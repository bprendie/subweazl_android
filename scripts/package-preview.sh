#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
task_java="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
task_sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JAVA_HOME="$task_java" ./gradlew :app:assembleRelease :app:assembleDebug
mkdir -p build/outputs
JAVA_HOME="$task_java" "$task_sdk/build-tools/36.0.0/apksigner" sign \
  --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --out build/outputs/Subweazl-1.3.1-preview.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
JAVA_HOME="$task_java" "$task_sdk/build-tools/36.0.0/apksigner" verify build/outputs/Subweazl-1.3.1-preview.apk
