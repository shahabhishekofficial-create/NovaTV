#!/usr/bin/env bash
# Runs inside the Android emulator action's host shell. CI-only media is never packaged.
set -euo pipefail

mkdir -p evidence
python3 -m http.server 8766 --directory /tmp/novatv-media > /tmp/novatv-media-server.log 2>&1 &
server=$!
cleanup() {
    kill "$server" 2>/dev/null || true
    adb logcat -d -t 2500 > evidence/logcat.txt || true
    adb exec-out screencap -p > evidence/final-screen.png || true
    adb pull /sdcard/Android/data/tv.nova.player.debug/files/test-evidence/ evidence/ || true
    cp /tmp/novatv-media-server.log evidence/ || true
    find evidence -maxdepth 2 -type f -print | sort
    grep 'NovaTvTest' evidence/logcat.txt || true
}
trap cleanup EXIT

adb wait-for-device
adb shell input keyevent 82
curl --fail --retry 20 --retry-delay 1 http://127.0.0.1:8766/video.m3u8 > /dev/null

status=0
for attempt in 1 2; do
    set +e
    ./gradlew connectedDebugAndroidTest --stacktrace 2>&1 | tee /tmp/novatv-connected-tests.log
    status=${PIPESTATUS[0]}
    set -e
    if [[ $status -eq 0 || $attempt -eq 2 ]] || ! grep -Eq 'Received status code (429|5[0-9][0-9])|repo.maven.apache.org.*(timed out|Timeout)' /tmp/novatv-connected-tests.log; then
        break
    fi
    echo 'Transient repository download failure; retrying the Gradle task once.'
    sleep 15
done
cp /tmp/novatv-connected-tests.log evidence/ || true
if [[ $status -ne 0 ]]; then
    exit "$status"
fi

adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -W -n tv.nova.player/.ui.HomeActivity
sleep 5
adb shell pidof tv.nova.player | grep -E '[0-9]+'
adb shell dumpsys activity top | grep 'tv.nova.player'
adb exec-out screencap -p > "evidence/release-home-${TV_PROFILE}.png"
