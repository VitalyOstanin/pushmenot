#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
test -c /dev/kvm
tools/gradle.sh :core:test :app:assembleDebug :app:assembleDebugAndroidTest :fixture:assembleDebug
sdk_digest="$(sha256sum tools/Containerfile.sdk | cut -c1-16)"
sdk_image="localhost/pushmenot-sdk:$sdk_digest"
emulator_digest="$(sha256sum tools/Containerfile.emulator | cut -c1-16)"
image="localhost/pushmenot-emulator:$sdk_digest-$emulator_digest"
if ! podman image exists "$image"; then
    podman build --network host --build-arg "SDK_IMAGE=$sdk_image" \
        --tag "$image" --file tools/Containerfile.emulator tools
fi
timeout 22m podman run --rm --network none --device /dev/kvm --group-add keep-groups \
    --volume "$PWD:/src:z" "$image" bash tools/emulator-test.sh
sha256sum app/build/outputs/apk/debug/app-debug.apk
