# InkVault project plan

## Purpose

Build **InkVault** as a second Android application in this repository: a native,
offline-first Obsidian vault reader, editor, and handwriting application for the
BOOX tablet.
It must use the repository's existing Obsidian Sync implementation directly,
rather than relying on the Obsidian application or a filesystem bridge.

The application is intentionally a focused native Obsidian client, not a full
desktop replacement. Its essential jobs are:

- keep an Obsidian vault available locally and synchronize it safely when a
  connection is available;
- browse the vault, edit and accurately preview Obsidian-flavoured Markdown;
- display PDFs and create A4, pen-first handwritten documents;
- annotate PDFs, while preserving editable handwriting data; and
- make handwritten documents useful to ordinary Obsidian clients by having the
  ObsidiSync server publish a PDF rendition while retaining the hidden editable
  source for InkVault.

The UI must be fast, legible, stable, and deliberately economical on a
4-GB colour e-ink BOOX device.

## Repository and application boundary

Add this as the `:inkvault` Android app/module and a separate launcher entry,
using package/application ID `de.tobisk.inkvault`. Give it settings, a database,
a cache, and a release artifact independent of InkDAV. Release APKs should use
the name `InkVault-vVERSION-boox-note-air5c.apk`. It may
reuse carefully extracted, tested transport, credential-storage, WorkManager,
and e-ink UI primitives, but must not couple its vault schema or sync lifecycle
to InkDAV's CalDAV/VTODO model.

The new app owns a local vault mirror. The local mirror is the UI source of
truth: browsing, reading, creating, editing, and drawing never wait for a
network response. A durable operation queue records local changes and drains
after connectivity returns. Sync must use the native ObsidiSync protocol
maintained at <https://github.com/kellertobias/obsidisync>, including its
authentication, remote revisions, conflicts, upload staging, and referenced-file
downloads.

InkVault is a first-class sync client for one configured vault. It checks
`/v1/server/info`, refuses incompatible API versions, registers a distinct
device/computer identity, and uses the `/v1/users/{user}/vaults/{vault}` register,
upload, sync, blob, history, and resolve APIs. It must request referenced file
content when the server advertises `syncFileReferences`, then download and verify
changed files individually so first sync does not hold the whole vault in RAM.
The existing password and OIDC device login/session-refresh flows are reused.
The WebDAV endpoint is not InkVault's sync transport because its writes are
last-write-wins and do not expose the full plugin conflict workflow.

ObsidiSync needs a versioned `inkVaultNotesV1` server feature. InkVault must not
enable handwritten-note sync until the server advertises it. The feature adds
hidden InkNote source storage, client-aware visibility, source-to-PDF rendering,
and pair-aware history/conflict behaviour while retaining the existing sync API
shape wherever possible.

## Primary workspace layout

The app is a three-pane, landscape-friendly workspace.

```text
+---------------------+-----------------------------------------------+
| Vault tree           | Document toolbar                              |
| folders and notes    +-----------------------------------------------+
|                      |                                               |
| Selected folder      | Current Markdown, PDF, or A4 handwritten page |
| files at the bottom  |                                               |
|                      |                                               |
|                      |                                               |
| Quick recording      |                         [previous] [next]     |
+---------------------+-----------------------------------------------+
```

- The left sidebar normally shows the expandable vault tree. Its lower section
  lists the immediate files of the selected tree item.
- For a PDF or handwritten document, the sidebar can switch between vault
  navigation and page miniatures for the current document.
- For a Markdown document, the sidebar can switch to an optional generated
  outline built from the document's headings. This table of contents is a view
  only: it is never inserted into or persisted in the Markdown file. Selecting
  an outline entry navigates to that heading.
- The sidebar is independently toggleable. Hiding it gives the document a
  distraction-free full-screen writing area.
- The right pane always shows the selected vault item: Markdown reader/editor,
  PDF viewer, audio player, image viewer, or handwritten document.
- Handwritten/PDF pages are always displayed as complete A4 pages; the app must
  never leave a half page as the intended resting view. Explicit previous/next
  controls move one page at a time. A page indicator makes the current position
  unambiguous.
- The application clearly distinguishes a writable canvas from read-only PDF,
  Markdown preview, navigation, and tool controls. Touch never accidentally
  draws into a non-canvas area.

## Vault files and synchronization contract

### Standard vault content

Synchronize ordinary folders and files from the one configured vault
bidirectionally, including Markdown, PDF, JPEG, PNG, SVG, and MP3. Preserve names,
paths, moves, deletes, modification metadata/revisions, and conflicts according
to the ObsidiSync protocol. The initial target is a vault with hundreds of files
and growth beyond that; indexing and navigation must remain incremental.

Typed notes use `.md` Obsidian Markdown. Editing must be offline-first and
preserve content the native editor does not interpret. Version 1 preview and edit
support covers core Markdown; wiki-style links and aliases; block quotes; embedded
vault images; Obsidian-style info, warning, and success callout blocks; tables;
and a generated heading outline. The outline is presented as an optional sidebar
view and is not stored in the Markdown source. Wiki links and embedded-image
paths resolve relative to the vault and update predictably when files are
renamed. Establish a compatibility test vault with one fixture per supported
construct. Unknown syntax remains editable as source and must never be silently
discarded or rewritten.

### InkNote v1 source format and portable rendition

A handwritten note is one logical document represented by two related vault
artifacts:

1. a hidden, syncable **InkNote v1** source package containing pages, vector ink,
   inserted assets, tool metadata, and the information required to continue
   editing; and
2. a visible PDF rendition generated by ObsidiSync so normal Obsidian clients can
   open the note.

InkNote v1 is a versioned directory package below
`.inkvault/notes/{document-id}/`, hidden as a whole from ordinary Obsidian sync
clients. Using independently versioned page files avoids rewriting a 500-page
binary archive whenever one stroke changes. The package layout is:

```text
.inkvault/notes/{document-id}/
  manifest.json
  pages/{page-id}.cbor
  assets/{sha256}.{extension}
```

`manifest.json` contains the schema version, stable document ID, visible PDF
path, title, created/modified times, ordered page IDs, each page's A4 orientation
and template reference, source revision, and deterministic render revision.
Each page is canonical CBOR containing page dimensions, ordered vector strokes,
stroke points (position, time, and optional pressure/tilt), tool style, placed
image objects, transforms, and tombstones required for safe synchronization.
Content-addressed assets hold inserted JPEG, PNG, SVG, or template material.
Undo history is a bounded device-local concern and is not part of the permanent
cross-device source format.

ObsidiSync treats the package and its visible PDF as one logical note. InkVault
receives the source package and visible PDF metadata; ordinary Obsidian clients
receive only the generated PDF. After a complete source revision arrives, the
server validates it, renders the PDF deterministically, updates the PDF binary,
and commits source metadata plus PDF metadata as one logical server transaction.
A failed render leaves the previously valid PDF in place and reports the source
revision as pending/failed instead of publishing a partial result.

Creating a note chooses a vault folder and name, allocates its stable ID, creates
an A4 source package locally, and queues the source. The visible PDF path is the
chosen path and filename with a `.pdf` extension. InkVault may render a local
preview, but only the server-generated rendition is authoritative for sync.
ObsidiSync must recover safely if source upload or rendering is interrupted.

### PDF annotation

Opening a PDF presents an **Annotate PDF** action. Starting annotation creates
or opens a hidden editable annotation source associated with that PDF. Saving
persists the editable annotation data locally and syncs it to ObsidiSync. The
server renders and overwrites the remote PDF at the same vault path, while the
hidden source remains available to InkVault for continued editing. The base PDF
hash/revision is recorded in the source, and the existing ObsidiSync binary
history remains the recovery path for the unannotated version.

An **Import PDF** action uses Android's system file picker to copy a PDF into the
selected vault folder, queue it for sync, and optionally enter annotation mode
immediately. Import never depends on the source file remaining available.

Existing PDF pages are fixed: annotation does not add, delete, reorder, or
duplicate their pages. Every imported or synchronized PDF retains its original
page dimensions and orientation. The viewer fits a complete page by default and
supports pinch zoom and two-finger panning without modifying those dimensions.
PDFs may contain up to 500 pages, so rendering, thumbnails, text extraction, and
annotation overlays must all be lazy and page-bounded.

## Handwriting editor

Every handwritten page has A4 dimensions and may independently use portrait or
landscape orientation. It is rendered at a resolution that keeps ink crisp at
common zoom levels without retaining full-resolution bitmaps for every page in
memory. Handwritten documents support adding, deleting, reordering, and
duplicating pages; destructive page deletion participates in undo.

The top editing toolbar includes:

- pen tools with configurable colour, width, and optional pressure sensitivity;
- marker tools with configurable colour and width; markers are never pressure
  sensitive;
- a tool preset manager for creating, naming, editing, ordering, and selecting
  personal pens and markers;
- eraser, undo, redo, lasso/select, insert image, page navigation, and save/sync
  state; and
- an explicit read/write mode when needed, with the active writing tool obvious
  even on low-contrast e-ink.

Canvas behaviour:

- stylus input alone writes; finger input does not create strokes;
- pinch zooms the complete A4 page; two fingers pan it;
- a lasso selects intersecting strokes/objects, then moves or scales the
  selection without rasterizing it;
- the eraser removes every stroke it touches (object/stroke erasing), with a
  predictable hit tolerance;
- undo and redo operate on document commands, including ink, lasso transforms,
  image insertion, page changes, and annotation actions;
- images selected from the vault may be placed on a page. JPEG, PNG, and SVG
  are supported, with references/copies managed so sync does not leave broken
  images;
- a page may use a background template selected from a configurable vault
  folder. Each page of a source PDF in that folder is available as a template;
  applying one records an immutable asset hash so later template-file changes do
  not unexpectedly alter existing notes.

Use a vector stroke model with sampled pressure/time/tilt only when hardware
provides it. Render incrementally by page and dirty region; cache bounded page
thumbnails and flattened ink tiles, not entire documents. PDF export should
retain vector ink where practical and flatten only where required for compatible
display.

## Audio capture and playback

At the bottom of the vault sidebar, provide a persistent **Quick recording**
button. It records offline into `/Recordings` at the vault root by default. The
folder is configurable and is created when first needed. Recordings are saved as
MP3 files, entered into the durable local file index, and queued for sync. The
app can open and play recordings. Trimming, cutting, and other audio editing are
outside the first release.

Request microphone permission only when recording is first invoked and make
recording state, elapsed time, stop, and failed-save state clear without relying
on animation or colour alone.

## Save, offline, and conflict behaviour

- Save an open document whenever it is closed, switched away from, or the app
  is backgrounded/stopped. Flush the local durable representation before queuing
  remote synchronization.
- Autosave open editable documents every five minutes by default. The interval
  is configurable, and an immediate-save action remains available.
- After every save, sync immediately when online; otherwise retain the operation
  until connectivity returns and sync immediately then. Also schedule a periodic
  sync every 15 minutes while Android permits it, and provide **Sync now**.
- Reuse ObsidiSync's first-sync choices, server-side merge, file history, and
  resolution semantics. Text conflicts received with conflict markers open in a
  dedicated resolver and are completed through the server's resolve API. Binary
  and InkNote pair conflicts remain explicit and recoverable; no client silently
  applies last-write-wins.
- Treat Markdown, PDFs, hidden note sources, and generated renditions as
  content with revision-aware conflict policies. A paired handwritten note must
  resolve as a pair, not by independently mixing source/PDF versions.
- Background work must respect Android scheduling and e-ink battery constraints;
  foreground sync can be requested by the user. The UI always reports locally
  saved, pending sync, syncing, synced, offline, and conflict states in text as
  well as icons.

## E-ink and BOOX acceptance requirements

The BOOX tablet is a first-class target, not an emulator-only compatibility
claim. Integrate the supported BOOX display-mode API when available and fall
back safely on other Android devices. Writing mode should select the display
mode appropriate for low-latency stylus strokes; reading/navigation should use a
quality mode appropriate for sharp text and PDFs. The setting must be reversible
and visible to the user.

- Use opaque paper-like backgrounds, high-contrast text, dark outlines, large
  hit targets, and labels/icons together.
- Avoid gradients, shadows, translucent overlays, spinner-only state, rapid
  animation, continuously updating UI, and unnecessary recomposition.
- Update only the active page/canvas dirty region and explicit status regions;
  do not re-render the vault tree or document chrome for every stylus sample.
- Bound image/PDF decode sizes, thumbnail caches, audio buffers, sync batches,
  and inactive-document memory. Profile vaults with hundreds and then thousands
  of files, plus a 500-page PDF, within the tablet's 4-GB RAM envelope.
- Test both stylus latency and ghosting on the physical BOOX in its relevant
  quality and speed modes. Colour choices must remain distinguishable with the
  display's limited contrast; important meaning cannot depend on colour.

## Delivery phases and acceptance

1. **Foundation:** create the `:inkvault` module/app, credentials/settings,
   local-vault database/filesystem model, native Obsidian Sync adapter, durable
   outbox, and a protocol contract test suite.
2. **Vault browser:** offline vault tree, selected-folder file list, file-type
   routing, sidebar toggling, sync status, and conflict UI.
3. **Readers and Markdown:** PDF/image/audio viewers and a native Markdown
   editor/preview verified against the compatibility fixture vault.
4. **A4 ink engine:** stylus-only canvas, page navigation/thumbnails, pen,
   marker, eraser, lasso transforms, undo/redo, custom presets, zoom/pan, and
   vault-image insertion.
5. **Portable handwritten notes and ObsidiSync extension:** implement InkNote v1,
   client-aware source visibility, deterministic server-side PDF rendering,
   pair revisions, create-note workflow, reconnect recovery, and ordinary
   Obsidian PDF interoperability.
6. **PDF annotation and audio:** editable annotation sources with remote-PDF
   replacement, recording/playback, configurable recording folder, and offline
   queues.
7. **Hardening and device acceptance:** autosave/background lifecycle, conflict
   matrices, memory/performance profiling, accessibility, real-server sync, and
   BOOX HD/Regal/Speed display acceptance.

Release is not complete merely because unit tests pass. It requires a physical
BOOX test with an offline edit/reconnect cycle, a handwritten note and annotated
PDF visible in a normal Obsidian client, recovery from an interrupted paired
upload, and confirmation that a realistic vault remains responsive within the
tablet memory budget.

## Remaining implementation decisions

The product decisions above are fixed. The implementation still needs short
technical spikes for:

- the minimum supported BOOX Note Air 5 C firmware and the precise stylus/raw
  input and display-refresh APIs available on that firmware;
- the Android Markdown editor/renderer components used to implement the stated
  syntax without a WebView-heavy interaction loop;
- canonical InkNote CBOR schemas, numeric precision, forward-compatible unknown
  fields, maximum stroke/page sizes, and server-side Rust rendering libraries;
- binary/InkNote conflict-copy naming within ObsidiSync while matching its
  existing history and resolution experience;
- MP3 encoder choice, bitrate, sample rate, and filename convention.

Vault encryption beyond Android's normal application sandbox is explicitly out
of scope. The target device is the BOOX Note Air 5 C. InkVault supports one vault.
