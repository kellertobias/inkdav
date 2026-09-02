#!/bin/sh
set -eu

version="${1:-}"
case "$version" in
  ''|*[!0-9.]*) echo "Expected semantic version" >&2; exit 2 ;;
esac

: "${INKDAV_KEYSTORE_FILE:?INKDAV_KEYSTORE_FILE is required for a published release}"
: "${INKDAV_KEYSTORE_PASSWORD:?INKDAV_KEYSTORE_PASSWORD is required for a published release}"
: "${INKDAV_KEY_ALIAS:?INKDAV_KEY_ALIAS is required for a published release}"
: "${INKDAV_KEY_PASSWORD:?INKDAV_KEY_PASSWORD is required for a published release}"

node scripts/set-version.mjs "$version"
./gradlew --no-daemon ktlintCheck testDebugUnitTest lintRelease assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "dist/InkDAV-v${version}.apk"
shasum -a 256 "dist/InkDAV-v${version}.apk" > "dist/InkDAV-v${version}.apk.sha256"
