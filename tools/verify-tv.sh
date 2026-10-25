#!/usr/bin/env bash
# Runs inside the Android emulator action's host shell. CI-only media is never packaged.
set -euo pipefail

mkdir -p evidence
python3 -m http.server 8766 --directory /tmp/novatv-media > /tmp/novatv-media-server.log 2>&1 &
server=$!
trap 'kill "$server" 2>/dev/null || true; adb logcat -d -t 2500 > evidence/logcat.txt || true; adb exec-out screencap -p > evidence/final-screen.png || true; adb pull /sdcard/Android/data/tv.nova.player.debug/files/test-evidence/ evidence/ || true; cp /tmp/novatv-media-server.log evidence/ || true' EXIT

adb wait-for-device
adb shell input keyevent 82
curl --fail --retry 20 --retry-delay 1 http://127.0.0.1:8766/video.m3u8 > /dev/null

status=0
./gradlew connectedDebugAndroidTest --stacktrace || status=$?
if [[ $status -ne 0 ]]; then
    exit "$status"
fi

adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -W -n tv.nova.player/.ui.HomeActivity
sleep 5
adb shell pidof tv.nova.player | grep -E '[0-9]+'
adb shell dumpsys activity top | grep 'tv.nova.player'
adb exec-out screencap -p > "evidence/release-home-${TV_PROFILE}.png"
