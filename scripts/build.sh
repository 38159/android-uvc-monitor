#!/usr/bin/env bash
# Build the APK using an installed Gradle 8.7 and Android SDK.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ -x ./gradlew ]]; then
  GRADLE=./gradlew
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=$(command -v gradle)
else
  echo 'Gradle 8.7 not found. Install it and add gradle to PATH.' >&2
  exit 1
fi
if [[ ! -f local.properties && -z ${ANDROID_HOME:-} && -z ${ANDROID_SDK_ROOT:-} ]]; then
  echo 'Set ANDROID_HOME to your Android SDK directory or create local.properties with sdk.dir.' >&2
  exit 1
fi
"$GRADLE" :app:assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/uvc-monitor.apk
printf 'APK: %s\n' "$ROOT/dist/uvc-monitor.apk"
