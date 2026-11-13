package com.pox.colab.engine

import com.pox.colab.model.ColabVM
import com.pox.colab.model.TunnelProvider

object ColabBootstrapGenerator {

    /**
     * Generates a fully autonomous bootstrap script that runs inside a Google Colab notebook cell
     * on standard cloud Ubuntu (equipped with NVIDIA GPU drivers, Python 3, and CUDA).
     * It installs and configures OpenSSH server, persistent tmux session, tunnel provider,
     * Google Colab OAuth credentials, and host telemetry agent directly without any
     * Alpine Linux chroot or minirootfs.
     */
    fun generateBootstrapScript(vm: ColabVM, accountTokenOrCookie: String? = null): String {
        val tunnelCmd = when (vm.tunnelProvider) {
            TunnelProvider.CLOUDFLARE -> """
                # Install Cloudflared Tunnel
                if ! command -v cloudflared &> /dev/null; then
                    curl -sL https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -o /usr/local/bin/cloudflared
                    chmod +x /usr/local/bin/cloudflared
                fi
                echo "[*] Starting Cloudflare Quick Tunnel..."
                cloudflared tunnel --url ssh://localhost:${vm.sshConfig.port} > /tmp/tunnel.log 2>&1 &
                echo "[*] Run 'grep -o 'https://.*trycloudflare.com' /tmp/tunnel.log' to view the tunnel endpoint."
            """.trimIndent()
            TunnelProvider.BORE -> """
                # Install bore tcp tunnel
                if ! command -v bore &> /dev/null; then
                    curl -sL https://github.com/ekzhang/bore/releases/download/v0.5.1/bore-v0.5.1-x86_64-unknown-linux-musl.tar.gz | tar -xz -C /usr/local/bin
                fi
                echo "[*] Starting Bore TCP Tunnel..."
                bore local ${vm.sshConfig.port} --to bore.pub > /tmp/tunnel.log 2>&1 &
                echo "[*] Run 'cat /tmp/tunnel.log' to view your assigned bore.pub:<port> endpoint."
            """.trimIndent()
            TunnelProvider.NGROK -> """
                # Ngrok Tunnel setup
                echo "[*] Starting Ngrok SSH Tunnel..."
                ngrok tcp ${vm.sshConfig.port} > /tmp/tunnel.log 2>&1 &
            """.trimIndent()
            TunnelProvider.CUSTOM -> """
                echo "[*] Using custom tunnel / direct host"
            """.trimIndent()
        }

        val authSetup = if (!accountTokenOrCookie.isNullOrBlank()) {
            val rawToken = if (accountTokenOrCookie.trim().startsWith("{")) {
                try {
                    org.json.JSONObject(accountTokenOrCookie).optString("token", "").ifBlank {
                        org.json.JSONObject(accountTokenOrCookie).optString("access_token", accountTokenOrCookie)
                    }
                } catch (_: Exception) {
                    accountTokenOrCookie
                }
            } else {
                accountTokenOrCookie
            }
            val sanitized = rawToken.replace("\"", "\\\"").replace("'", "'\\''")
            """
                # Configure Google Colab OAuth credentials directly on Colab host
                mkdir -p ~/.colab ~/.config/colab
                cat << 'AUTH_JSON' > ~/.colab/credentials.json
                {
                  "access_token": "$sanitized",
                  "token_type": "Bearer"
                }
                AUTH_JSON
                chmod 600 ~/.colab/credentials.json
                export COLAB_AUTH_TOKEN='$sanitized'
                echo "export COLAB_AUTH_TOKEN='$sanitized'" >> ~/.bashrc
            """.trimIndent()
        } else {
            "# No account token provided for automated auth setup"
        }

        return """
            #!/usr/bin/env bash
            # ==============================================================================
            # Colab Native Ubuntu + CLI + Resilient SSH Environment Bootstrapper
            # VM Name: ${vm.name}
            # Target Accelerator: ${vm.hardwareAccelerator.label}
            # ==============================================================================
            set -euo pipefail

            echo "=== [1/5] Updating APT & Installing Core Packages ==="
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -y
            apt-get install -y --no-install-recommends openssh-server tmux curl git rsync python3

            echo "=== [2/5] Configuring Native OpenSSH Server ==="
            mkdir -p /var/run/sshd /etc/ssh
            ssh-keygen -A 2>/dev/null || true
            echo 'root:${vm.sshConfig.password}' | chpasswd

            # Configure /etc/ssh/sshd_config with PermitRootLogin, PasswordAuthentication, ClientAliveInterval
            sed -i 's/^[# ]*PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config || true
            grep -q '^PermitRootLogin' /etc/ssh/sshd_config || echo 'PermitRootLogin yes' >> /etc/ssh/sshd_config

            sed -i 's/^[# ]*PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config || true
            grep -q '^PasswordAuthentication' /etc/ssh/sshd_config || echo 'PasswordAuthentication yes' >> /etc/ssh/sshd_config

            echo 'ClientAliveInterval 10' >> /etc/ssh/sshd_config
            echo 'ClientAliveCountMax 9999' >> /etc/ssh/sshd_config
            echo 'TCPKeepAlive yes' >> /etc/ssh/sshd_config

            # Start OpenSSH daemon
            /usr/sbin/sshd -D -p ${vm.sshConfig.port} &

            # Provide colab-cli convenience command on host
            mkdir -p /usr/local/bin
            cat << 'CLICONTENT' > /usr/local/bin/colab-cli
#!/bin/sh
# Colab CLI wrapper: delegates directly to official Google Colab CLI tool
if command -v colab >/dev/null 2>&1; then
    case "${'$'}1" in
        status)
            echo '=== Colab Runtime ==='
            echo "Uptime: $(uptime)"
            echo 'Disk:'
            df -h /
            echo 'Memory:'
            free -m
            ;;
        gpu)
            if command -v nvidia-smi >/dev/null 2>&1; then
                nvidia-smi
            else
                echo 'CPU mode (No GPU attached)'
            fi
            ;;
        *)
            exec colab "${'$'}@"
            ;;
    esac
else
    case "${'$'}1" in
        status)
            echo '=== Colab Runtime ==='
            echo "Uptime: $(uptime)"
            df -h /
            free -m
            ;;
        gpu)
            if command -v nvidia-smi >/dev/null 2>&1; then nvidia-smi; else echo 'CPU mode (No GPU attached)'; fi
            ;;
        *)
            python3 -m colab_cli.cli "${'$'}@" 2>/dev/null || echo 'Usage: colab-cli [status|gpu|metrics|sessions|auth]'
            ;;
    esac
fi
CLICONTENT
            chmod +x /usr/local/bin/colab-cli

            $authSetup

            echo "=== [3/5] Spawning Persistent Tmux Session ==="
            tmux new-session -d -s "${vm.sshConfig.tmuxSessionName}" 'bash' || true

            echo "=== [4/5] Starting Tunnel Provider ==="
            $tunnelCmd

            echo "=== [5/5] Launching Real Telemetry Daemon ==="
            cat << 'AGENT_PY' > /content/agent.py
import http.server
import json
import os
import shutil
import subprocess
import threading
import time

PORT = 8080

def collect_metrics():
    try:
        with open('/proc/loadavg') as f:
            load = f.read().split()[:3]
    except Exception:
        load = ["0.00", "0.00", "0.00"]

    try:
        with open('/proc/meminfo') as f:
            mem = {l.split(':')[0].strip(): int(l.split(':')[1].split()[0]) * 1024 for l in f if ':' in l}
        ram_total = mem.get('MemTotal', 0)
        ram_free = mem.get('MemAvailable', mem.get('MemFree', 0))
        ram_used = ram_total - ram_free
    except Exception:
        ram_total, ram_used = 0, 0

    try:
        st = os.statvfs('/')
        disk_total = st.f_blocks * st.f_frsize
        disk_used = (st.f_blocks - st.f_bavail) * st.f_frsize
    except Exception:
        disk_total, disk_used = 0, 0

    gpu_name = None
    vram_total = 0
    vram_used = 0
    gpu_temp = 0
    gpu_util = 0.0
    try:
        out = subprocess.check_output(
            ['nvidia-smi', '--query-gpu=name,memory.total,memory.used,temperature.gpu,utilization.gpu', '--format=csv,noheader,nounits'],
            timeout=2
        ).decode().strip()
        parts = [p.strip() for p in out.split(',')]
        if len(parts) >= 4:
            gpu_name = parts[0]
            vram_total = int(parts[1]) * 1024 * 1024
            vram_used = int(parts[2]) * 1024 * 1024
            gpu_temp = int(parts[3])
            if len(parts) >= 5:
                try:
                    gpu_util = float(parts[4])
                except Exception:
                    gpu_util = 0.0
    except Exception:
        pass

    cpu_pct = 0.0
    try:
        cpu_pct = min(100.0, float(load[0]) * 20.0)
    except Exception:
        cpu_pct = 0.0

    uptime_sec = 0
    try:
        with open("/proc/uptime", "r") as f:
            uptime_sec = int(float(f.readline().split()[0]))
    except Exception:
        uptime_sec = 0

    return {
        "status": "RUNNING",
        "cpu_percent": round(cpu_pct, 1),
        "load_average": ", ".join(load),
        "ram_total_bytes": ram_total,
        "ram_used_bytes": ram_used,
        "disk_total_bytes": disk_total,
        "disk_used_bytes": disk_used,
        "gpu_model": gpu_name,
        "gpu_vram_total_bytes": vram_total,
        "gpu_vram_used_bytes": vram_used,
        "gpu_temperature_celsius": gpu_temp,
        "gpu_usage_percent": round(gpu_util, 1),
        "uptime_seconds": uptime_sec,
        "timestamp": time.time()
    }

class ColabAgentHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path in ("/api/status", "/api/metrics"):
            data = collect_metrics()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(data).encode())
        else:
            return super().do_GET()

def metrics_writer_loop():
    while True:
        try:
            m = collect_metrics()
            with open('/tmp/colab_metrics.json', 'w') as f:
                json.dump(m, f)
        except Exception:
            pass
        time.sleep(3)

threading.Thread(target=metrics_writer_loop, daemon=True).start()
http.server.HTTPServer(("", PORT), ColabAgentHandler).serve_forever()
AGENT_PY
            nohup python3 /content/agent.py > /tmp/agent.log 2>&1 &

            echo "=========================================================="
            echo "      COLAB UBUNTU RUNTIME INITIALIZED SUCCESSFULLY       "
            echo "=========================================================="
        """.trimIndent()
    }
}

