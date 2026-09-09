#!/bin/sh
set -eu

verify=./scripts/verify-boox-apk.sh

"$verify" app/build/outputs/apk/calendar/release/app-calendar-release-unsigned.apk "" unsigned de.tobisk.inkdav
"$verify" app/build/outputs/apk/todos/release/app-todos-release-unsigned.apk "" unsigned de.tobisk.inkdav.todos
"$verify" app/build/outputs/apk/files/release/app-files-release-unsigned.apk "" unsigned de.tobisk.inkdav.files
"$verify" app/build/outputs/apk/calendar/debug/app-calendar-debug.apk "" signed de.tobisk.inkdav
"$verify" app/build/outputs/apk/todos/debug/app-todos-debug.apk "" signed de.tobisk.inkdav.todos
"$verify" app/build/outputs/apk/files/debug/app-files-debug.apk "" signed de.tobisk.inkdav.files
"$verify" inkvault/build/outputs/apk/debug/inkvault-debug.apk "" signed de.tobisk.inkvault inkvault
"$verify" inkvault/build/outputs/apk/release/inkvault-release-unsigned.apk "" unsigned de.tobisk.inkvault inkvault

mkdir -p dist/ci
cp app/build/outputs/apk/calendar/debug/app-calendar-debug.apk dist/ci/InkDAV-Calendar-main-boox-note-air5c-debug.apk
cp app/build/outputs/apk/todos/debug/app-todos-debug.apk dist/ci/InkDAV-Todos-main-boox-note-air5c-debug.apk
cp app/build/outputs/apk/files/debug/app-files-debug.apk dist/ci/InkDAV-Files-main-boox-note-air5c-debug.apk
cp inkvault/build/outputs/apk/debug/inkvault-debug.apk dist/ci/InkVault-main-boox-note-air5c-debug.apk
