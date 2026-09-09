package de.tobisk.inkvault.ink

import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

/** Excalidraw is a storage format here; all interaction is handled by InkCanvas. */
class ExcalidrawDocument(private val source: String) {
    private val block: MatchResult?
    val scene: JSONObject
    val initialPage: InkPage
    private var originalStrokes: Map<String, InkStroke>
    private var originalObjects: Map<String, Map<String, Any?>>
    val warnings: List<String>
    private val revisions = mutableMapOf<String, Int>()

    init {
        block = if (source.trimStart().startsWith("{")) {
            null
        } else {
            val heading = Regex("(?m)^#{1,2} Drawing\\s*$").find(source)
            requireNotNull(heading) { "No Excalidraw Drawing section" }
            requireNotNull(Regex("```(json|compressed-json)\\s*\\n([\\s\\S]*?)\\r?\\n```").find(source, heading.range.last + 1)) { "No drawing data" }
        }
        val json = block?.let { if (it.groupValues[1] == "compressed-json") LzDrawing.decode(it.groupValues[2]) else it.groupValues[2] } ?: source.trimStart()
        scene = JSONObject(json)
        require(scene.optString("type") == "excalidraw") { "Not an Excalidraw scene" }
        val elements = scene.getJSONArray("elements")
        require(elements.length() <= 50000) { "Drawing element limit exceeded" }
        val strokes = mutableListOf<InkStroke>()
        val objects = mutableListOf<Map<String, Any?>>()
        val unsupported = mutableSetOf<String>()
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            if (element.optBoolean("isDeleted")) continue
            val id = element.getString("id")
            if (element.optString("type") == "freedraw" && element.optJSONArray("points")?.length() != 0) {
                val points = element.getJSONArray("points")
                val pressure = element.optJSONArray("pressures")
                val x = element.optDouble("x", 0.0)
                val y = element.optDouble("y", 0.0)
                val cx = element.optDouble("width", 0.0) / 2
                val cy = element.optDouble("height", 0.0) / 2
                val angle = element.optDouble("angle", 0.0)
                strokes.add(
                    InkStroke(
                        id,
                        (0 until points.length()).map { n ->
                            val p = points.getJSONArray(n)
                            val px = p.getDouble(0) - cx
                            val py = p.getDouble(1) - cy
                            InkPoint(unit(x + cx + px * cos(angle) - py * sin(angle)), unit(y + cy + px * sin(angle) + py * cos(angle)), n.toLong(), ((pressure?.optDouble(n, 0.5) ?: 0.5) * 2000).toLong())
                        },
                        InkStyle(
                            color = color(element.optString("strokeColor", "#000000")),
                            width = unit(element.optDouble("strokeWidth", 1.0)).coerceIn(50, 20000),
                            pressure = !element.optBoolean("simulatePressure", true),
                            extra = mapOf("opacity" to element.optInt("opacity", 100).toLong())
                        )
                    )
                )
            } else {
                val type = element.optString("type")
                if (type !in setOf("rectangle", "diamond", "ellipse", "line", "arrow", "text", "image", "frame")) unsupported.add(type)
                objects.add(mapOf("id" to id, "x" to unit(element.optDouble("x", 0.0)), "y" to unit(element.optDouble("y", 0.0)), "width" to unit(element.optDouble("width", 0.0)), "height" to unit(element.optDouble("height", 0.0)), "excalidraw" to element.toString()))
            }
        }
        initialPage = InkPage(strokes = strokes, objects = objects)
        originalStrokes = strokes.associateBy { it.id }
        originalObjects = objects.associateBy { it["id"] as String }
        warnings = unsupported.map { "Preserved $it as a placeholder" }
    }

    /** Keep original elements byte-for-field equivalent unless a native command changes them. */
    fun encode(page: InkPage): String {
        val result = JSONObject(scene.toString())
        val originals = scene.getJSONArray("elements")
        val strokes = page.strokes.associateBy { it.id }
        val objects = page.objects.associateBy { it["id"] as String }
        val seen = mutableSetOf<String>()
        val elements = JSONArray()
        for (i in 0 until originals.length()) {
            val old = originals.getJSONObject(i)
            val id = old.getString("id")
            seen.add(id)
            val stroke = strokes[id]
            val obj = objects[id]
            val next = when {
                stroke != null && stroke != originalStrokes[id] -> strokeElement(stroke, old)
                obj != null && obj != originalObjects[id] -> objectElement(obj, old)
                stroke != null || obj != null || old.optBoolean("isDeleted") -> JSONObject(old.toString()).also { if ((revisions[id] ?: 0) > old.optInt("version")) bump(it) }
                else -> JSONObject(old.toString()).put("isDeleted", true).also(::bump)
            }
            elements.put(next)
        }
        val added = page.strokes.filter { it.id !in seen }.map { (it.extra["createdAt"] as? Long ?: 0L) to strokeElement(it) } +
            page.objects.filter { it["id"] !in seen }.map { (it["createdAt"] as? Long ?: 0L) to objectElement(it, JSONObject(it["excalidraw"] as String)) }
        added.sortedBy { it.first }.forEach { elements.put(it.second) }
        result.put("elements", elements)
        val json = result.toString(2)
        val match = block ?: return json
        var markdown = source.replaceRange(match.range, "```json\n$json\n```")
        val text = (0 until elements.length()).map { elements.getJSONObject(it) }.filter { it.optString("type") == "text" && !it.optBoolean("isDeleted") }
            .joinToString("\n\n") { "${it.optString("rawText", it.optString("originalText", it.optString("text")))} ^${it.getString("id")}" }
        markdown = Regex("(?m)^## Text Elements\\s*\\n[\\s\\S]*?(?=^## |^%%\\s*$)").replace(markdown) { "## Text Elements\n$text\n\n" }
        return markdown
    }

    fun element(obj: Map<String, Any?>): JSONObject = objectElement(obj, JSONObject(obj["excalidraw"] as String), false)

    /** Match the live native snapshot after reloading a successfully persisted scene. */
    fun adopt(page: InkPage) {
        originalStrokes = page.strokes.associateBy { it.id }
        originalObjects = page.objects.associateBy { it["id"] as String }
    }

    private fun objectElement(obj: Map<String, Any?>, old: JSONObject, update: Boolean = true): JSONObject {
        val next = JSONObject(old.toString())
        val w = (obj["width"] as Long) / UNIT
        val h = (obj["height"] as Long) / UNIT
        val sx = if (old.optDouble("width", 0.0) == 0.0) 1.0 else w / old.getDouble("width")
        val sy = if (old.optDouble("height", 0.0) == 0.0) 1.0 else h / old.getDouble("height")
        old.optJSONArray("points")?.let { points ->
            next.put("points", JSONArray((0 until points.length()).map { n -> listOf(points.getJSONArray(n).getDouble(0) * sx, points.getJSONArray(n).getDouble(1) * sy) }))
        }
        if (old.optString("type") == "text") next.put("fontSize", old.optDouble("fontSize", 20.0) * abs(sy))
        next.put("x", (obj["x"] as Long) / UNIT).put("y", (obj["y"] as Long) / UNIT).put("width", w).put("height", h)
        if (update) {
            next.put("isDeleted", false)
            bump(next)
        }
        return next
    }

    private fun strokeElement(stroke: InkStroke, old: JSONObject? = null): JSONObject {
        val x = stroke.points.minOf { it.x } / UNIT
        val y = stroke.points.minOf { it.y } / UNIT
        val next = old?.let { JSONObject(it.toString()) } ?: JSONObject().put("id", stroke.id).put("type", "freedraw")
            .put("fillStyle", "solid").put("backgroundColor", "transparent").put("strokeStyle", "solid").put("roughness", 0).put("groupIds", JSONArray()).put("frameId", JSONObject.NULL)
            .put("roundness", JSONObject.NULL).put("boundElements", JSONArray()).put("link", JSONObject.NULL).put("locked", false).put("seed", stroke.id.hashCode() and Int.MAX_VALUE)
        next.put("x", x).put("y", y).put("angle", 0).put("width", stroke.points.maxOf { it.x } / UNIT - x).put("height", stroke.points.maxOf { it.y } / UNIT - y)
            .put("points", JSONArray(stroke.points.map { listOf(it.x / UNIT - x, it.y / UNIT - y) }))
            .put("pressures", JSONArray(stroke.points.map { ((it.pressure ?: 1000) / 2000.0).coerceIn(0.0, 1.0) }))
            .put("simulatePressure", !stroke.style.pressure).put("strokeWidth", stroke.style.width / UNIT)
            .put("strokeColor", "#%06x".format(stroke.style.color and 0xffffff)).put("opacity", stroke.style.extra["opacity"] ?: if (stroke.style.tool == "marker") 40 else 100)
            .put("isDeleted", false).put("lastCommittedPoint", JSONObject.NULL)
        bump(next)
        return next
    }

    private fun bump(element: JSONObject) {
        val id = element.getString("id")
        val version = maxOf(element.optInt("version", 0), revisions[id] ?: 0) + 1
        revisions[id] = version
        element.put("version", version).put("versionNonce", java.util.concurrent.ThreadLocalRandom.current().nextInt(Int.MAX_VALUE)).put("updated", System.currentTimeMillis())
    }

    companion object {
        const val UNIT = 200.0
        fun unit(value: Double): Long {
            require(value.isFinite() && abs(value) < 1e10) { "Invalid drawing coordinate" }
            return (value * UNIT).roundToLong()
        }
        fun color(value: String): Long = runCatching {
            val hex = value.removePrefix("#")
            0xff000000L or (if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex).toLong(16)
        }.getOrDefault(0xff000000)
    }
}

/** LZ-String base64 decoder for existing Obsidian compressed-json blocks. Saves use plain JSON. */
internal object LzDrawing {
    fun decode(encoded: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        val data = encoded.filterNot(Char::isWhitespace)
        require(data.isNotEmpty()) { "Empty compressed drawing" }
        var index = 0
        var mask = 32
        fun bits(count: Int): Int {
            var result = 0
            repeat(count) { bit ->
                require(index < data.length) { "Truncated compressed drawing" }
                val value = alphabet.indexOf(data[index])
                require(value in 0..63) { "Invalid compressed drawing" }
                if (value and mask != 0) result = result or (1 shl bit)
                mask = mask shr 1
                if (mask == 0) {
                    mask = 32
                    index++
                }
            }
            return result
        }
        val first = when (bits(2)) {
            0 -> bits(8).toChar().toString()
            1 -> bits(16).toChar().toString()
            else -> error("Invalid compressed drawing")
        }
        val dictionary = mutableListOf("", "", "", first)
        var previous = first
        val output = StringBuilder(first)
        var width = 3
        var remaining = 4
        while (true) {
            var code = bits(width)
            when (code) {
                0, 1 -> {
                    dictionary.add(bits(if (code == 0) 8 else 16).toChar().toString())
                    code = dictionary.lastIndex
                    remaining--
                }
                2 -> return output.toString()
            }
            if (remaining == 0) {
                remaining = 1 shl width
                width++
            }
            val entry = if (code < dictionary.size) {
                dictionary[code]
            } else {
                require(code == dictionary.size) { "Invalid compressed drawing dictionary" }
                previous + previous.first()
            }
            output.append(entry)
            require(output.length <= 32 * 1024 * 1024 && width <= 24) { "Drawing exceeds decompression limit" }
            dictionary.add(previous + entry.first())
            remaining--
            previous = entry
            if (remaining == 0) {
                remaining = 1 shl width
                width++
            }
        }
    }
}
