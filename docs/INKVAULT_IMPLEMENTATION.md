# InkVault implementation and acceptance status

This is an **initial, incomplete implementation** of
[the product plan](OBSIDIAN_HANDWRITING_APP_PLAN.md), not a completed release.
InkDAV remains the separate `:app` application. InkVault is `:inkvault`, package
`de.tobisk.inkvault`, with its own launcher, SQLite database, immutable file cache,
Android Keystore alias, encrypted sessions and WorkManager queues.

## Implemented

- Black-and-white native workspace with outlined controls, a black status/header area,
  a hamburger toggle, and Vault/Pages/History/Settings sidebar modes. Expandable
  folders and file actions include recursive/hidden filters and transactional note-pair moves.
- Offline Markdown creation/editing and native Markwon preview with tables,
  strikethrough, wiki links/aliases, info/warning/success callouts and local images.
  Preview conversions never modify the Markdown source. Fixtures live in
  `inkvault/src/test/resources/compatibility-vault`.
- Streaming immutable blob storage with SHA-256 and length verification; SQLite
  transactions couple local content pointers to durable dirty generations and
  deletion tombstones. Rename is represented by a create/delete transaction.
- Native ObsidiSync API 1 compatibility negotiation, password login, OIDC device
  login with pending/slow-down handling, encrypted refresh-token rotation,
  distinct device identity, staged bounded uploads, referenced downloads, history
  requests and explicit conflict resolution. Sessions are scoped to server origin.
- Sync response journals survive process death. The server head advances only
  after all referenced blobs are verified. Newer local edits are retained with a
  separate received version instead of being overwritten. An editor also checks
  its opened content hash when saving after a background pull.
- Connected immediate work and 15-minute periodic work; debounced autosave after
  edits, background page encoding, and final saves on document switch/activity stop.
  There is no dedicated document Save button.
- Complete-page PDF viewer (up to 500 pages), bounded image decoding and audio
  playback. PDF file-picker imports copy bytes into owned storage immediately.
- A4 portrait/landscape ink pages; stylus-only writing; pressure pens,
  pressure-independent markers; named editable/reorderable presets; whole-stroke
  eraser with segment intersection; polygon lasso selection, vector move/scale,
  page-local undo/redo, image objects and immutable PDF template copies.
- Add/duplicate/reorder/delete handwritten pages; page deletion can be undone.
  PDF annotation uses a retained base PDF hash/revision and fixed pages.
- Experimental local InkNote manifest + canonical CBOR page files under
  `.inkvault/notes/{id}`. Unknown page/stroke/style fields are preserved. CBOR uses
  integer micrometres, integer milliseconds, and pressure/tilt thousandths, with
  an 8 MiB page limit and a nesting bound. Incompatible schemas fail closed.
- MP3 quick recording: Android AudioRecord into the Java LAME core, 44.1 kHz mono,
  96 kb/s; permission requested only when invoked. Recordings go to the configured
  folder (default `Recordings`) and enter the normal durable sync queue. Leaving
  the activity stops and finalizes a recording; background capture is not enabled.
- Official BOOX device SDK view-scoped System/Reading/Regal/Writing display-mode
  controls, reset when leaving the canvas/activity. Non-BOOX devices fall back.
- Negotiated `inkVaultNotesV1` source/PDF pairing with the companion ObsidiSync
  server: complete package uploads, authoritative vector PDF rendering, immutable
  base-PDF annotation, shared Git revisions, replay-safe publication and recovery.
  Ordinary clients receive only the PDF. Local journals preserve and resolve a
  whole pair; keeping local source uses an expected-server-head check.
- Independent release build script and CI artifact; existing InkDAV artifact and
  release naming remain unchanged.

## Unfinished plan requirements

1. The server implementation is in the isolated ObsidiSync worktree
   `/private/tmp/inkvault-obsidisync`, branch `codex/inkvault-notes-v1`, based on
   `43786bb`. A complete portable diff is saved as
   [obsidisync-inkvault-notes-v1.patch](obsidisync-inkvault-notes-v1.patch).
   It has not been integrated or deployed. Servers without the feature keep
   handwriting local. Renderer boundaries: SVG text must be paths; nested SVG
   images/foreignObject, encrypted PDF and non-default PDF UserUnit are rejected.
   Managed-PDF renames now publish the source and derived PDF atomically. Divergent Git remotes require operator reconciliation.
2. First-sync currently uses the ordinary merge request. The plugin's selectable
   force-push/download-and-backup workflows are not implemented. Normal sync does
   not call register: upstream register rewrites the server's Git remote settings.
   Settings provides an explicit Initialize server vault action for that purpose.
3. History is currently a server history inspector, not a version restore/name UI.
   Binary resolution supports choosing local or received data; the complete
   Keep-both/copy-name matrix is pending. The paired resolver supports choosing
   the entire local or received note; it never resolves individual page files.
4. Outline supports ATX headings; setext headings, block-reference navigation,
   heading navigation in preview and rename-aware link rewriting are pending.
   Unsupported source is retained. Page navigation has lazily attached ink/PDF miniatures; PDF text extraction is pending.
5. Undo is bounded per active page; the complete cross-page document-command
   history (including template changes and all page operations) is pending.
   Marker rendering and lasso edge cases need further work. The first incremental
   rendering build improved handwriting, but the user still found it too slow;
   direct BOOX pen integration is under device validation.
6. Full vault indexing/performance profiling at thousands of files, a bounded
   eviction/garbage-collection policy for historical blobs, accessibility review,
   interrupted MP3 recovery UI and the full conflict matrix remain unfinished.
7. Credentialed real-server reconnect, normal Obsidian PDF interoperability,
   and human BOOX latency/ghosting/colour acceptance remain outstanding.
   Interrupted-pair recovery has automated server coverage. Automated stylus events cannot establish physical pen latency.

## Verification performed on 2026-09-07

- Repository `ktlintCheck`, all 53 existing InkDAV JVM tests and all 12 new
  InkVault JVM tests passed.
- Debug lint for both apps, InkVault release lint, both debug APKs and InkVault's
  minified release APK passed. Lint still reports warnings (including UI string
  localization and allocations); no lint baseline was added to hide errors.
- Four instrumented tests passed on the connected **BOOX Note Air 5 C / Android
  15**: durable journal/reopen with concurrent edits, incomplete-blob rollback,
  stylus/finger/read-mode behavior, and page 500 of a generated 500-page PDF.
- Debug APK signature/alignment/package/version checks passed. The debug build
  was installed and enabled on the BOOX. BOOX had automatically disabled the new
  package (`lastDisabledCaller: com.onyx`); enabling only InkVault allowed Android
  to report a successful cold start (799 ms).
- Empty-workspace process memory snapshot: approximately 104 MiB total PSS,
  zero WebViews. This is not a realistic-vault memory acceptance result.
- The device's PIN lock prevented visual workspace inspection. No PIN was read,
  requested or entered. Human visual, pen-latency and ghosting acceptance remains
  outstanding.
- No server was deployed, no Git changes were committed/pushed, and no signed
  release was published.

## Server continuation verification (2026-09-07)

- Rust server: full existing suite plus seven InkVault integration tests passed
  (102 passed, one existing ignored test). Tests cover staged-upload interruption,
  lost-response replay, whole-pair conflicts, expected-head resolution, recovery
  before/after the Git ref switch, source visibility, WebDAV guards, base PDF
  preservation, atomic deletion, PNG/SVG/PDF templates, deterministic output,
  and 500 A4 pages with mixed orientations.
- `cargo clippy --all-targets -- -D warnings` passed. Modified Rust files pass
  rustfmt and the patch passes whitespace checks.
- Android: 12 JVM tests and seven BOOX instrumented tests passed; the three added
  device cases check whole-pair conflict preservation/adoption, prevent an
  ordinary-file sync from acknowledging unsent source, and cover offline PDF
  annotation plus independent-PDF mutation guards. Debug lint and the
  minified release build passed. Human writing and production sync were not tested.
- The full Rust suite uses local HTTP fixtures. Its first sandboxed attempt was
  blocked from binding a socket; rerunning with local socket access passed.
- The server patch includes `docs/INKVAULT_NOTES_V1.md`, covering wire format,
  transactional publication, recovery, limits, and integration instructions.

To review the separate server implementation, open the worktree above. To
recreate it in a clean ObsidiSync checkout at the recorded base:

```sh
git apply --check /Users/keller/repos/_tools/dav-client/docs/obsidisync-inkvault-notes-v1.patch
git apply /Users/keller/repos/_tools/dav-client/docs/obsidisync-inkvault-notes-v1.patch
cargo test --manifest-path rust-server/Cargo.toml
```

The original `/Users/keller/repos/_tools/obsync` working tree remains unchanged.
No commit, push, deployment, or release publication was performed.

## Storage and synchronization decisions

The UI reads only local indexed files. File contents are immutable and addressed
by SHA-256; filesystem writes complete and flush before SQLite changes pointers.
Unreferenced crash remnants are safe but currently retained. This avoids a
filesystem/database atomicity gap at the cost of pending garbage collection.

The dirty generation is the durable per-path outbox record. A response journal
contains both the received metadata and the exact request snapshot. Applying a
journal is transactional and compares generations. Conflicting received bytes
remain referenced in a separate conflict record. A conflict response does not
advance the merge base. No WebDAV transport is used.

Bounds: 512 MiB file intake, 32 MiB metadata response, 20,000 indexed sync entries,
2 MiB Markdown source, 8 MiB CBOR page, 500 document pages. Upload chunks are at
most 2 MiB. PDF and flattened-page bitmaps are at most 1600 × 2000 pixels. Prior
strokes are cached between stylus samples; inactive document pages are not kept
as bitmaps. These are engineering bounds, not a completed device memory profile.

## Build and verification

Use the same JDK 17 / Android SDK 36 environment as InkDAV:

```sh
./gradlew :inkvault:ktlintCheck :inkvault:testDebugUnitTest \
  :inkvault:lintDebug :inkvault:lintRelease \
  :inkvault:assembleDebug :inkvault:assembleRelease
./gradlew :inkvault:connectedDebugAndroidTest
```

Debug artifact: `inkvault/build/outputs/apk/debug/inkvault-debug.apk`.
Minified unsigned artifact: `inkvault/build/outputs/apk/release/inkvault-release-unsigned.apk`.

For a signed artifact, supply separate `INKVAULT_KEYSTORE_FILE`,
`INKVAULT_KEYSTORE_PASSWORD`, `INKVAULT_KEY_ALIAS`, `INKVAULT_KEY_PASSWORD`, then:

```sh
./scripts/build-inkvault-release.sh 0.1.0 1000
```

This produces `dist/InkVault-v0.1.0-boox-note-air5c.apk` plus its checksum. Signing
and packaging do not satisfy the release acceptance requirements above. Licensing
notices for the MP3 encoder and other dependencies are in APK assets under
`licenses/`; review distribution requirements before publishing a release.

The implementation used the existing ObsidiSync API contract from
[ObsidiSync](https://github.com/kellertobias/obsidisync), native preview APIs from
[Markwon](https://noties.io/Markwon/docs/v4/image/), and display APIs from the
[official BOOX SDK](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/EPD-Screen-Update.md).

## Unlocked tablet smoke test

The 2026-09-07/08 unlocked UI test passed note creation, Markdown preview,
handwriting input routing, eraser/undo, page navigation, PDF import/annotation,
and persistence across restart. A lingering keyboard on Preview was fixed and
the rebuilt app remains installed. See [the report and screenshots](verification/boox-2026-09-08/REPORT.md)
for exact coverage and remaining physical-device/live-sync limits.

## Live ink follow-up (2026-09-08)

The first BOOX raw-reader backend reported enabled but received no completed
strokes on the test tablet. The user confirmed that ink appeared only on pen
lift. That build suppressed the Android preview and was rejected.

The installed replacement uses `FEATURE_APP_PEN_TOUCH_RENDER`, explicitly forwards
Android stylus events through `TouchHelper.onTouchEvent`, and keeps incremental
Android live preview enabled. The “BOOX pen drawing” counter counts SDK callbacks;
it does not prove that the physical panel displayed each point. Debug build,
14 JVM tests, lint, and formatting checks pass with this backend. Physical live
ink visibility and latency still require confirmation; the tablet switched to
InkDAV during follow-up, so no further input was injected.

## Compact controls follow-up (2026-09-08)

- Sidebar mode tabs now precede the closing chevron in the black header; collapsed
  mode hides those tabs and shows a hamburger. Page navigation precedes Sync.
- Folders and Files each have a single heading row with Create, Edit, and View
  Options. Edit menus include rename, move, and delete; view menus retain the
  requested collapse and file visibility controls.
- The persistent save/status row is removed. Sync completion produces a temporary
  outlined floating badge; failures remain visible without a permanent banner.
- Recording playback toggles play/pause, long press resets to the beginning, and
  up to three recent recordings appear underneath.
- Handwriting has thin/thick/marker slots, More Pens, eraser, lasso, Insert, and
  Undo. Long or double press edits thickness/color; additional pens use the same
  gestures. Presets are stored locally per document, independently of other
  documents. Typing groups lists and insert actions and provides Undo.
- The last opened file is remembered in durable local metadata and reopened on
  launch if it still exists.

Validation: debug APK and test APK build, 14 JVM tests, lint and formatting checks
passed. Ten BOOX device tests passed, including header/tab placement and sidebar
collapse/expand. The tablet screenshot confirms the compact layout. Physical
playback listening and the pen smoothness acceptance remain separate checks.

## Shared header and pen popover (2026-09-08)

The editing controls now share the header row with sidebar tabs, page navigation,
and a cloud Sync icon. Editing tools scroll horizontally when space is limited.
Folder/file rows are plain selectable text; their grey section headings have
black top/bottom borders.

Long/double pressing a pen opens an anchored popover with Pen/Pencil/Marker,
a size slider and preview dot, pressure sensitivity, and twelve color swatches.
Changes save directly to that document's local presets. Pressure is calibrated
in input samples so Android and exported PDF strokes agree; marker pressure is
also supported by the updated companion-server patch. Pencil selects the BOOX
pencil brush while retaining compatible pen source data.

Pen-down now requests unbuffered dispatch before SDK processing and reuses
unchanged BOOX configuration instead of repeating setup. Android live preview
remains enabled. This removes avoidable input work; physical latency improvement
is not yet confirmed.

Validation: 14 JVM tests, build/lint/ktlint, 10 device tests, and 11 targeted server
tests passed. The final device launch/input tests were rerun after marker support.
The on-device popover exposes all four requested rows and twelve swatches;
see [the tablet capture](verification/boox-2026-09-08/pen-popover-and-shared-header.png).
The server patch applies cleanly but has not been deployed.

## Sidebar alignment and native crash repair (2026-09-08)

The 02:24:39 tablet crash was SIGABRT in Android MotionEvent.getToolType on a
background thread. Inspection of the bundled BOOX SDK showed AppTouchRender
retains the down event in an asynchronous Rx consumer. Passing framework-owned
events, or immediately recycling calibrated copies, allowed that consumer to
read recycled native input state. The bridge now transfers independent events
to the SDK and leaves their lifetime to GC after SDK references are released;
the SDK offers no event-completion callback for safe pooling.

A device stress check sends 120 strokes, alternates calibrated/default pressure,
recycles every framework event, and forces GC periodically. The 11-test device
suite passes; the strengthened targeted stress check also requires SDK callbacks.
The input canvas is isolated and unsaved, preserving existing notes.

The sidebar draws a 2dp black right border after its children, and the header
reserves the same 320dp width as the open sidebar. A device assertion verifies
that the editing tools and document start at the same horizontal coordinate.

Launcher backgrounds are pure white in InkVault and the three InkDAV variants.
InkVault is installed. The three other APKs build, but Android rejects updates
with INSTALL_FAILED_UPDATE_INCOMPATIBLE: their installed signing key differs
from the local debug key. No apps were uninstalled or data cleared. Their
matching release signing configuration is required to install the icon update.

## Synchronous BOOX drawing (2026-09-08)

The user's comparison with native BOOX Notes still rejected latency after the
event-lifetime repair. Inspection of AppTouchRender showed it submits move
points through Rx observeOn/subscribeOn on SingleThreadScheduler.

The bridge now uses TouchHelper only to configure the hardware region, brush,
and lifecycle. It calls EpdController.moveTo/quadTo/penUp directly during Android
stylus dispatch, including historical samples and calibrated pressure. No input
events are passed into the asynchronous SDK input reader. The previous owned-
MotionEvent workaround is superseded; this removes both queue delay and event
copy allocation from this path. Initial brush setup also now follows openDrawing
so SDK initialization cannot reset the selected style. Android preview remains
enabled because native submission alone does not prove visible panel response.

Installed debug build: 14 JVM tests, lint/ktlint and all 11 BOOX device tests pass.
The 120-stroke stress test submitted all 360 points; the slowest measured process
call took 0.446458 ms, but the later firmware probe found these calls were
silent no-ops. That timing is superseded by the compatibility correction below.

## Recording rows and live preset icons (2026-09-08)

Recordings are left-aligned text rows with a delete icon on the right, both in
the recent list and all-recordings list. Deletion uses the existing vault
tombstone flow after confirmation and releases playback when deleting its
active file. Import PDF now appears in the file Add menu; its dedicated sidebar
button is removed.

Preset icons use distinct fountain-pen, pencil, and chisel-marker drawings and
a small color swatch whose radius follows thickness. The drawable reads the
preset directly and invalidates when configuration changes. More Pens uses
two overlapping pen outlines.

Build, 14 JVM tests, lint and ktlint passed. Installed on the BOOX; visually
verified recording alignment/delete placement, icon color dots, and the Add
menu's Import PDF option. Existing recordings were not deleted during checking.
See [tablet capture](verification/boox-2026-09-08/recordings-and-preset-icons.png).


## Active selection and BOOX firmware access (2026-09-08)

Active drawing tools now use black backgrounds and white symbols, including the
selected preset in More Pens. Tapping an already selected pen opens its settings
popover, matching long press. Preset color/thickness dots retain their colors.

The previous direct EpdController wrappers silently did nothing: Android hid
the android.onyx.ViewUpdateHelper methods used by SDMDevice reflection.
BooxFirmware initialization runs in Application.attachBaseContext and uses
[LSPass 6.1](https://github.com/LSPosed/AndroidHiddenApiBypass) to exempt only
Landroid/onyx/ in this app process before SDK initialization. It verifies the
firmware moveTo method is visible; the bridge stays disabled if initialization
fails. Apache 2.0 license and attribution are included in assets/licenses.

The device probe now confirms actual firmware bindings. Build, 14 JVM tests,
lint, ktlint and all 12 device tests pass. A 120-stroke / 360-point test recorded
8.091198 ms maximum native submission overhead. This corrects earlier no-op
timing claims; physical pencil latency still requires user comparison.
See the [updated device report](verification/boox-2026-09-08/REPORT.md).
