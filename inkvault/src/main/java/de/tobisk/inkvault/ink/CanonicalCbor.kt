package de.tobisk.inkvault.ink

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** InkNote uses integer micrometres and pressure/tilt thousandths, so there is one numeric encoding. */
object CanonicalCbor {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        fun header(major: Int, n: Long) {
            require(n >= 0)
            when {
                n < 24 -> out.write((major shl 5) or n.toInt())
                else -> {
                    val count = when {
                        n <= 255 -> 1
                        n <= 65535 -> 2
                        n <= 0xffffffffL -> 4
                        else -> 8
                    }
                    out.write(
                        (major shl 5) or when (count) {
                            1 -> 24
                            2 -> 25
                            4 -> 26
                            else -> 27
                        }
                    )
                    for (i in count - 1 downTo 0) out.write((n ushr (i * 8)).toInt() and 255)
                }
            }
        }
        fun write(v: Any?, depth: Int) {
            require(depth <= 32) { "CBOR nesting limit" }
            when (v) {
                null -> out.write(246)
                is Boolean -> out.write(if (v) 245 else 244)
                is Int -> {
                    val n = v.toLong()
                    header(if (n >= 0) 0 else 1, if (n >= 0) n else -1 - n)
                }
                is Long -> header(if (v >= 0) 0 else 1, if (v >= 0) v else -1 - v)
                is String -> {
                    val bytes = v.toByteArray(Charsets.UTF_8)
                    header(3, bytes.size.toLong())
                    out.write(bytes)
                }
                is ByteArray -> {
                    header(2, v.size.toLong())
                    out.write(v)
                }
                is List<*> -> {
                    header(4, v.size.toLong())
                    v.forEach { write(it, depth + 1) }
                }
                is Map<*, *> -> {
                    val keys = v.keys.map {
                        require(it is String)
                        it to encode(it)
                    }.sortedWith { a, b ->
                        if (a.second.size != b.second.size) {
                            a.second.size.compareTo(b.second.size)
                        } else {
                            var comparison = 0
                            for (i in a.second.indices) {
                                comparison = (a.second[i].toInt() and 255).compareTo(b.second[i].toInt() and 255)
                                if (comparison != 0) break
                            }
                            comparison
                        }
                    }
                    header(5, keys.size.toLong())
                    keys.forEach { (key, bytes) ->
                        out.write(bytes)
                        write(v[key], depth + 1)
                    }
                }
                else -> error("Unsupported InkNote CBOR type")
            }
            require(out.size() <= MAX_BYTES) { "Page exceeds 8 MiB" }
        }
        write(value, 0)
        return out.toByteArray()
    }
    fun decode(bytes: ByteArray): Any? {
        require(bytes.size <= MAX_BYTES)
        var pos = 0
        fun byte(): Int {
            require(pos < bytes.size) { "Truncated CBOR" }
            return bytes[pos++].toInt() and 255
        }
        fun read(depth: Int): Any? {
            require(depth <= 32)
            val first = byte()
            val major = first ushr 5
            val info = first and 31
            if (major == 7) {
                return when (info) {
                    20 -> false
                    21 -> true
                    22 -> null
                    else -> error("Only integer numeric encoding is supported")
                }
            }
            val n = when {
                info < 24 -> info.toLong()
                info in 24..27 -> {
                    var result = 0L
                    repeat(1 shl (info - 24)) { result = (result shl 8) or byte().toLong() }
                    require(result >= 0)
                    result
                }
                else -> error("Indefinite CBOR is not canonical")
            }
            return when (major) {
                0 -> n
                1 -> -1 - n
                2, 3 -> {
                    require(n <= bytes.size - pos)
                    val data = bytes.copyOfRange(pos, pos + n.toInt())
                    pos += n.toInt()
                    if (major == 2) data else Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
                }
                4 -> {
                    require(n <= bytes.size - pos)
                    List(n.toInt()) { read(depth + 1) }
                }
                5 -> {
                    require(n <= (bytes.size - pos) / 2)
                    buildMap<String, Any?> {
                        repeat(n.toInt()) {
                            val key = read(depth + 1) as? String ?: error("Expected string key")
                            require(!containsKey(key)) { "Duplicate CBOR key" }
                            put(key, read(depth + 1))
                        }
                    }
                }
                else -> error("Unsupported CBOR major type")
            }
        }
        val value = read(0)
        require(pos == bytes.size) { "Trailing CBOR" }
        require(encode(value).contentEquals(bytes)) { "Noncanonical CBOR" }
        return value
    }
}
