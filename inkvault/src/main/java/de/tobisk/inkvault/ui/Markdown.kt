package de.tobisk.inkvault.ui

import de.tobisk.inkvault.data.VaultPath

object Markdown {
    data class Heading(val level: Int, val title: String, val offset: Int)

    /** Fenced blocks are opaque; preview conversion is never written back to the source. */
    fun outline(source: String): List<Heading> {
        val result = mutableListOf<Heading>()
        var offset = 0
        var fence: Char? = null
        var fenceLength = 0
        source.lineSequence().forEach { line ->
            val trimmed = line.trimStart()
            val marker = Regex("^(`{3,}|~{3,})").find(trimmed)?.value
            if (marker != null && fence == null) {
                fence = marker[0]
                fenceLength = marker.length
            } else if (marker != null && marker[0] == fence && marker.length >= fenceLength) {
                fence = null
            } else if (fence == null) {
                Regex("^ {0,3}(#{1,6}) +(.+?) *#* *$").find(line)?.let { result.add(Heading(it.groupValues[1].length, it.groupValues[2], offset)) }
            }
            offset += line.length + 1
        }
        return result
    }
    fun resolve(current: String, target: String, paths: List<String>): String? {
        val raw = target.substringBefore('#').substringBefore('^').trim()
        if (raw.isEmpty()) return current
        fun normalize(value: String): String? {
            val parts = mutableListOf<String>()
            for (p in value.split('/')) {
                when (p) {
                    "", "." -> Unit
                    ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                    else -> parts.add(p)
                }
            }
            return parts.joinToString("/")
        }
        val relative = normalize("${VaultPath.folder(current)}/$raw")
        val root = normalize(raw)
        val candidates = listOfNotNull(relative, relative?.plus(".md"), root, root?.plus(".md"))
        candidates.firstOrNull { it in paths }?.let { return it }
        return paths.filter { VaultPath.name(it) == raw || VaultPath.name(it) == "$raw.md" }.singleOrNull()
    }
    fun preview(source: String): String {
        var fence: Char? = null
        return source.lineSequence().joinToString("\n") { line ->
            val marker = Regex("^\\s*(`{3,}|~{3,})").find(line)?.groupValues?.get(1)
            if (marker != null) {
                fence = if (fence == marker[0]) null else marker[0]
                line
            } else if (fence != null) {
                line
            } else {
                val callout = line.replace(Regex("^(\\s*>\\s*)\\[!(info|warning|success)]([+-]?)\\s*", RegexOption.IGNORE_CASE)) { "${it.groupValues[1]}**${it.groupValues[2].replaceFirstChar(Char::titlecase)}** " }
                callout.replace(Regex("(!?)\\[\\[([^]\\n]+)]]")) {
                    val target = it.groupValues[2].substringBefore('|')
                    val label = it.groupValues[2].substringAfter('|', target)
                    if (it.groupValues[1] == "!") {
                        "![$label](vault-image:${java.net.URLEncoder.encode(target, "UTF-8")})"
                    } else {
                        "[$label](inkvault:${java.net.URLEncoder.encode(target, "UTF-8")})"
                    }
                }
            }
        }
    }
}
