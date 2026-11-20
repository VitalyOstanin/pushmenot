#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image_digest="$(sha256sum "$root/tools/Containerfile.sdk" | cut -c1-16)"
image="localhost/pushmenot-sdk:$image_digest"
if ! podman image exists "$image"; then
    podman build --network host --tag "$image" --file "$root/tools/Containerfile.sdk" "$root/tools"
fi

exec timeout 60m podman run --rm --network host \
    --volume "$root:/src:z" \
    --volume pushmenot-gradle:/gradle \
    --volume pushmenot-debug-signing:/root/.android \
    "$image" ./gradlew --no-daemon "$@"
