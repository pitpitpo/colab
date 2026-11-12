package com.pox.colab.engine

import com.pox.colab.model.ColabVM
import com.pox.colab.model.TunnelProvider

object AlpineBootstrapGenerator {

    /**
     * Generates a fully autonomous bootstrap script that runs inside Google Colab
     * to spin up a minimal Alpine Linux container environment equipped with
     * official Colab CLI, resilient SSH + Tmux session manager, SFTP server,
     * live telemetry agent, and a Cloudflare/Bore tunnel.
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
                cloudflared tunnel --url ssh://localhost:22 > /tmp/tunnel.log 2>&1 &
                echo "[*] Run 'grep -o 'https://.*trycloudflare.com' /tmp/tunnel.log' to view the tunnel endpoint."
            """.trimIndent()
            TunnelProvider.BORE -> """
                # Install bore tcp tunnel
                if ! command -v bore &> /dev/null; then
                    curl -sL https://github.com/ekzhang/bore/releases/download/v0.5.1/bore-v0.5.1-x86_64-unknown-linux-musl.tar.gz | tar -xz -C /usr/local/bin
                fi
                echo "[*] Starting Bore TCP Tunnel..."
                bore local 22 --to bore.pub > /tmp/tunnel.log 2>&1 &
                echo "[*] Run 'cat /tmp/tunnel.log' to view your assigned bore.pub:<port> endpoint."
            """.trimIndent()
            TunnelProvider.NGROK -> """
                # Ngrok Tunnel setup
                echo "[*] Starting Ngrok SSH Tunnel..."
                ngrok tcp 22 > /tmp/tunnel.log 2>&1 &
            """.trimIndent()
            TunnelProvider.CUSTOM -> """
                echo "[*] Using custom tunnel / direct host"
            """.trimIndent()
        }

        val authSetup = if (!accountTokenOrCookie.isNullOrBlank()) {
            val sanitized = accountTokenOrCookie.replace("\"", "\\\"").replace("'", "'\\''")
            """
                # Configure Google Colab OAuth credentials in Alpine
                mkdir -p /content/alpine_rootfs/root/.colab /content/alpine_rootfs/root/.config/colab
                cat << 'AUTH_JSON' > /content/alpine_rootfs/root/.colab/credentials.json
                {
                  "access_token": "$sanitized",
                  "token_type": "Bearer"
                }
                AUTH_JSON
                chmod 600 /content/alpine_rootfs/root/.colab/credentials.json
                echo "export COLAB_AUTH_TOKEN='$sanitized'" >> /content/alpine_rootfs/etc/profile
            """.trimIndent()
        } else {
            "# No account token provided for automated auth setup"
        }

        return """
            #!/usr/bin/env bash
            # ==============================================================================
            # Colab Alpine + CLI + Resilient SSH Environment Bootstrapper
            # VM Name: ${vm.name}
            # Target Accelerator: ${vm.hardwareAccelerator.label}
            # ==============================================================================
            set -euo pipefail

            echo "=== [1/5] Setting up Minimal Alpine Container & Environment ==="
            mkdir -p /content/alpine_rootfs
            ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/x86_64/alpine-minirootfs-3.19.1-x86_64.tar.gz"

            if [ ! -f /content/alpine.tar.gz ]; then
                echo "Downloading minimal Alpine Linux rootfs..."
                curl -sL "${'$'}ALPINE_URL" -o /content/alpine.tar.gz
                tar -xzf /content/alpine.tar.gz -C /content/alpine_rootfs
            fi

            echo "=== [2/5] Configuring Alpine Chroot & Colab CLI Tools ==="
            # Setup DNS and proc
            cp /etc/resolv.conf /content/alpine_rootfs/etc/
            
            # Install Alpine Packages: openssh, tmux, python3, pip, compilation tools, rsync, git, curl, htop
            chroot /content/alpine_rootfs /bin/sh -c "
                apk update && apk upgrade --no-cache
                apk add --no-cache openssh tmux bash curl python3 py3-pip python3-dev gcc musl-dev libffi-dev git rsync htop shadow
                
                # Install and automatically update official Google Colab CLI inside Alpine
                pip install --upgrade --break-system-packages --no-cache-dir google-colab-cli || true

                # Setup SSH server with anti-disconnect configuration
                ssh-keygen -A
                mkdir -p /var/run/sshd
                echo 'root:${vm.sshConfig.password}' | chpasswd
                sed -i 's/#PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
                sed -i 's/#PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config
                echo 'ClientAliveInterval 10' >> /etc/ssh/sshd_config
                echo 'ClientAliveCountMax 9999' >> /etc/ssh/sshd_config
                echo 'TCPKeepAlive yes' >> /etc/ssh/sshd_config

                # Create Colab CLI tool inside Alpine linking to official colab CLI
                cat << 'CLICONTENT' > /usr/local/bin/colab-cli
#!/bin/sh
# Colab CLI wrapper: delegates directly to official Google Colab CLI tool
if command -v colab >/dev/null 2>&1; then
    case "${'$'}1" in
        status)
            echo '=== Colab Alpine Runtime ==='
            echo 'Uptime: '\$(uptime)
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
            echo '=== Colab Alpine Runtime ==='
            echo 'Uptime: '\$(uptime)
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
                ln -sf /usr/local/bin/colab-cli /usr/bin/colab-cli 2>/dev/null || true
            "

            $authSetup

            echo "=== [3/5] Starting Resilient Tmux Session & SSH Daemon ==="
            # Start SSH daemon inside Alpine
            chroot /content/alpine_rootfs /usr/sbin/sshd -D -p ${vm.sshConfig.port} &
            
            # Pre-spawn persistent tmux session so SSH connections never lose state
            chroot /content/alpine_rootfs /bin/sh -c "
                tmux new-session -d -s ${vm.sshConfig.tmuxSessionName} 'bash' || true
            "

            echo "=== [4/5] Starting Tunnel Provider ==="
            $tunnelCmd

            echo "=== [5/5] Launching Real Telemetry Daemon ==="
            # Comprehensive Python daemon collecting real Linux CPU, RAM, Disk, and GPU telemetry
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
    try:
        out = subprocess.check_output(
            ['nvidia-smi', '--query-gpu=name,memory.total,memory.used,temperature.gpu', '--format=csv,noheader,nounits'],
            timeout=2
        ).decode().strip()
        parts = [p.strip() for p in out.split(',')]
        if len(parts) >= 4:
            gpu_name = parts[0]
            vram_total = int(parts[1]) * 1024 * 1024
            vram_used = int(parts[2]) * 1024 * 1024
            gpu_temp = int(parts[3])
    except Exception:
        pass

    try:
        cpu_pct = min(100.0, float(load[0]) * 20.0)
    except Exception:
        cpu_pct = 0.0

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
            # also copy into chroot so SSH exec sees it immediately
            with open('/content/alpine_rootfs/tmp/colab_metrics.json', 'w') as f:
                json.dump(m, f)
        except Exception:
            pass
        time.sleep(3)

threading.Thread(target=metrics_writer_loop, daemon=True).start()
http.server.HTTPServer(("", PORT), ColabAgentHandler).serve_forever()
AGENT_PY
            nohup python3 /content/agent.py > /tmp/agent.log 2>&1 &

            echo "=========================================================="
            echo "   COLAB ALPINE RESILIENT INSTANCE RUNNING SUCCESSFULLY   "
            echo "=========================================================="
        """.trimIndent()
    }
}
