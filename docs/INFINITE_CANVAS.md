# Native Infinite Canvas

Create file → Infinite Canvas creates a standard `.excalidraw` file. Excalidraw
and Obsidian drawing files open directly from the vault. This mode has no paper
boundary, pages, or PDF annotation action; existing document modes remain intact.

The native `InkCanvas` handles all drawing, selection, history and input. The
same `buildInkTools` function builds the header toolbar for handwriting and
Infinite Canvas. A finger always navigates, even when eraser, lasso or a shape
tool is selected. One finger pans; two fingers zoom. A stylus or its eraser edits.
Navigation does not alter or save document content. Pending gestures are canceled
on interrupted input rather than saved as partial edits.

`ExcalidrawDocument` maps the scene to immutable native strokes/objects and keeps
unmodified source elements and extension fields. Edits update geometry and element
versions; deletions retain tombstones. Obsidian LZ-String compressed Drawing blocks
are decoded in Kotlin; saves use valid uncompressed JSON blocks with the surrounding
Markdown retained. The local blob store remains the persistence and conflict boundary.

The renderer displays basic shapes, text, arrows and local images, with rotation,
opacity, fill colors and dashed strokes. Unknown elements and missing images use
placeholders. Rough sketch styling, hachure fills, exact Excalidraw fonts, complex
arrowheads, image crop and frame clipping are currently approximate or unsupported;
their original fields are preserved for desktop editing. Text content editing and
plugin-specific interactive embeds are not exposed by the handwriting toolbar.

## Verification

```
JAVA_HOME=/path/to/jdk17 ./gradlew :inkvault:ktlintCheck :inkvault:testDebugUnitTest :inkvault:lintDebug :inkvault:assembleDebug
```

`ExcalidrawTest` covers plain/compressed round trips, metadata preservation, negative
coordinates, additions, transforms, deletion, undo and version advancement.
`NativeCanvasInputTest` uses Robolectric with native Android graphics on the host,
without launching InkVault or opening a vault. It exercises finger navigation under
every tool, pinch transitions, stylus edits, negative coordinates, cancellation,
undo/redo, and a stylus joining a finger gesture.

Do not run `connectedDebugAndroidTest` on a personal tablet: the Gradle UTP cleanup
can uninstall the app and erase its private data. No device installation is required
for these JVM checks. Hardware pen latency must be accepted separately with a safe
in-place installation and a backup.
