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
export INKVAULT_KEYSTORE_FILE="$INKDAV_KEYSTORE_FILE"
export INKVAULT_KEYSTORE_PASSWORD="$INKDAV_KEYSTORE_PASSWORD"
export INKVAULT_KEY_ALIAS="$INKDAV_KEY_ALIAS"
export INKVAULT_KEY_PASSWORD="$INKDAV_KEY_PASSWORD"
./gradlew --no-daemon ktlintCheck \
  testCalendarDebugUnitTest testTodosDebugUnitTest testFilesDebugUnitTest :inkvault:testDebugUnitTest \
  :inkvault:verifyBooxDependencies \
  lintCalendarRelease lintTodosRelease lintFilesRelease :inkvault:lintRelease \
  :app:assembleCalendarRelease :app:assembleTodosRelease :app:assembleFilesRelease :inkvault:assembleRelease
mkdir -p dist
for application in calendar todos files; do
  case "$application" in
    calendar) product="InkDAV-Calendar"; package_id="de.tobisk.inkdav" ;;
    todos) product="InkDAV-Todos"; package_id="de.tobisk.inkdav.todos" ;;
    files) product="InkDAV-Files"; package_id="de.tobisk.inkdav.files" ;;
  esac
  artifact="${product}-v${version}-boox-note-air5c.apk"
  cp "app/build/outputs/apk/$application/release/app-$application-release.apk" "dist/$artifact"
  ./scripts/verify-boox-apk.sh "dist/$artifact" "$version" signed "$package_id"
  (cd dist && sha256sum "$artifact" > "$artifact.sha256")
done

artifact="InkVault-v${version}-boox-note-air5c.apk"
cp inkvault/build/outputs/apk/release/inkvault-release.apk "dist/$artifact"
./scripts/verify-boox-apk.sh "dist/$artifact" "$version" signed de.tobisk.inkvault inkvault
(cd dist && sha256sum "$artifact" > "$artifact.sha256")
