#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Requires an isolated actual Navidrome server on port 4537 (see docs/VALIDATION.md).
# Never point this test at a personal/production server: it edits fixture playlists/favorites.
task_java="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
task_sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
task_adb="$task_sdk/platform-tools/adb"
task_device="${ANDROID_SERIAL:-emulator-5554}"
case "$task_device" in emulator-*) ;; *) echo 'Navidrome integration tests require an emulator.' >&2; exit 1;; esac
curl --fail --silent http://127.0.0.1:4537/ping >/dev/null
JAVA_HOME="$task_java" ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
"$task_adb" -s "$task_device" install -r app/build/outputs/apk/debug/app-debug.apk
"$task_adb" -s "$task_device" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p build/evidence
"$task_adb" -s "$task_device" shell am instrument -w -e class io.prendie.subweazl.NavidromeIntegrationTest io.prendie.subweazl.test/androidx.test.runner.AndroidJUnitRunner > build/evidence/navidrome-integration.txt
cat build/evidence/navidrome-integration.txt
rg -q 'OK \(1 test\)' build/evidence/navidrome-integration.txt
