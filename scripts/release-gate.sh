#!/usr/bin/env bash
# Release gate: deterministic physics lab + on-device playtest.
#
# 1. Runs the JVM physics lab (app/src/test/.../PhysicsLabTest.kt) and fails on
#    any regression in direction, roll distance, cushions or pockets.
# 2. Builds the debug APK.
# 3. If an emulator/device is attached, installs it, opens practice mode and
#    records a shot sequence in build/playtest/<timestamp>/ for visual review.
#
# Usage: scripts/release-gate.sh [--lab-only]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PKG="com.ldmaster.sinuca"
APK="app/build/outputs/apk/debug/app-debug.apk"
LAB="$ROOT/app/build/reports/physics-lab.md"

echo "== 1. physics lab =="
./gradlew :app:testDebugUnitTest --console=plain
echo "report: $LAB"

if [[ "${1:-}" == "--lab-only" ]]; then
  exit 0
fi

echo "== 2. apk =="
./gradlew :app:assembleDebug --console=plain

if ! command -v adb >/dev/null || ! adb devices | awk 'NR>1 && $2=="device" {found=1} END {exit !found}'; then
  echo "no device/emulator attached: skipping the on-device playtest"
  exit 0
fi

echo "== 3. on-device playtest =="
STAMP="$(date +%Y%m%dT%H%M%SZ)"
OUT="$ROOT/build/playtest/$STAMP"
mkdir -p "$OUT"

adb install -r "$APK" >/dev/null
adb shell am force-stop "$PKG"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 2

# Tap the "Treino local" button by reading its real bounds from uiautomator.
adb shell uiautomator dump /sdcard/gate-ui.xml >/dev/null 2>&1
BOUNDS="$(adb shell cat /sdcard/gate-ui.xml \
  | tr -d '\r' \
  | tr '>' '\n' \
  | grep 'Treino local' \
  | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' \
  | head -1 \
  | grep -o '[0-9]\+' | paste -sd' ' -)"
read -r X1 Y1 X2 Y2 <<<"$BOUNDS"
adb shell input tap $(( (X1 + X2) / 2 )) $(( (Y1 + Y2) / 2 ))
sleep 4

# Landscape size and density, used by the HUD for its hit areas.
adb exec-out screencap -p > "$OUT/00-baseline.png"
read -r W H <<<"$(sips -g pixelWidth -g pixelHeight "$OUT/00-baseline.png" | awk '/pixel/ {print $2}' | paste -sd' ' -)"
DENSITY="$(adb shell wm density | awk '{print $3}')"
DP="$(awk -v d="$DENSITY" 'BEGIN {print d/160}')"
CAM_X="$(awk -v w="$W" -v d="$DP" 'BEGIN {printf "%d", w - 60*d}')"
TRAJ_X="$(awk -v w="$W" -v d="$DP" 'BEGIN {printf "%d", w - 98*d}')"
TOP_Y="$(awk -v d="$DP" 'BEGIN {printf "%d", 22*d}')"
PW_X="$(awk -v w="$W" -v d="$DP" 'BEGIN {printf "%d", w - 19*d}')"
PW_TOP="$(awk -v d="$DP" 'BEGIN {printf "%d", 52*d}')"
PW_BOTTOM="$(awk -v h="$H" -v d="$DP" 'BEGIN {printf "%d", h - 78*d}')"
SHOOT_X="$(awk -v w="$W" -v d="$DP" 'BEGIN {printf "%d", w - 42*d}')"
SHOOT_Y="$(awk -v h="$H" -v d="$DP" 'BEGIN {printf "%d", h - 42*d}')"
TABLE_X="$(( W / 2 ))"
TABLE_Y="$(( H / 2 ))"

shoot() {
  adb shell input tap "$SHOOT_X" "$SHOOT_Y"
}

power() {
  local percent="$1"
  local y
  y="$(awk -v top="$PW_TOP" -v bottom="$PW_BOTTOM" -v p="$percent" \
    'BEGIN {printf "%d", bottom - (bottom - top) * p / 100}')"
  adb shell input tap "$PW_X" "$y"
}

frame() {
  adb exec-out screencap -p > "$OUT/$1.png"
  sleep 0.25
}

echo "top view + trajectory"
adb shell input tap "$CAM_X" "$TOP_Y"
sleep 1
adb shell input tap "$TRAJ_X" "$TOP_Y"
sleep 1
frame 01-top-trajectory

echo "soft tap (3%)"
power 3
frame 02-power-3
shoot
for index in 1 2 3 4 5 6; do frame "03-tap-$index"; done

echo "firm shot (55%)"
power 55
frame 04-power-55
shoot
for index in 1 2 3 4 5 6 7 8; do frame "05-shot-$index"; done

echo "frames: $OUT"
