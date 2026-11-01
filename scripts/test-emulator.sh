#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
task_java="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
task_sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
task_adb="$task_sdk/platform-tools/adb"
# Requires the isolated sibling cmd/weazlfixture server on port 4003.
curl --fail --silent http://127.0.0.1:4003/api/v1/info >/dev/null
JAVA_HOME="$task_java" ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
"$task_adb" install -r app/build/outputs/apk/debug/app-debug.apk
"$task_adb" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p build/evidence
"$task_adb" shell am instrument -w -e class io.prendie.subweazl.IntegrationTest,io.prendie.subweazl.UiTest io.prendie.subweazl.test/androidx.test.runner.AndroidJUnitRunner > build/evidence/integration.txt
cat build/evidence/integration.txt
rg -q 'OK \(2 tests\)' build/evidence/integration.txt
"$task_adb" shell am instrument -w -e class 'io.prendie.subweazl.ColdStartTest#prepare' io.prendie.subweazl.test/androidx.test.runner.AndroidJUnitRunner > build/evidence/cold-prepare.txt
rg -q 'OK \(1 test\)' build/evidence/cold-prepare.txt
"$task_adb" shell am force-stop io.prendie.subweazl
"$task_adb" shell am instrument -w -e class 'io.prendie.subweazl.ColdStartTest#verify' io.prendie.subweazl.test/androidx.test.runner.AndroidJUnitRunner > build/evidence/cold-verify.txt
cat build/evidence/cold-verify.txt
rg -q 'OK \(1 test\)' build/evidence/cold-verify.txt
"$task_adb" shell am instrument -w -e class io.prendie.subweazl.VoiceIntegrationTest io.prendie.subweazl.test/androidx.test.runner.AndroidJUnitRunner > build/evidence/voice-integration.txt
cat build/evidence/voice-integration.txt
rg -q 'OK \(1 test\)' build/evidence/voice-integration.txt
"$task_adb" shell am instrument -w -e class io.prendie.subweazl.RadioGatewayIntegrationTest io.prendie.subweazl.test/androidx.test.runner.AndroidJUnitRunner > build/evidence/radio-gateway.txt
cat build/evidence/radio-gateway.txt
rg -q 'OK \(2 tests\)' build/evidence/radio-gateway.txt
"$task_adb" shell am start -n io.prendie.subweazl/.MainActivity
