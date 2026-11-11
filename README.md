# Colab (Android Client & Orchestrator)

A native Android management and terminal client for Google Colab GPU/TPU instances with resilient SSH, real SFTP file management, live system telemetry, and official `google-colab-cli` OAuth 2.0 PKCE authentication.

## Features

- **Official Google Colab CLI OAuth Flow**:
  - Implements the exact RFC 7636 PKCE remote flow used by `google-colab-cli` (`InstalledAppFlow` with `token_usage=remote`).
  - Terminal-styled CLI login dialog matching `colab auth login --auth=oauth2`.
  - Automatic identity extraction (Email & Profile) via Google UserInfo API and ID token JWT decoding.
  - Automatic CCU balance and subscription tier detection (`/tun/m/ccu-info`).
  - Serializes credentials into the official `~/.config/colab-cli/token.json` format.

- **Alpine Linux Instance Bootstrap**:
  - Automated bootstrap script generation configured for Alpine Linux.
  - Runs inside Colab runtimes to deploy OpenSSH, Tmux, Mosh, Python agent telemetry, and Cloudflare / Bore / Ngrok tunnels.

- **Resilient SSH Terminal**:
  - Embedded SSH terminal with support for standard SSH and persistent Tmux session attach/reattach across network reconnects.

- **Filesystem Management**:
  - SFTP-backed remote filesystem explorer supporting navigation, viewing, downloading, and uploading files directly to and from the Colab VM.

- **Live Telemetry & Dashboard**:
  - Real-time CPU, RAM, GPU, and Disk utilization polling via background agent.

## Architecture & Tech Stack

- **Target SDK**: Android 14 (API 34)
- **Min SDK**: Android 8.0 (API 26)
- **UI**: Jetpack Compose, Material Design 3 (M3)
- **Networking & SSH**: JSch, OkHttp 4.12
- **Language**: Kotlin 1.9.22 / JDK 17
