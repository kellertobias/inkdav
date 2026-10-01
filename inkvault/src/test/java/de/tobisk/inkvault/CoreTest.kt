package de.tobisk.inkvault

import de.tobisk.inkvault.audio.Mp3Encoder
import de.tobisk.inkvault.data.*
import de.tobisk.inkvault.ink.*
import de.tobisk.inkvault.ui.Markdown
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun pressureSmoothingKeepsTheConfiguredBaselineAndDampsSensorJitter() {
        val pressure = InkPressureSmoother()
        assertEquals(1f, pressure.sample(0.2f, 0f), 0.0001f)
        assertEquals(1f, pressure.sample(1.8f, 0f), 0.0001f)

        pressure.reset()
        assertEquals(0.5f, pressure.sample(0.5f, 1f), 0.0001f)
        assertEquals(0.85f, pressure.sample(1.5f, 1f), 0.0001f)
        assertEquals(1.0775f, pressure.sample(1.5f, 1f), 0.0001f)

        pressure.reset()
        assertEquals(InkPressureSmoother.MIN, pressure.sample(-10f, 1f), 0.0001f)
        pressure.reset()
        assertEquals(InkPressureSmoother.MAX, pressure.sample(10f, 1f), 0.0001f)
    }

    @Test fun penSizeControlUsesTheVisiblePartOfTheLegacyRange() {
        assertEquals(300L, InkPresetSize.fromSlider(0))
        assertEquals(4000L, InkPresetSize.fromSlider(InkPresetSize.SLIDER_MAX))
        assertEquals(220, InkPresetSize.toSlider(2500))
        assertEquals(300L, InkPresetSize.restoreV2(1180))
        assertEquals(900L, InkPresetSize.restoreV2(1640))
        assertEquals(4000L, InkPresetSize.restoreV2(4000))
    }

    @Test fun booxPreviewUsesTheMeasuredNativeWidthCalibration() {
        assertEquals(1f, de.tobisk.inkvault.ui.BooxPenBridge.WIDTH_CALIBRATION, 0f)
        assertEquals(0.6f, de.tobisk.inkvault.ui.BooxPenBridge.PENCIL_WIDTH_CALIBRATION, 0f)
        assertEquals(3f, de.tobisk.inkvault.ui.InkCanvas.MIN_RENDERED_STROKE_PIXELS, 0f)
        assertEquals(16, de.tobisk.inkvault.ui.InkCanvas.PENCIL_TEXTURE_SIZE)
        assertEquals(44, de.tobisk.inkvault.ui.InkCanvas.PENCIL_TEXTURE_HOLE_CUTOFF)
        assertEquals(96, de.tobisk.inkvault.ui.InkCanvas.PENCIL_TEXTURE_LIGHT_CUTOFF)
        assertEquals(96, de.tobisk.inkvault.ui.InkCanvas.PENCIL_TEXTURE_LIGHT_ALPHA)
        assertEquals(180, de.tobisk.inkvault.ui.InkCanvas.PENCIL_TEXTURE_DARK_ALPHA)
        assertEquals(1.35f, de.tobisk.inkvault.ui.InkCanvas.PENCIL_TEXTURE_PIXEL_SIZE, 0f)
    }

    @Test fun typingActionsFormatWholeLinesAndKeepAnInsertionCursorInsideEmphasis() {
        val edit = de.tobisk.inkvault.ui.MarkdownEdit.format("First\nSecond", 9, 9, "heading")
        assertEquals(6, edit.start)
        assertEquals("# Second", edit.text)
        val lists = de.tobisk.inkvault.ui.MarkdownEdit.format("One\nTwo\nThree", 0, 8, "numbered")
        assertEquals("1. One\n2. Two", lists.text)
        val bold = de.tobisk.inkvault.ui.MarkdownEdit.format("word", 4, 4, "bold")
        assertEquals(6, bold.cursor)
        assertEquals("****", bold.text)
    }

    @Test fun shapesRemainEditableAfterSourceRoundTripAndUndo() {
        val from = InkPoint(10000, 20000, 1)
        val to = InkPoint(80000, 90000, 2)
        val history = InkHistory()
        var page = InkPage()
        for (tool in InkShapes.tools) {
            history.record(page)
            page = page.copy(strokes = page.strokes + InkStroke(points = InkShapes.points(tool, from, to), style = InkStyle()))
        }
        assertEquals(page, InkPage.decode(page.bytes()))
        val previous = history.undo(page)!!
        assertEquals(3, previous.strokes.size)
        assertSame(page, history.redo(previous))
        val rectangle = InkShapes.points("rectangle", from, to)
        assertEquals(rectangle.first().x, rectangle.last().x)
        assertEquals(rectangle.first().y, rectangle.last().y)
    }

    @Test fun pathsRejectTraversalAndPlatformEscapes() {
        listOf("../secret", "a/../b", "/absolute", "a\\b", "a//b", ".git/config", "a\u0000b").forEach { path -> assertThrows(IllegalArgumentException::class.java) { VaultPath.requireValid(path) } }
        assertEquals("a/é.md", VaultPath.requireValid("a/é.md"))
    }

    @Test fun blobsRejectCorruptionWithoutPublishing() {
        val root = Files.createTempDirectory("vault-test").toFile()
        try {
            val blobs = BlobStore(root)
            val expected = VaultPath.sha256("good".toByteArray())
            assertThrows(IllegalArgumentException::class.java) { blobs.put("bad".byteInputStream(), expected, 3) }
            assertFalse(blobs.file(expected).exists())
            assertEquals(0, root.listFiles()!!.size)
            assertEquals(expected, blobs.put("good".byteInputStream(), expected, 4))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun cborIsCanonicalAndRejectsAmbiguousInput() {
        val a = linkedMapOf("longer" to 5L, "a" to listOf(1L, null, true))
        val b = linkedMapOf("a" to listOf(1L, null, true), "longer" to 5L)
        assertArrayEquals(CanonicalCbor.encode(a), CanonicalCbor.encode(b))
        assertEquals(a, CanonicalCbor.decode(CanonicalCbor.encode(a)))
        assertThrows(IllegalArgumentException::class.java) { CanonicalCbor.decode(byteArrayOf(0x18, 1)) }
        assertThrows(IllegalArgumentException::class.java) { CanonicalCbor.decode(byteArrayOf(1, 1)) }
    }

    @Test fun eraserHitsSegmentsBetweenSamplesAndUndoRestoresVectors() {
        val stroke = InkStroke(points = listOf(InkPoint(0, 10000, 0), InkPoint(20000, 10000, 10)), style = InkStyle())
        val page = InkPage(strokes = listOf(stroke), extra = mapOf("future" to "keep"))
        val history = InkHistory()
        history.record(page)
        val erased = page.erase(InkPoint(10000, 0, 0), InkPoint(10000, 20000, 10), 100)
        assertTrue(erased.strokes.isEmpty())
        assertEquals(listOf(stroke.id), erased.tombstones)
        val restored = history.undo(erased)!!
        assertArrayEquals(page.bytes(), restored.bytes())
        assertEquals("keep", restored.extra["future"])
        assertTrue(history.redo(restored)!!.strokes.isEmpty())
    }

    @Test fun markerCanUsePressure() {
        val style = InkStyle(tool = "marker", pressure = true)
        assertEquals(true, style.value()["pressure"])
    }

    @Test fun pageLayoutPreferencesAreIndependentAndDefaultToSinglePage() {
        assertEquals(de.tobisk.inkvault.ui.PageLayoutMode.SINGLE, de.tobisk.inkvault.ui.PageLayoutMode.fromStored(null))
        assertEquals(de.tobisk.inkvault.ui.PageLayoutMode.SINGLE, de.tobisk.inkvault.ui.PageLayoutMode.fromStored("unknown"))
        assertEquals(de.tobisk.inkvault.ui.PageLayoutMode.SCROLL, de.tobisk.inkvault.ui.PageLayoutMode.fromStored("scroll"))
        assertNotEquals(de.tobisk.inkvault.ui.PageLayoutMode.SINGLE.storedValue, de.tobisk.inkvault.ui.PageLayoutMode.SCROLL.storedValue)
    }

    @Test fun pageFitPreferencesAreIndependentByOrientationAndSidebarState() {
        val mode = de.tobisk.inkvault.ui.PageFitMode
        assertEquals(de.tobisk.inkvault.ui.PageFitMode.WIDTH, mode.fromStored(null))
        assertEquals(de.tobisk.inkvault.ui.PageFitMode.HEIGHT, mode.fromStored("height"))
        assertEquals(4, listOf(false, true).flatMap { landscape -> listOf(false, true).map { sidebar -> mode.preferenceKey(landscape, sidebar) } }.distinct().size)
    }

    @Test fun markdownOutlineDoesNotModifySourceOrParseFences() {
        val text = "# Title\n```md\n# not a heading\n```\n## Real\n> [!warning] Careful\n[[Real|Alias]]"
        assertEquals(listOf("Title", "Real"), Markdown.outline(text).map { it.title })
        assertTrue(Markdown.preview(text).contains("**Warning**"))
        assertTrue(text.contains("[[Real|Alias]]"))
        assertEquals("folder/Other.md", Markdown.resolve("folder/Note.md", "Other", listOf("folder/Other.md", "Other.md")))
        assertNull(Markdown.resolve("Note.md", "dup", listOf("a/dup.md", "b/dup.md")))
    }

    @Test fun markdownFrontMatterIsSeparatedAndPreservedAsFileMetadata() {
        val source = "---\ntitle: Field notes\naliases:\n  - Notebook\ntags: [work]\n---\n# Visible heading\nBody"
        val file = Markdown.file(source)
        assertEquals("---\ntitle: Field notes\naliases:\n  - Notebook\ntags: [work]\n---\n", file.header)
        assertEquals("# Visible heading\nBody", file.body)
        assertEquals(
            listOf(
                Markdown.Property("title", "Field notes"),
                Markdown.Property("aliases", "- Notebook"),
                Markdown.Property("tags", "[work]")
            ),
            file.properties
        )
        assertEquals(Markdown.FileParts("", "---\nNot metadata", emptyList()), Markdown.file("---\nNot metadata"))
    }

    @Test fun mp3EncoderEmitsMpegFrames() {
        val output = java.io.ByteArrayOutputStream()
        Mp3Encoder().use { encoder ->
            val buffer = ByteArray(16384)
            repeat(12) { output.write(buffer, 0, encoder.encode(ShortArray(4096) { n -> (kotlin.math.sin(n * 2 * Math.PI * 440 / 44100) * 12000).toInt().toShort() }, 4096, buffer)) }
            output.write(buffer, 0, encoder.finish(buffer))
        }
        val bytes = output.toByteArray()
        assertTrue(bytes.size > 1000)
        assertTrue((0 until bytes.size - 1).any { bytes[it].toInt() and 255 == 255 && bytes[it + 1].toInt() and 0xe0 == 0xe0 })
    }
}
