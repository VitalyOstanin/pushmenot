#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 ]]; then
    printf 'Usage: %s INPUT.apk OUTPUT.apk\n' "$0" >&2
    exit 2
fi
: "${SIGNING_KEYSTORE_B64:?missing signing keystore}"
: "${SIGNING_PASSWORD:?missing signing password}"
: "${SIGNING_CERT_SHA256:?missing expected signing certificate fingerprint}"

tool_dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?missing Android SDK}}/build-tools/36.0.0
input=$1
output=$2
umask 077
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
printf '%s' "$SIGNING_KEYSTORE_B64" | base64 --decode > "$work/release.p12"
"$tool_dir/zipalign" -P 16 -f 4 "$input" "$work/aligned.apk"
"$tool_dir/apksigner" sign --ks "$work/release.p12" --ks-type PKCS12 \
    --ks-key-alias release --ks-pass env:SIGNING_PASSWORD --v4-signing-enabled false \
    --out "$work/signed.apk" "$work/aligned.apk"
report=$("$tool_dir/apksigner" verify --print-certs "$work/signed.apk")
actual=$(awk -F ': ' '/certificate SHA-256 digest:/ {print tolower($NF)}' <<< "$report" | sort -u)
[[ "${actual,,}" == "${SIGNING_CERT_SHA256,,}" ]] || {
    printf 'APK signing certificate does not match the expected fingerprint.\n' >&2
    exit 1
}
install -m 0644 "$work/signed.apk" "$output"
