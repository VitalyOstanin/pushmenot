#!/usr/bin/env bash
set -euo pipefail

emulator -avd pushmenot -no-window -noaudio -no-snapshot -gpu swiftshader_indirect > /tmp/emulator.log 2>&1 &
emulator_pid=$!
trap 'kill "$emulator_pid" 2>/dev/null || true' EXIT
export ANDROID_SERIAL=emulator-5554
deadline=$((SECONDS + 240))
echo "Waiting for Android 14 emulator boot"
while [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != 1 ]]; do
    if ! kill -0 "$emulator_pid" 2>/dev/null || (( SECONDS >= deadline )); then
        cat /tmp/emulator.log >&2
        exit 1
    fi
    sleep 2
done

echo "Android emulator booted after ${SECONDS}s; installing test APKs"
if [[ -f upgrade-baseline.apk ]]; then
    package=io.github.vitalyostanin.pushmenot
    echo "Installing the baseline APK to exercise an in-place upgrade"
    adb install upgrade-baseline.apk
    adb shell am start -W -n "$package/.MainActivity"
    db_deadline=$((SECONDS + 30))
    until adb shell "run-as $package test -s databases/pushmenot.db"; do
        if (( SECONDS >= db_deadline )); then
            echo "Baseline app did not create its database" >&2
            exit 1
        fi
        sleep 1
    done
    adb shell am force-stop "$package"
fi
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb install fixture/build/outputs/apk/debug/fixture-debug.apk
report=app/build/reports/androidTests/emulator.txt
mkdir -p "$(dirname "$report")"
echo "Running instrumentation tests"
timeout 15m adb shell am instrument -w \
    io.github.vitalyostanin.pushmenot.test/androidx.test.runner.AndroidJUnitRunner | tee "$report"
grep -Eq '^OK \([1-9][0-9]* tests?\)' "$report"
