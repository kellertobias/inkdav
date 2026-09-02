# InkDAV

InkDAV is an offline-first CalDAV, VTODO, and WebDAV client designed for large Android e-ink tablets, with the BOOX Note Air 5 C as its primary target.

The UI uses opaque paper-colored surfaces, strong outlines, redundant text status, and page-style navigation. It intentionally avoids shadows, gradients, animated transitions, continuously moving indicators, and color-only state.

## Current vertical slice

- Multiple DAV and NASDrive accounts with Android Keystore-encrypted credentials.
- CalDAV collection discovery, bounded event download, multiple calendars, offline create/edit/delete, and materialized recurring occurrences with exclusions, additions, moved exceptions, cancellations, and DST-aware timezone handling.
- VTODO collection discovery, complete task download including undated tasks, multiple lists, offline create/edit/delete/completion, list and Apple Reminders-style schedule views.
- Year, month, week, and agenda-style day calendar views.
- Room as the only UI-facing source of truth, with a durable mutation outbox.
- Connected WorkManager synchronization with exponential retry.
- `If-None-Match`/`If-Match` writes and explicit conflict state on 409/412 responses.
- WebDAV file indexing, nested folder navigation, pinned recursive folders, and streaming downloads.
- Two-way, three-way-baselined synchronization between a DAV folder and a user-selected Android Storage Access Framework folder, with safe first merge, conditional writes, conflict copies, atomic local downloads, and independent 10,000-item local/remote scan bounds.
- Android `DocumentsProvider` integration so other apps can browse indexed files and open cached or streamed content through the system file picker.
- Resizable task and calendar home-screen widgets. Task widgets can show one selected list or an upcoming window of 1–30 days while excluding selected lists; calendar widgets show the next occurrences. Row counts adapt to launcher-selected widget height.
- Adjustable past/future calendar cache window and e-ink settings.

## Build

Requirements: JDK 17 and Android SDK platform 36.

```sh
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk ./gradlew testDebugUnitTest assembleDebug lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

To install on a USB- or network-ADB-connected BOOX tablet:

```sh
ANDROID_HOME=/path/to/android-sdk "$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
```

## NASDrive

Use an HTTPS NASDrive URL ending in `/webdav/`, the profile device access key as username, and its one-time secret as password. Do not use the interactive OIDC password.

NASDrive's inspected WebDAV implementation supports the v1 operations InkDAV uses (`PROPFIND` depth 0/1, `GET`, `PUT`, `MKCOL`, `DELETE`, `COPY`, and `MOVE`) and rechecks root permissions on each operation. It does not provide a change journal or `sync-collection`, so InkDAV performs bounded depth-1 walks only during a user/app sync. It never polls file trees continuously, which also avoids waking idle NAS disks merely to look for changes.

At the time this project was created, NASDrive's WebDAV implementation existed as uncommitted work in the NASDrive checkout. It must be committed, tested, and deployed before live InkDAV file synchronization can be accepted. Arbitrary public-link shares are intentionally outside InkDAV's scope.

## Offline and conflict rules

The local database is authoritative for the UI. Local event/task changes commit first, then enter an ordered outbox. A reconnect drains that outbox before pulling remote changes. Conditional failures become visible conflicts; InkDAV never silently applies last-write-wins.

A time-range calendar query is not a complete server snapshot. InkDAV therefore never treats an absent event in that result as a deletion. Server-side tombstones/change tokens can enable safe deletion reconciliation in a future migration.

## Honest remaining product work

This is a compiled, test-backed first vertical slice, not yet the final production release. The next implementation milestones are:

1. Notification alarms, search, and per-calendar display settings.
2. Full recurrence support beyond the common RFC 5545 cases currently covered, notably `RANGE=THISANDFUTURE`, plus occurrence-only editing (the current editor clearly edits the complete series).
3. Mirror rename detection (`MOVE`) and very-large-collection incremental change journals where a server provides them. Today a bounded tree comparison treats renames as create/delete.
4. Integration fixtures for Nextcloud and the deployed NASDrive endpoint, followed by a release APK test on the physical Note Air 5 C in BOOX HD, Regal, and Speed modes.

Android scoped storage does not allow a Dropbox-style raw filesystem mount visible to every legacy app. InkDAV's transparent interface is the system file picker (`DocumentsProvider`); a selected physical mirror folder is the compatibility path for apps that only browse shared storage.
