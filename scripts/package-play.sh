#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
task_java="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
task_key_dir="${SUBWEAZL_SIGNING_DIR:-$HOME/.config/subweazl-android}"
test -f "$task_key_dir/play-upload.jks"
test -f "$task_key_dir/play-upload.password"
JAVA_HOME="$task_java" ./gradlew :app:bundleRelease
mkdir -p build/outputs
"$task_java/bin/jarsigner" -keystore "$task_key_dir/play-upload.jks" \
  -storepass:file "$task_key_dir/play-upload.password" \
  -keypass:file "$task_key_dir/play-upload.password" \
  -signedjar build/outputs/subweazl-play.aab \
  app/build/outputs/bundle/release/app-release.aab subweazl-upload
"$task_java/bin/jarsigner" -verify build/outputs/subweazl-play.aab
