package de.tobisk.inkvault.ink

import kotlin.math.*

/** Shapes use ordinary source strokes, so all clients and the PDF renderer agree. */
object InkShapes {
    val tools = setOf("line", "rectangle", "ellipse", "arrow")
    fun points(tool: String, from: InkPoint, to: InkPoint): List<InkPoint> {
        fun p(x: Double, y: Double) = to.copy(x = x.toLong(), y = y.toLong())
        return when (tool) {
            "rectangle" -> listOf(from, to.copy(y = from.y), to, to.copy(x = from.x), from.copy(time = to.time))
            "ellipse" -> (0..64).map { index ->
                val angle = index * 2 * PI / 64
                p((from.x + to.x) / 2.0 + (to.x - from.x) / 2.0 * cos(angle), (from.y + to.y) / 2.0 + (to.y - from.y) / 2.0 * sin(angle))
            }
            "arrow" -> {
                val angle = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
                val head = min(8000.0, hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble()) / 3)
                listOf(from, to, p(to.x - head * cos(angle - PI / 6), to.y - head * sin(angle - PI / 6)), to, p(to.x - head * cos(angle + PI / 6), to.y - head * sin(angle + PI / 6)))
            }
            else -> listOf(from, to)
        }
    }
}
