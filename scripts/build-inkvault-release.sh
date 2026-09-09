#!/bin/sh
set -eu
version="${1:?Expected semantic version}"
code="${2:?Expected monotonically increasing Android version code}"
case "$version" in ''|*[!0-9.]*) echo 'Invalid version' >&2; exit 2 ;; esac
case "$code" in ''|*[!0-9]*) echo 'Invalid version code' >&2; exit 2 ;; esac
: "${INKVAULT_KEYSTORE_FILE:?Required}"
: "${INKVAULT_KEYSTORE_PASSWORD:?Required}"
: "${INKVAULT_KEY_ALIAS:?Required}"
: "${INKVAULT_KEY_PASSWORD:?Required}"
./gradlew --no-daemon :inkvault:ktlintCheck :inkvault:testDebugUnitTest :inkvault:lintRelease :inkvault:assembleRelease -PinkVaultVersion="$version" -PinkVaultVersionCode="$code"
mkdir -p dist
artifact="InkVault-v${version}-boox-note-air5c.apk"
cp inkvault/build/outputs/apk/release/inkvault-release.apk "dist/$artifact"
./scripts/verify-boox-apk.sh "dist/$artifact" "$version" signed de.tobisk.inkvault inkvault
(cd dist && shasum -a 256 "$artifact" > "$artifact.sha256")
