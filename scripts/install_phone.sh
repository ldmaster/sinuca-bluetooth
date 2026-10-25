#!/usr/bin/env bash
# Install the newest built release APK over USB, without the installer UI.
#
#   ./scripts/install_phone.sh                 # uses the local release APK
#   ./scripts/install_phone.sh path/to/app.apk
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
APK=${1:-$ROOT/app/build/outputs/apk/release/app-release.apk}

if [[ ! -f "$APK" ]]; then
  echo "APK not found: $APK" >&2
  echo "Build it with: ./gradlew :app:assembleRelease" >&2
  exit 2
fi

if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "No device connected. Enable USB debugging and accept the prompt." >&2
  "$ADB" devices
  exit 2
fi

echo "Expected signing certificate:"
echo "  SHA-256 f9f09a4c086a837207cb31f04c83d7cc72e8f5124a881d9ccca639b16fb87be3"
"$ADB" install -r "$APK"
echo "Installed. The update keeps the same package and signature, so the"
echo "scoreboard and the AI learning survive."
