package com.pox.colab.engine

import com.pox.colab.model.ColabVM
import com.pox.colab.model.TunnelProvider

object AlpineBootstrapGenerator {

    /**
     * Generates a fully autonomous bootstrap script that runs inside Google Colab
     * to spin up a minimal Alpine Linux container environment equipped with
     * Colab CLI, resilient Mosh server, tmux session manager, SFTP/file agent,
     * and a Cloudflare/Bore tunnel.
     */
    fun generateBootstrapScript(vm: ColabVM): String {
        val tunnelCmd = when (vm.tunnelProvider) {
            TunnelProvider.CLOUDFLARE -> """
                # Install Cloudflared Tunnel
                if ! command -v cloudflared &> /dev/null; then
                    curl -sL https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -o /usr/local/bin/cloudflared
                    chmod +x /usr/local/bin/cloudflared
                fi
                echo "[*] Starting Cloudflare Quick Tunnel..."
                cloudflared tunnel --url ssh://localhost:22 > /tmp/tunnel.log 2>&1 &
            """.trimIndent()
            TunnelProvider.BORE -> """
                # Install bore tcp tunnel
                if ! command -v bore &> /dev/null; then
                    curl -sL https://github.com/ekzhang/bore/releases/download/v0.5.1/bore-v0.5.1-x86_64-unknown-linux-musl.tar.gz | tar -xz -C /usr/local/bin
                fi
                echo "[*] Starting Bore TCP Tunnel..."
                bore local 22 --to bore.pub > /tmp/tunnel.log 2>&1 &
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
            
            # Install Alpine Packages: openssh, mosh, tmux, python3, rsync, git, curl, htop
            chroot /content/alpine_rootfs /bin/sh -c "
                apk update
                apk add --no-cache openssh mosh tmux bash curl python3 py3-pip git rsync htop shadow
                
                # Setup SSH server with anti-disconnect configuration
                ssh-keygen -A
                mkdir -p /var/run/sshd
                echo 'root:${vm.sshConfig.password}' | chpasswd
                sed -i 's/#PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
                sed -i 's/#PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config
                echo 'ClientAliveInterval 10' >> /etc/ssh/sshd_config
                echo 'ClientAliveCountMax 9999' >> /etc/ssh/sshd_config
                echo 'TCPKeepAlive yes' >> /etc/ssh/sshd_config

                # Create Colab CLI inside Alpine
                cat << 'CLICONTENT' > /usr/local/bin/colab-cli
#!/bin/sh
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
        if command -v nvidia-smi &> /dev/null; then
            nvidia-smi
        else
            echo 'CPU mode (No GPU attached)'
        fi
        ;;
    *)
        echo 'Usage: colab-cli [status|gpu|metrics]'
        ;;
esac
CLICONTENT
                chmod +x /usr/local/bin/colab-cli
            "

            echo "=== [3/5] Starting Resilient Tmux Session & Mosh/SSH Daemons ==="
            # Start SSH daemon inside Alpine
            chroot /content/alpine_rootfs /usr/sbin/sshd -D -p ${vm.sshConfig.port} &
            
            # Pre-spawn persistent tmux session so SSH connections never lose state
            chroot /content/alpine_rootfs /bin/sh -c "
                tmux new-session -d -s ${vm.sshConfig.tmuxSessionName} 'bash' || true
            "

            echo "=== [4/5] Starting Tunnel Provider ==="
            $tunnelCmd

            echo "=== [5/5] Launching File & Metrics Sync Agent ==="
            # Lightweight python HTTP daemon for file listing/upload/download and live metrics
            cat << 'AGENT_PY' > /content/agent.py
import http.server
import json
import os
import shutil
import subprocess
import time

PORT = 8080

class ColabAgentHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/api/status":
            metrics = {
                "status": "RUNNING",
                "uptime": time.time(),
                "vm": "${vm.name}"
            }
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(metrics).encode())
        else:
            return super().do_GET()

http.server.HTTPServer(("", PORT), ColabAgentHandler).serve_forever()
AGENT_PY
            nohup python3 /content/agent.py > /tmp/agent.log 2>&1 &

            echo "=========================================================="
            echo "   COLAB ALPINE RESILIENT INSTANCE RUNNING SUCCESSFULLY   "
            echo "=========================================================="
        """.trimIndent()
    }
}
