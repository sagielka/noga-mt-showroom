#!/usr/bin/env bash
# Convenience wrapper: builds the debug APK and prints where it landed.
set -euo pipefail
cd "$(dirname "$0")"

if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" && ! -f local.properties ]]; then
  echo "No Android SDK found."
  echo "Either open this folder in Android Studio once (it writes local.properties),"
  echo "or set ANDROID_HOME to your SDK path, or copy local.properties.example."
  exit 1
fi

./gradlew clean assembleDebug
echo
echo "Debug APK:   $(pwd)/app/build/outputs/apk/debug/app-debug.apk"

if ./gradlew assembleRelease; then
  echo "Release APK: $(pwd)/app/build/outputs/apk/release/app-release-unsigned.apk"
  echo "(unsigned - see README 'Signing a release build')"
fi
