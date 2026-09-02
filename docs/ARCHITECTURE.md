# Architecture

`MainActivity` hosts a Compose UI whose screens observe Room `Flow`s through `MainViewModel`. Screens never read DAV directly.

```text
Compose views -> MainViewModel -> Room (canonical local state)
                                ^
                                |
                       SyncEngine / WorkManager
                                |
             CalDAV + VTODO + WebDAV over HTTPS
```

The core persistence records accounts, logical collections, calendar components, materialized calendar occurrences, tasks, file metadata/manifests, mirror baselines, widget configurations, and pending mutations. Raw iCalendar is retained alongside parsed projections so unsupported properties, alarms, and sibling components are not destroyed by an edit.

Account passwords/device secrets are encrypted with an AES-GCM key generated in Android Keystore. Application data and preferences are excluded from cloud backup and device transfer.

Calendar and task mutations are lazy offline writes: Room and the outbox are updated in one transaction. The sync worker drains writes using stable UIDs, hrefs, and ETags before it pulls. A bounded query only upserts; it cannot authorize deletions. Recurrence expansion derives a deterministic, bounded occurrence projection from masters, RDATE/EXDATE, and detached exceptions while retaining the original VCALENDAR resource.

File roots use `Depth: 1` indexing. Pinned directories and physical mirrors are walked recursively during a bounded sync, with independent hard item caps for each side. Physical mirrors use the persisted permission for a user-selected Storage Access Framework tree. A non-destructive first merge establishes a three-way baseline; later runs compare remote ETags and local SHA-256 values, use conditional streaming uploads, write local downloads to a temporary document before rename, propagate one-sided deletes, and create both versions on concurrent edits.

The exported DocumentsProvider exposes one root per DAV file collection. Online files stream through DAV; pinned files open from local storage. Provider search uses only the bounded local index and never causes a remote NAS scan.

Home-screen widgets are standard resizable Android `AppWidgetProvider` surfaces with opaque high-contrast `RemoteViews`. Per-widget task filters are stored in Room; widget row counts use the launcher's reported dimensions and refresh after sync or a local mutation.
