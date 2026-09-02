# InkDAV

InkDAV is an offline-first CalDAV, VTODO, and WebDAV client designed for large Android e-ink tablets, with the BOOX Note Air 5 C as its primary target.

The UI uses opaque paper-colored surfaces, strong outlines, redundant text status, and page-style navigation. It intentionally avoids shadows, gradients, animated transitions, continuously moving indicators, and color-only state.

## Current vertical slice

- Multiple DAV and NASDrive accounts with Android Keystore-encrypted credentials.
- CalDAV collection discovery, bounded initial event download, RFC 6578 incremental sync and guarded deletion tombstones across multiple calendars.
- Offline event create/edit/delete with materialized RRULE/RDATE/EXDATE projections, detached changes/cancellations, `THISANDFUTURE`, and DST-safe date arithmetic.
- VTODO collection discovery, complete task download including undated and recurring tasks, multiple lists, offline create/edit/delete/completion, and Apple Reminders-style list/schedule views.
- Year, month, week, and agenda-style day calendar views.
- Room as the only UI-facing source of truth, with a durable mutation outbox.
- Immediate full synchronization plus hourly connected calendar/task synchronization with exponential retry; periodic work does not crawl file roots or wake the NAS.
- `If-None-Match`/`If-Match` writes, stopped conflict retries, and explicit “Use server” / “Keep both” resolution.
- WebDAV file indexing, nested folder navigation, pinned recursive folders, and streaming downloads.
- A separate local file browser rooted in either a user-selected Android folder or, after explicit all-files authorization, the shared device-storage root, with folder navigation, file-type icons, in-app Markdown/image/audio/first-page PDF previews, and external-app opening for every file.
- Two-way, three-way-baselined synchronization between a DAV folder and a user-selected Android Storage Access Framework folder, with safe first merge, rename propagation, pause/remove management, duplicate-binding prevention, conditional writes, conflict copies, atomic local downloads, and independent 10,000-item local/remote scan bounds.
- Android `DocumentsProvider` integration so other apps can browse indexed files and open cached or streamed content through the system file picker.
- Resizable task and calendar home-screen widgets. Task widgets can show one selected list or an upcoming window of 1–30 days while excluding selected lists; calendar widgets show the next occurrences. Row counts adapt to launcher-selected widget height.
- Adjustable past/future calendar cache window and e-ink settings.
- A Settings updater that checks official GitHub releases, verifies the APK checksum and signing identity, and opens Android's installer.

## Build

Requirements: JDK 17 and Android SDK platform 36.

```sh
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk ./gradlew testDebugUnitTest assembleDebug lintDebug
```

Canonical development checks:

```sh
./gradlew ktlintFormat
./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug
```

GitHub Actions runs formatting, JVM tests, debug/release lint, debug and minified release builds, Room migration and DAV contract tests on an Android 15 emulator, CodeQL, and an explicit BOOX compatibility check. Every successful main-branch run retains an installable debug APK for the Note Air5 C. A successful push run then gates the signed semantic-release transaction; see [docs/RELEASING.md](docs/RELEASING.md).

The local debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Hosted releases provide `InkDAV-vVERSION-boox-note-air5c.apk`, a signed universal APK with ARM64 native libraries for the Android 15 Note Air5 C.

To install on a USB- or network-ADB-connected BOOX tablet:

```sh
ANDROID_HOME=/path/to/android-sdk "$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
```

## NASDrive

Use an HTTPS NASDrive URL ending in `/webdav/`, the profile device access key as username, and its one-time secret as password. Do not use the interactive OIDC password.

NASDrive's inspected WebDAV implementation supports the v1 operations InkDAV uses (`PROPFIND` depth 0/1, `GET`, `PUT`, `MKCOL`, `DELETE`, `COPY`, and `MOVE`) and rechecks root permissions on each operation. It does not provide a change journal or `sync-collection`, so InkDAV performs bounded depth-1 walks only during a user/app sync. It never polls file trees continuously, which also avoids waking idle NAS disks merely to look for changes.

Arbitrary public-link shares are intentionally outside InkDAV's scope. The endpoint must be live-accepted with the user's NASDrive account before file synchronization is considered operationally accepted.

## Offline and conflict rules

The local database is authoritative for the UI. Local event/task changes commit first, then enter an ordered outbox. A reconnect drains that outbox before pulling remote changes. Conditional failures become visible conflicts; InkDAV never silently applies last-write-wins.

A time-range calendar query is not a complete server snapshot, so InkDAV never treats an absent event in that result as a deletion. After that bounded baseline, RFC 6578 change reports provide changed hrefs and tombstones. A sync token advances only after the complete report is applied; token expiry falls back to a non-deleting bounded rebuild.

## Production and operations

The implementation is locally build-, lint-, shrinker-, and test-backed. These acceptance steps require external state and are not implied by source completion:

1. Install the Android signing secrets and enable the release workflow.
2. Run credentialed interoperability fixtures against the actual CalDAV/task server and deployed NASDrive endpoint.
3. Install the signed APK and accept calendar density, ghosting, physical-folder permissions, background execution, and widget resizing on the BOOX Note Air 5 C in HD, Regal, and Speed modes.

See [Security and privacy](SECURITY.md), [Architecture and code tour](docs/ARCHITECTURE.md), and [Releasing](docs/RELEASING.md).

Android scoped storage does not allow a Dropbox-style raw filesystem mount visible to every legacy app. InkDAV's transparent interface is the system file picker (`DocumentsProvider`); a selected physical mirror folder is the compatibility path for apps that only browse shared storage.

## License

InkDAV is available under the Apache License 2.0. See `LICENSE`.
