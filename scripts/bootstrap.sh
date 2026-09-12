#!/usr/bin/env bash
# One-time setup for a fresh Mac. Idempotent.
#  1. Installs the Gradle wrapper jar if it is missing (pinned to gradle-wrapper.properties)
#  2. Points local.properties at the Android SDK if Android Studio has installed one
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

GRADLE_VERSION="$(sed -n 's/.*gradle-\([0-9.]*\)-bin.zip/\1/p' gradle/wrapper/gradle-wrapper.properties)"
BASE="https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}"

if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  echo "Fetching Gradle wrapper ${GRADLE_VERSION} ..."
  curl -fsSL "${BASE}/gradle/wrapper/gradle-wrapper.jar" -o gradle/wrapper/gradle-wrapper.jar
  curl -fsSL "${BASE}/gradlew" -o gradlew
  curl -fsSL "${BASE}/gradlew.bat" -o gradlew.bat
  chmod +x gradlew
fi

SDK_DIR="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
touch local.properties
if ! grep -q '^sdk.dir=' local.properties; then
  if [ -d "$SDK_DIR" ]; then
    echo "sdk.dir=$SDK_DIR" >> local.properties
  else
    echo "!! No Android SDK found at $SDK_DIR. Install Android Studio, open it once, re-run."
  fi
fi
echo "Done. Next: ./gradlew assembleDebug"
