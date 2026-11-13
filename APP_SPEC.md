# Colab Android App — Product & Technical Specification

```yaml
app_name: "Colab"
package_name: "com.pox.colab"
design_system: "Material Design 3 Expressive (M3) & AMOLED Pure Black"
architecture: "MVVM + Jetpack Compose + Coroutines/Flow + Clean Architecture"
target_environment: "Android 8.0+ (API 26 to API 34+)"
reference_apps:
  - "https://m3.material.io/get-started"
  - "https://github.com/rukamori/ArchiveTune"
```

---

## 1. Design & UI/UX Principles

* **Pure Material 3 Expressive System**:
  * Adherence to official [M3 Design Guidelines](https://m3.material.io/) and ArchiveTune component architecture.
  * **AMOLED Pure Black (`#000000`)**: Deep black base with M3 tonal elevation containers:
    * `surfaceContainerLowest`: `#000000`
    * `surfaceContainerLow`: `#0C0C0C`
    * `surfaceContainer`: `#121212`
    * `surfaceContainerHigh`: `#1A1A1A`
    * `surfaceContainerHighest`: `#242424`
  * **Shape Scale**: Expressive rounded shapes (`12.dp`, `16.dp`, `20.dp`, `28.dp`, and `CircleShape` for action buttons).
  * **Shimmer & Micro-Interactions**: Custom skeleton shimmer placeholders (`ListItemPlaceholder`, `GridItemPlaceholder`, `ButtonPlaceholder`) for asynchronous loading states, and animated pulsing dots (`StatusIndicatorDot`) for active VM telemetry.

---

## 2. Core Architecture & Navigation

```
├── HomeScreen (Cards Grid + Circular New VM FAB)
│    ├── New VM Modal (Easy Mode vs Advanced Mode)
│    └── Account Switcher & Search
│
├── VMDetailsScreen (3 Dedicated Tabs)
│    ├── Tab 1: Dashboard (Live CPU/RAM/GPU telemetry & Bootstrap Script)
│    ├── Tab 2: Console (Multi-Tab Resilient SSH / tmux Terminal)
│    └── Tab 3: File Manager (Remote file browser, downloads & uploads)
│
└── SettingsScreen (Unified Settings Hubs)
     ├── Accounts Hub (Alpine Colab CLI PKCE OAuth, Tiers & Quota)
     ├── Theme Hub (Color palettes, AMOLED black & per-VM terminal styling)
     └── App Info & Updates Manager (GitHub Releases API client)
```

---

## 3. Screen Specifications

### A. Home Screen
* **Instance Grid / Cards**:
  * Displays all configured Alpine Colab VM instances in responsive grid cards.
  * Card metadata: VM instance name, active Google Account / SSH endpoint, live status dot (Running, Stopped, Reconnecting, Error).
  * Semantic AssistChips: Hardware accelerator tag (CPU / T4 / V100 / A100), resilient protocol tag (`tmux-ssh` / `mosh`).
  * Live mini-telemetry bar (real-time CPU load indicator).
  * Primary actions: `Terminal` (Filled primary button) and `Files` (Filled tonal button).
  * Context menu: Mark Online/Offline, Delete instance.
* **New VM FAB**:
  * Prominent circular Floating Action Button (`shape = CircleShape`) with `+` icon.
* **Search & Filter**:
  * Pill-shaped search bar (`RoundedCornerShape(24.dp)`) with clear button for real-time VM filtering.

### B. New VM Creator Modal (`AddVMDialog`)
* **Two Creation Modes**:
  1. **Easy Mode**: Single-tap auto-generation. Automatically provisions random unique instance name, secure root credentials, optimal hardware accelerator, tunnel provider, and protocol.
  2. **Advanced Mode**: Manual configuration with intelligent auto-fill:
     * Instance Name & Root Password (auto-generates unique fallback if left blank).
     * Hardware Accelerator selection: None / T4 GPU / V100 GPU / A100 GPU.
     * Tunnel Provider selection: Cloudflare / Ngrok / Localtunnel / Tailscale.
     * Resilient Protocol: tmux Auto-Reattach SSH / Mosh-UDP.
     * Associated Google Account assignment.

### C. VM Details (3-Tab Experience)
* **Tab 1: Dashboard**:
  * Live telemetry gauges: CPU Load (%), RAM Usage (Allocated / Total), GPU Load & VRAM with temperature sensor (°C), and Disk Usage (`/`).
  * Real-time OkHttp telemetry polling worker.
  * Alpine Container Bootstrap box: One-click script generator containing the full Colab cell bootstrap snippet with copy & power toggle actions.
* **Tab 2: Console (Multi-Tab Resilient SSH)**:
  * **Multi-Tab Session Architecture**: Top scrollable session row with `+` button to create concurrent terminal sessions and `x` to close.
  * **Disconnection Resistance**: Uses SSH over `tmux` wrapper; sessions persist and re-attach seamlessly on network interruption or tunnel reconnection.
  * Per-session buffer, interactive command input field, quick-action accessory keys (Ctrl, Tab, Esc, Arrows), and session clear.
  * Dynamic background coloring driven by the user's Theme Hub preference.
* **Tab 3: File Manager**:
  * Remote directory navigation bar with breadcrumbs and one-touch refresh.
  * File list with file type icons, size in bytes/KB/MB, and modification timestamps.
  * Interactive operations: Download file, preview text/code, upload local file, create directory, rename, and delete.
  * Expressive skeleton shimmer loaders during folder fetches.

### D. Settings Hubs
* **Accounts Hub**:
  * **Add Account Flow**: Native dialog emulating Alpine `colab auth login` CLI workflow with OAuth2 PKCE URL generation, copy-to-clipboard button, and auth code submission.
  * **Account Controls**: Radio button active account selection, switch to enable/pause account, tier assignment (`Pro` vs. `Free`), and account removal.
* **Theme Hub**:
  * **Palettes**: Colab Amber, Matrix Green, Cyber Cyan, Neon Violet, Monochrome.
  * **Display Modes**: AMOLED Pure Black (`#000000`), Dark Theme toggle, Dynamic Color (Material You Android 12+).
  * **Terminal Customization**: Global terminal background selector as well as granular **per-VM background customization**.
* **App Info & Updates Manager**:
  * Direct GitHub API integration (`api.github.com/repos/pitpitpo/colab/releases`).
  * Compares installed version (`v1.0.0`) with latest GitHub release.
  * Parses markdown release notes, changelog, publication dates, and provides direct `.apk` / asset download triggers.

---

## 4. Internal Alpine Linux Container & Colab CLI Layer
* **Architecture**:
  * **Embedded in the App Itself**: The app hosts a minimal Alpine Linux container environment internally on the device/app runtime.
  * **CLI Multi-Tenancy (One Container, Multi-CLI)**:
    * The single Alpine container acts as the local controller running the Colab CLI environment.
    * For **each connected Colab account**, a dedicated Colab CLI instance/environment is spawned inside this internal Alpine container.
  * **Instance & Auth Management**:
    * Pressing "Add Account" in Settings initializes a new CLI instance inside the internal Alpine container.
    * The CLI instance initiates the OAuth PKCE flow (`colab auth login`) to generate the Google authorization link and passes it back to the user.
    * Each account's CLI instance maintains isolated credentials, manages its respective Colab instances, and controls compute quotas/auth tokens.
  * **Automatic Upgrades**:
    * Automatically updates packages and CLI tools inside the Alpine container on startup:
      ```sh
      apk update && apk upgrade && pip install --upgrade --no-cache-dir google-colab-cli tmux openssh
      ```
  * **Resilient Connection**:
    * Persistent `tmux` session daemon with keep-alive (`ClientAliveInterval 10`, `TCPKeepAlive yes`), resilient against network disconnections.


