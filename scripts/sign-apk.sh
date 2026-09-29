#!/usr/bin/env bash
set -euo pipefail
# Passwords are read by apksigner from environment variables, never command arguments.
: "${SIGNING_KEYSTORE_PATH:?Missing signing keystore path}"
: "${SIGNING_KEYSTORE_PASSWORD:?Missing signing keystore password}"
: "${SIGNING_KEY_ALIAS:?Missing signing key alias}"
: "${SIGNING_KEY_PASSWORD:?Missing signing key password}"
: "${ANDROID_HOME:?Missing Android SDK path}"
input=${1:?Usage: sign-apk.sh unsigned.apk signed.apk}
output=${2:?Usage: sign-apk.sh unsigned.apk signed.apk}
build_tools="$ANDROID_HOME/build-tools/36.0.0"
"$build_tools/zipalign" -c -P 16 4 "$input"
"$build_tools/apksigner" sign --v4-signing-enabled false --ks "$SIGNING_KEYSTORE_PATH" \
  --ks-key-alias "$SIGNING_KEY_ALIAS" --ks-pass env:SIGNING_KEYSTORE_PASSWORD \
  --key-pass env:SIGNING_KEY_PASSWORD --out "$output" "$input"
"$build_tools/apksigner" verify --verbose --print-certs "$output"
(cd "$(dirname "$output")" && sha256sum "$(basename "$output")" > "$(basename "$output").sha256")
