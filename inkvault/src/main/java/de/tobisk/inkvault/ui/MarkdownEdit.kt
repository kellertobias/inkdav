package de.tobisk.inkvault.ui

object MarkdownEdit {
    data class Replacement(val start: Int, val end: Int, val text: String, val cursor: Int)
    fun format(source: String, start: Int, end: Int, kind: String): Replacement {
        require(start in 0..source.length && end in start..source.length)
        val prefix = when (kind) {
            "heading" -> "# "
            "bullet" -> "- "
            "numbered" -> "1. "
            "quote" -> "> "
            else -> null
        }
        if (prefix != null) {
            val first = if (start == 0) 0 else source.lastIndexOf('\n', start - 1) + 1
            val last = source.indexOf('\n', (end - if (end > start) 1 else 0).coerceAtLeast(first)).let { if (it < 0) source.length else it }
            val result = source.substring(first, last).split('\n').mapIndexed { index, line -> (if (kind == "numbered") "${index + 1}. " else prefix) + line }.joinToString("\n")
            return Replacement(first, last, result, first + result.length)
        }
        val selected = source.substring(start, end)
        val wrapper = if (kind == "bold") {
            "**"
        } else if (kind == "italic") {
            "*"
        } else {
            ""
        }
        val text = if (kind == "table") "\n| Heading | Heading |\n| --- | --- |\n| $selected | |\n" else "$wrapper$selected$wrapper"
        return Replacement(start, end, text, if (start == end && wrapper.isNotEmpty()) start + wrapper.length else start + text.length)
    }
}
