package de.tobisk.inkvault

import de.tobisk.inkvault.ink.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExcalidrawTest {
    private val source = """{"type":"excalidraw","version":2,"custom":{"retain":true},"appState":{},"files":{"abc":{"dataURL":"data:image/png;base64,AA=="}},"elements":[{"id":"ink","type":"freedraw","x":-40,"y":-20,"width":10,"height":10,"points":[[0,0],[10,10]],"strokeColor":"#123456","strokeWidth":2,"version":5,"customData":{"retain":true}},{"id":"shape","type":"rectangle","x":-100,"y":-80,"width":50,"height":30,"angle":0.4,"version":3,"fillStyle":"hachure","groupIds":["group"]},{"id":"future","type":"future-shape","x":1,"y":2,"width":3,"height":4,"data":"retain"}]}"""

    @Test fun uneditedElementsAndExtensionsSurviveNativeRoundTrip() {
        val document = ExcalidrawDocument(source)
        assertEquals(-8000L, document.initialPage.strokes.single().points.first().x)
        val saved = JSONObject(document.encode(document.initialPage))
        assertEquals(JSONObject(source).toString(), saved.toString())
        assertEquals(1, document.warnings.size)
    }

    @Test fun nativeStrokeWritesValidExcalidrawAndPreservesOtherElements() {
        val document = ExcalidrawDocument(source)
        val stroke = InkStroke(points = listOf(InkPoint(-60000, -10000, 0, 700), InkPoint(10000, 40000, 10, 1200)), style = InkStyle(pressure = true))
        val page = document.initialPage.copy(strokes = document.initialPage.strokes + stroke)
        val saved = JSONObject(document.encode(page))
        assertEquals(4, saved.getJSONArray("elements").length())
        val added = saved.getJSONArray("elements").getJSONObject(3)
        assertEquals("freedraw", added.getString("type"))
        assertEquals(-300.0, added.getDouble("x"), 0.001)
        val reopened = ExcalidrawDocument(saved.toString())
        assertEquals(2, reopened.initialPage.strokes.size)
        assertEquals(JSONObject(source).getJSONArray("elements").getJSONObject(2).toString(), saved.getJSONArray("elements").getJSONObject(2).toString())
    }

    @Test fun moveEraseUndoAndRepeatedSaveKeepVersionsAndMetadata() {
        val document = ExcalidrawDocument(source)
        val moved = document.initialPage.transform(setOf("shape", "ink"), 2000, -4000)
        val first = JSONObject(document.encode(moved)).getJSONArray("elements")
        assertEquals(-90.0, first.getJSONObject(1).getDouble("x"), 0.001)
        assertEquals(0.4, first.getJSONObject(1).getDouble("angle"), 0.001)
        assertEquals("hachure", first.getJSONObject(1).getString("fillStyle"))
        val next = JSONObject(document.encode(moved.transform(setOf("ink"), 1000, 0))).getJSONArray("elements")
        assertTrue(next.getJSONObject(0).getInt("version") > first.getJSONObject(0).getInt("version"))
        val erased = JSONObject(document.encode(moved.copy(strokes = emptyList()))).getJSONArray("elements")
        assertTrue(erased.getJSONObject(0).getBoolean("isDeleted"))
        val undo = JSONObject(document.encode(document.initialPage)).getJSONArray("elements")
        assertFalse(undo.getJSONObject(0).optBoolean("isDeleted"))
        assertTrue(undo.getJSONObject(0).getInt("version") > erased.getJSONObject(0).getInt("version"))
    }

    @Test fun compressedObsidianDrawingOpensNativelyAndRetainsMarkdown() {
        val encoded = "N4IgLgngDgpiBcIYA8DGBDANgSwCYCd0B3EAGiUxgFsYA7MAZwQG0Bdc9KKAZTHTDjxgAX3IAzbJSZDRIAK61sqAPa5BIAOL4APwH2YAAkA8G4Fh9kMKA==="
        val header = "---\nexcalidraw-plugin: parsed\n---\nMy note\n%%\n## Drawing\n"
        val doc = ExcalidrawDocument(header + "```compressed-json\n$encoded\n```\n%%\nTrailing note")
        assertEquals("Grüße 🌍", doc.scene.getString("unicode"))
        val saved = doc.encode(doc.initialPage)
        assertTrue(saved.startsWith(header))
        assertTrue(saved.endsWith("\n%%\nTrailing note"))
        assertTrue(saved.contains("```json\n"))
        assertEquals("Grüße 🌍", ExcalidrawDocument(saved).scene.getString("unicode"))
    }

    @Test fun malformedSceneDoesNotOpenAsEmptyCanvas() {
        for (value in listOf("{}", "not a drawing", "## Drawing\n```compressed-json\ninvalid\n```")) assertThrows(Exception::class.java) { ExcalidrawDocument(value) }
    }
}
