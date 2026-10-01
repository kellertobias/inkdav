package de.tobisk.inkvault.ink

import java.util.UUID
import kotlin.math.hypot

data class InkPoint(val x: Long, val y: Long, val time: Long, val pressure: Long? = null, val tilt: Long? = null) {
    fun value() = listOf(x, y, time, pressure, tilt)
}

/** A small low-pass filter removes digitizer noise without adding a visible pen lag. */
class InkPressureSmoother {
    private var previous: Float? = null

    fun reset() {
        previous = null
    }

    fun sample(raw: Float, sensitivity: Float): Float {
        val target = value(raw, sensitivity)
        val value = previous?.let { it + (target - it) * RESPONSE } ?: target
        previous = value
        return value
    }

    companion object {
        const val MIN = 0.2f
        const val MAX = 2f
        private const val RESPONSE = 0.35f

        fun value(raw: Float, sensitivity: Float) = (1f + (raw - 1f) * sensitivity).coerceIn(MIN, MAX)
    }
}

/** Physical widths exposed by the preset editor, calibrated for the Note Air canvas. */
object InkPresetSize {
    const val MIN = 300L
    const val MAX = 4000L
    const val STEP = 10L
    const val MIN_VISIBLE = 200L
    const val RANGE_VERSION = 3
    const val SLIDER_MAX = ((MAX - MIN) / STEP).toInt()

    fun fromSlider(progress: Int) = MIN + progress.coerceIn(0, SLIDER_MAX) * STEP

    fun toSlider(width: Long) = ((width.coerceIn(MIN, MAX) - MIN) / STEP).toInt()

    /** Undo the short-lived v2 proportional migration and recover the old width. */
    fun restoreV2(width: Long): Long {
        val usefulLegacyMax = 4000L
        val legacyMin = 50L
        val v2Min = 1000L
        val candidates = (legacyMin..usefulLegacyMax step STEP).filter { old ->
            val position = (old - legacyMin).toDouble() / (usefulLegacyMax - legacyMin)
            val migrated = (v2Min + position * (MAX - v2Min)).toLong()
            v2Min + ((migrated - v2Min) / STEP) * STEP == width
        }
        return (candidates.lastOrNull() ?: width).coerceIn(MIN, MAX)
    }
}

data class InkStyle(val tool: String = "pen", val color: Long = 0xff000000, val width: Long = 600, val pressure: Boolean = false, val extra: Map<String, Any?> = emptyMap()) {
    init {
        require(tool in setOf("pen", "marker"))
        require(width in 50..20000)
    }
    fun value() = extra + mapOf("tool" to tool, "color" to color, "width" to width, "pressure" to pressure)
}
data class InkStroke(val id: String = UUID.randomUUID().toString(), val points: List<InkPoint>, val style: InkStyle, val extra: Map<String, Any?> = emptyMap()) {
    init {
        require(points.isNotEmpty() && points.size <= 100000)
    }
    fun value() = extra + mapOf("id" to id, "points" to points.map { it.value() }, "style" to style.value())
}
data class InkPage(
    val width: Long = 210000,
    val height: Long = 297000,
    val strokes: List<InkStroke> = emptyList(),
    val objects: List<Map<String, Any?>> = emptyList(),
    val tombstones: List<String> = emptyList(),
    val extra: Map<String, Any?> = emptyMap()
) {
    init {
        require(width in 1000..2000000 && height in 1000..2000000)
        require(strokes.size <= 50000)
    }
    fun bytes() = CanonicalCbor.encode(extra + mapOf("schemaVersion" to 1, "width" to width, "height" to height, "strokes" to strokes.map { it.value() }, "objects" to objects, "tombstones" to tombstones))
    fun erase(from: InkPoint, to: InkPoint, tolerance: Long): InkPage {
        val removed = strokes.filter { stroke ->
            stroke.points.zipWithNext().any { (a, b) ->
                segmentsIntersect(from, to, a, b) || distance(a, from, to) <= tolerance + stroke.style.width / 2 || distance(b, from, to) <= tolerance + stroke.style.width / 2 || distance(from, a, b) <= tolerance + stroke.style.width / 2
            } ||
                stroke.points.size == 1 &&
                distance(stroke.points[0], from, to) <= tolerance + stroke.style.width / 2
        }
        return copy(strokes = strokes - removed.toSet(), tombstones = tombstones + removed.map { it.id })
    }
    fun transform(ids: Set<String>, dx: Long, dy: Long, scale: Double = 1.0, originX: Long = 0, originY: Long = 0): InkPage {
        require(scale in 0.1..10.0)
        return copy(
            strokes = strokes.map { s -> if (s.id !in ids) s else s.copy(points = s.points.map { p -> p.copy(x = ((p.x - originX) * scale + originX + dx).toLong(), y = ((p.y - originY) * scale + originY + dy).toLong()) }) },
            objects = objects.map { obj -> if (obj["id"] !in ids) obj else obj + mapOf("x" to (((obj["x"] as Long) - originX) * scale + originX + dx).toLong(), "y" to (((obj["y"] as Long) - originY) * scale + originY + dy).toLong(), "width" to ((obj["width"] as Long) * scale).toLong(), "height" to ((obj["height"] as Long) * scale).toLong()) }
        )
    }
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun decode(bytes: ByteArray): InkPage {
            val map = CanonicalCbor.decode(bytes) as Map<String, Any?>
            require(map["schemaVersion"] == 1L) { "Unsupported InkNote page version" }
            val strokes = (map["strokes"] as List<Map<String, Any?>>).map { s ->
                val style = s["style"] as Map<String, Any?>
                InkStroke(
                    s["id"] as String,
                    (s["points"] as List<List<Long?>>).map { p ->
                        require(p.size == 5) { "Unsupported point schema" }
                        InkPoint(requireNotNull(p[0]), requireNotNull(p[1]), requireNotNull(p[2]), p.getOrNull(3), p.getOrNull(4))
                    },
                    InkStyle(style["tool"] as String, style["color"] as Long, style["width"] as Long, style["pressure"] as Boolean, style - setOf("tool", "color", "width", "pressure")),
                    s - setOf("id", "points", "style")
                )
            }
            return InkPage(map["width"] as Long, map["height"] as Long, strokes, map["objects"] as List<Map<String, Any?>>, map["tombstones"] as List<String>, map - setOf("schemaVersion", "width", "height", "strokes", "objects", "tombstones"))
        }
        fun distance(p: InkPoint, a: InkPoint, b: InkPoint): Double {
            val dx = (b.x - a.x).toDouble()
            val dy = (b.y - a.y).toDouble()
            val denominator = dx * dx + dy * dy
            val t = if (denominator == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / denominator).coerceIn(0.0, 1.0)
            return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
        }
        fun segmentsIntersect(a: InkPoint, b: InkPoint, c: InkPoint, d: InkPoint): Boolean {
            fun cross(p: InkPoint, q: InkPoint, r: InkPoint) = (q.x - p.x).toDouble() * (r.y - p.y) - (q.y - p.y).toDouble() * (r.x - p.x)
            return cross(a, b, c) * cross(a, b, d) <= 0 &&
                cross(c, d, a) * cross(c, d, b) <= 0 &&
                maxOf(minOf(a.x, b.x), minOf(c.x, d.x)) <= minOf(maxOf(a.x, b.x), maxOf(c.x, d.x)) &&
                maxOf(minOf(a.y, b.y), minOf(c.y, d.y)) <= minOf(maxOf(a.y, b.y), maxOf(c.y, d.y))
        }
    }
}

/** Bounded command history; immutable page snapshots never enter the synced source. */
class InkHistory {
    private val undo = ArrayDeque<InkPage>()
    private val redo = ArrayDeque<InkPage>()
    fun record(page: InkPage) {
        undo.addLast(page)
        redo.clear()
        // Snapshots share immutable strokes; bound retained commands without encoding on pen-up.
        val limit = if (page.strokes.sumOf { it.points.size.toLong() } > 50000) 3 else 30
        while (undo.size > limit) undo.removeFirst()
    }
    fun undo(current: InkPage): InkPage? = if (undo.isEmpty()) null else undo.removeLast().also { redo.addLast(current) }
    fun redo(current: InkPage): InkPage? = if (redo.isEmpty()) null else redo.removeLast().also { undo.addLast(current) }
}
