# Architecture and code tour

## System in one minute

InkDAV is one Android process with a Room-backed offline model, a WorkManager synchronizer, DAV transport, a Storage Access Framework mirror, a read-only `DocumentsProvider`, and two launcher widgets. Compose never reads a remote account directly: screens and widgets render persisted state, while edits commit locally and enter a durable outbox.

```text
app/src/main/java/de/tobisk/inkdav/
  ui/          e-ink Compose screens and editors
  data/        Room entities, DAO, migrations, offline commands
  dav/         CalDAV/WebDAV transport, iCalendar codecs and recurrence
  sync/        ordered outbox, token-based pulls and WorkManager jobs
  files/       physical-folder mirror and system document provider
  tasks/       schedule buckets and recurring VTODO projection
  widgets/     resizable task and calendar widgets
  security/    Android Keystore credential envelope
```

## Composition and dependency direction

`InkDavApplication` builds the single `AppContainer`. `MainViewModel` exposes Room/DataStore flows and invokes offline commands. `OfflineRepository` is the write boundary for calendar and task edits. `SyncEngine` is the only coordinator allowed to drain mutations and merge server changes. `OkHttpDavClient` owns HTTP and WebDAV XML; it does not mutate local state. `MirrorSyncEngine` owns the separate three-way file baseline.

The intended dependency direction is UI/widgets → view model/repositories → DAO and protocol/domain helpers. DAV transport must not call UI code, and UI code must not bypass the offline repository with network writes.

## Representative flow: edit an event without a network

1. `EditEventDialog` calls `MainViewModel.updateEvent`.
2. `OfflineRepository.updateEvent` patches the original VCALENDAR so unknown properties survive, marks the Room entity pending, replaces its bounded occurrence projection, and atomically replaces the object's outbox mutation.
3. The calendar recomposes immediately from `observeOccurrences`; no network response is required.
4. `SyncWorker` runs immediately when requested and periodically when connected. Periodic work syncs calendars/tasks without crawling file roots; a user/app sync includes files and mirrors. `SyncEngine` sends the queued payload with the last ETag.
5. A successful PUT stores the returned ETag and marks the object clean. HTTP 409/412 stops automatic retries and exposes a conflict in Sync. “Use server” discards the local mutation and rebuilds the bounded cache; “Keep both” creates a new UID for the local copy before rebuilding the server object.
6. The next RFC 6578 report advances the stored sync token only after changed resources and guarded tombstones are applied.

## Calendar, task, and file contracts

Calendar queries are intentionally bounded by the configured past/future window. Initial bounded queries establish a token baseline but never infer deletion from absence. Later RFC 6578 tombstones can remove only clean local objects. Recurrence masters and raw VCALENDAR payloads are retained; the display projection handles RRULE, RDATE, EXDATE, detached changes/cancellations, DST, and `THISANDFUTURE`.

Recurring VTODOs project to the next incomplete reminder. Completing one adds a detached completed occurrence to the same VCALENDAR and leaves the series active. List mode keeps the source task visible; schedule mode and widgets use the next-incomplete projection.

WebDAV indexes are separate from physical mirrors. A mirror performs bounded remote and local scans, compares both sides to the last complete baseline, uses conditional writes, detects unambiguous renames, and creates conflict copies when both contents changed. Android cannot provide a universal raw filesystem mount; the system document picker is the transparent read interface, while a selected SAF folder is the compatibility path for apps requiring physical files.

## Making changes

- Add persisted fields by changing `Entities.kt`, incrementing `InkDavDatabase`'s version, adding an explicit migration, exporting the schema, and extending `InkDavMigrationTest`.
- Add DAV behavior through `DavClient` and `OkHttpDavClient`; keep authentication headers and URL resolution there.
- Add offline calendar/task commands to `OfflineRepository`, including a mutation and immediate local projection.
- Add e-ink UI in `InkDavApp.kt`; retain opaque surfaces, high-contrast outlines, textual status, stable layout, and no animation-only feedback.
- Add mirror decisions to the pure `MirrorReconciler` first and cover the decision matrix before adding I/O to `MirrorSyncEngine`.

## External assumptions and remaining acceptance

CalDAV interoperability must be accepted against the user's actual server, including token expiry and server-specific recurrence payloads. NASDrive must expose HTTPS `/webdav/` device credentials and the DAV methods documented in the README. Release signing needs repository secrets. Final visual/refresh acceptance requires the physical BOOX Note Air 5 C in HD, Regal, and Speed modes; an emulator cannot validate ghosting or vendor launcher widget behavior.
