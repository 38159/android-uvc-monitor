#!/usr/bin/env bash
# Install and launch the viewer without changing tablet system settings.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/dist/uvc-monitor.apk"
if [[ ${1:-} == -h || ${1:-} == --help ]]; then
  echo 'Usage: ./scripts/install.sh [adb-serial-or-ip:port]'
  echo 'Set ADB to the adb executable if it is not on PATH.'
  exit 0
fi
if (( $# > 1 )); then echo 'Usage: ./scripts/install.sh [adb-serial-or-ip:port]' >&2; exit 1; fi
ADB=${ADB:-$(command -v adb || true)}
if [[ -z $ADB && -n ${ANDROID_HOME:-} && -x ${ANDROID_HOME}/platform-tools/adb ]]; then ADB="$ANDROID_HOME/platform-tools/adb"; fi
[[ -n $ADB ]] || { echo 'adb not found. Set ADB or add Android platform-tools to PATH.' >&2; exit 1; }
[[ -f $APK ]] || { echo 'APK not found. Run ./scripts/build.sh first.' >&2; exit 1; }
args=()
if (( $# )); then args=(-s "$1"); fi
"$ADB" "${args[@]}" get-state >/dev/null
if [[ $ADB == *.exe ]] && command -v wslpath >/dev/null 2>&1; then APK=$(wslpath -w "$APK"); fi
"$ADB" "${args[@]}" install -r "$APK"
"$ADB" "${args[@]}" shell am start -n ai.waion.uvcmonitor/.MainActivity
echo 'Connect the capture card and accept the USB permission prompt on the tablet.'
