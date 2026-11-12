#!/usr/bin/env bash
# ==============================================================================
# Colab Android Client - Build, Test & CI Script
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 1. Detect / Setup Java 17
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -d "$SCRIPT_DIR/tools/jdk-17.0.21+9" ]; then
    export JAVA_HOME="$SCRIPT_DIR/tools/jdk-17.0.21+9"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi

# 2. Detect / Setup Android SDK
if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -d "$SCRIPT_DIR/tools/android-sdk" ]; then
    export ANDROID_HOME="$SCRIPT_DIR/tools/android-sdk"
    export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
  elif [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  fi
fi

# 3. Determine Gradle binary (wrapper or local)
GRADLE_BIN="./gradlew"
if [ ! -f "$GRADLE_BIN" ]; then
  if [ -f "$SCRIPT_DIR/tools/gradle-8.5/bin/gradle" ]; then
    GRADLE_BIN="$SCRIPT_DIR/tools/gradle-8.5/bin/gradle"
  else
    GRADLE_BIN="gradle"
  fi
fi

chmod +x "$GRADLE_BIN" 2>/dev/null || true

ACTION="${1:-debug}"

echo "=================================================="
echo " Building Colab Android App"
echo " Target: $ACTION"
echo " Gradle: $GRADLE_BIN"
echo " Java:   $(java -version 2>&1 | head -n 1)"
echo "=================================================="

case "$ACTION" in
  debug)
    echo "[*] Compiling Debug APK..."
    "$GRADLE_BIN" assembleDebug
    echo "[+] Build complete!"
    echo "[+] APK: app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
      ls -lh app/build/outputs/apk/debug/app-debug.apk
    fi
    ;;

  release)
    echo "[*] Compiling Release APK..."
    "$GRADLE_BIN" assembleRelease
    echo "[+] Build complete!"
    echo "[+] APK: app/build/outputs/apk/release/app-release-unsigned.apk"
    if [ -f "app/build/outputs/apk/release/app-release-unsigned.apk" ]; then
      ls -lh app/build/outputs/apk/release/app-release-unsigned.apk
    fi
    ;;

  all)
    echo "[*] Compiling Debug and Release APKs..."
    "$GRADLE_BIN" assembleDebug assembleRelease
    echo "[+] All builds complete!"
    ;;

  clean)
    echo "[*] Cleaning build artifacts..."
    "$GRADLE_BIN" clean
    ;;

  ci)
    echo "[*] Running CI build & verification..."
    "$GRADLE_BIN" lintDebug assembleDebug assembleRelease --stacktrace
    echo "[+] CI pipeline check passed!"
    ;;

  *)
    echo "Usage: $0 [debug|release|all|clean|ci]"
    exit 1
    ;;
esac
