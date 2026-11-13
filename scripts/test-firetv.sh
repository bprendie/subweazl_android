#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
task_java="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
task_sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
task_adb="$task_sdk/platform-tools/adb"
case "${ANDROID_SERIAL:-}" in
  emulator-*) ;;
  *) echo "Set ANDROID_SERIAL to an isolated Android emulator; physical devices are refused." >&2; exit 1 ;;
esac
curl --fail --silent http://127.0.0.1:4003/api/v1/info >/dev/null
task_size="$("$task_adb" shell wm size | sed -n 's/Override size: //p' | tr -d '\r')"
task_density="$("$task_adb" shell wm density | sed -n 's/Override density: //p' | tr -d '\r')"
restore_display() {
  "$task_adb" shell wm size "${task_size:-reset}"
  "$task_adb" shell wm density "${task_density:-reset}"
}
trap restore_display EXIT
"$task_adb" shell wm size 1920x1080
"$task_adb" shell wm density 213
JAVA_HOME="$task_java" ./gradlew -PfireTv=true :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
"$task_adb" install -r app/build/outputs/apk/debug/app-debug.apk
"$task_adb" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p build/evidence/tv
"$task_adb" shell am instrument -w -e class io.prendie.subweazl.TvCredentialFormTest,io.prendie.subweazl.TvNavigationTest \
  io.prendie.subweazl.tv.test/androidx.test.runner.AndroidJUnitRunner > build/evidence/tv/navigation.txt
cat build/evidence/tv/navigation.txt
rg -q 'OK \(2 tests\)' build/evidence/tv/navigation.txt
