#!/usr/bin/env bash
set -euo pipefail

emulator -avd pushmenot -no-window -noaudio -no-snapshot -gpu swiftshader_indirect > /tmp/emulator.log 2>&1 &
emulator_pid=$!
trap 'kill "$emulator_pid" 2>/dev/null || true' EXIT
export ANDROID_SERIAL=emulator-5554
deadline=$((SECONDS + 240))
echo "Waiting for Android API ${1:-34} emulator boot"
while [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != 1 ]]; do
    if ! kill -0 "$emulator_pid" 2>/dev/null || (( SECONDS >= deadline )); then
        cat /tmp/emulator.log >&2
        exit 1
    fi
    sleep 2
done

actual_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$actual_api" != "${1:-34}" ]]; then
    echo "Unexpected Android API: $actual_api (expected ${1:-34})" >&2
    exit 1
fi
# UI tests navigate through system activities and the notification shade.
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put system screen_off_timeout 1800000
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
# A system UI startup ANR makes the emulator unusable for UI tests.
system_ui_anr="$(adb logcat -d -b events -s am_anr '*:S' | grep -F 'com.android.systemui' || true)"
if [[ -n "$system_ui_anr" ]]; then
    printf 'Android API %s System UI startup ANR:\n%s\n' "$actual_api" "$system_ui_anr" >&2
    exit 76
fi
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
# The container has no network. Disable Android's virtual radios so update checks fail
# immediately rather than waiting for the emulator's unreachable DNS resolver.
adb shell cmd connectivity airplane-mode enable
runner_args=()
if [[ -n "${2:-}" ]]; then
    runner_args=(-e class "$2")
fi
echo "Running instrumentation tests${2:+: $2}"
timeout 15m adb shell am instrument -w "${runner_args[@]}" \
    io.github.vitalyostanin.pushmenot.test/androidx.test.runner.AndroidJUnitRunner | tee "$report" "app/build/reports/androidTests/emulator-api-$actual_api.txt"
if ! grep -Eq '^OK \([1-9][0-9]* tests?\)' "$report"; then
    adb shell dumpsys power | grep -E 'mWakefulness=|Display Power: state=' || true
    adb logcat -d -b crash -t 80 || true
    adb logcat -d -b events -s am_anr '*:S' | tail -n 20 || true
    for screen in settings share navigation; do
        screenshot="/data/local/tmp/pushmenot-e2e-$screen.png"
        if adb shell test -s "$screenshot"; then
            adb exec-out cat "$screenshot" > "app/build/reports/androidTests/$screen-api-$actual_api.png"
        fi
    done
    exit 1
fi
