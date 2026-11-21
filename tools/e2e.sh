#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
if [[ "${1:-34}" == all ]]; then
    apis=(31 32 33 34 35 36 37)
else
    IFS=, read -ra apis <<< "${1:-34}"
    for api in "${apis[@]}"; do
        if [[ ! "$api" =~ ^3[1-7]$ ]]; then
            printf 'Usage: %s [api[,api...]|all] [test-class[#method]]\n' "$0" >&2
            exit 2
        fi
    done
fi
test -c /dev/kvm
tools/gradle.sh :core:test :app:assembleDebug :app:assembleDebugAndroidTest :fixture:assembleDebug
sha256sum app/build/outputs/apk/debug/app-debug.apk
sdk_digest="$(sha256sum tools/Containerfile.sdk | cut -c1-16)"
sdk_image="localhost/pushmenot-sdk:$sdk_digest"
emulator_digest="$(sha256sum tools/Containerfile.emulator | cut -c1-16)"

run_emulator() {
    local android_api="$1" selection="$2"
    local image_api="$android_api"
    if [[ "$android_api" == 37 ]]; then
        image_api=37.0
    fi
    local image_tag=google_apis
    if (( android_api >= 35 )); then
        # The fixtures use Android system APIs and do not need Google Play services.
        image_tag=default
    fi
    local image="localhost/pushmenot-emulator:$sdk_digest-$emulator_digest-api$android_api"
    if ! podman image exists "$image"; then
        podman build --layers --network host --build-arg "SDK_IMAGE=$sdk_image" \
            --build-arg "ANDROID_API=$image_api" --build-arg "ANDROID_IMAGE_TAG=$image_tag" \
            --tag "$image" --file tools/Containerfile.emulator tools || return
    fi
    local attempt status
    for attempt in 1 2; do
        if timeout 22m podman run --rm --network none --device /dev/kvm --group-add keep-groups \
            --volume "$PWD:/src:z" "$image" bash tools/emulator-test.sh "$android_api" "$selection"; then
            return 0
        else
            status=$?
        fi
        if [[ "$status" != 76 || "$attempt" == 2 ]]; then
            return "$status"
        fi
        printf 'Restarting Android API %s after System UI failed to start\n' "$android_api" >&2
    done
}

result=0
for android_api in "${apis[@]}"; do
    if run_emulator "$android_api" "${2:-}"; then
        printf 'Android API %s: passed\n' "$android_api"
    else
        error=$?
        result=1
        printf 'Android API %s: failed (exit %s)\n' "$android_api" "$error" >&2
    fi
done
exit "$result"
