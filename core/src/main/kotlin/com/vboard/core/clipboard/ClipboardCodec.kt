package com.vboard.core.clipboard

/**
 * Serializes the clip history to and from the on-disk `clips.v1.json` document.
 *
 * The project carries no JSON dependency, so this is a deliberately small reader
 * and writer for exactly one shape:
 *
 * ```json
 * {"v":1,"clips":[{"t":"hello","at":1700000000000,"p":false}]}
 * ```
 *
 * [decode] never throws and never reports what it saw: a truncated or otherwise
 * damaged file yields `null`, and the caller then leaves the bad file alone
 * rather than saving an empty history over the top of it.
 */
object ClipboardCodec {

    const val VERSION = 1

    private const val FORM_FEED = '\u000C'

    fun encode(entries: List<ClipEntry>): String {
        val sb = StringBuilder(32 + entries.size * 64)
        sb.append("{\"v\":").append(VERSION).append(",\"clips\":[")
        for ((i, e) in entries.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"t\":")
            appendJsonString(sb, e.text)
            sb.append(",\"at\":").append(e.capturedAtMillis)
            sb.append(",\"p\":").append(e.pinned)
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Returns null when [json] is not a well-formed store of this shape. */
    fun decode(json: String): List<ClipEntry>? = try {
        Reader(json).readStore()
    } catch (_: MalformedException) {
        null
    }

    // ------------------------------------------------------------------ write

    private fun appendJsonString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                FORM_FEED -> sb.append("\\f")
                // Control characters are illegal raw in a JSON string; U+2028 and
                // U+2029 are legal but break naive line-oriented tooling.
                else -> if (c < ' ' || c == '\u2028' || c == '\u2029') {
                    sb.append("\\u").append("%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    // ------------------------------------------------------------------- read

    private class MalformedException : Exception(null, null, false, false)

    private class Reader(private val src: String) {
        private var pos = 0

        fun readStore(): List<ClipEntry> {
            skipSpace()
            expect('{')
            var clips: List<ClipEntry>? = null
            var version: Long? = null
            readMembers { key ->
                when (key) {
                    "v" -> version = readNumber()
                    "clips" -> clips = readClips()
                    else -> skipValue()
                }
            }
            skipSpace()
            if (pos != src.length) fail<Unit>()
            if (version != VERSION.toLong()) fail<Unit>()
            return clips ?: fail()
        }

        private fun readClips(): List<ClipEntry> {
            skipSpace()
            expect('[')
            val out = mutableListOf<ClipEntry>()
            skipSpace()
            if (peek() == ']') {
                pos++
                return out
            }
            while (true) {
                out.add(readClip())
                skipSpace()
                when (next()) {
                    ',' -> Unit
                    ']' -> return out
                    else -> fail<Unit>()
                }
            }
        }

        private fun readClip(): ClipEntry {
            skipSpace()
            expect('{')
            var text: String? = null
            var at: Long? = null
            var pinned: Boolean? = null
            readMembers { key ->
                when (key) {
                    "t" -> text = readString()
                    "at" -> at = readNumber()
                    "p" -> pinned = readBoolean()
                    else -> skipValue()
                }
            }
            return ClipEntry(
                text = text ?: fail(),
                capturedAtMillis = at ?: fail(),
                pinned = pinned ?: fail(),
            )
        }

        /** Reads `"key": value` pairs up to the closing brace of an open object. */
        private fun readMembers(readValue: (String) -> Unit) {
            skipSpace()
            if (peek() == '}') {
                pos++
                return
            }
            while (true) {
                val key = readString()
                skipSpace()
                expect(':')
                readValue(key)
                skipSpace()
                when (next()) {
                    ',' -> Unit
                    '}' -> return
                    else -> fail<Unit>()
                }
            }
        }

        private fun readString(): String {
            skipSpace()
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> when (next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append(FORM_FEED)
                        'u' -> {
                            if (pos + 4 > src.length) fail<Unit>()
                            val hex = src.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: fail())
                        }
                        else -> fail<Unit>()
                    }
                    // A raw control character means the file was mangled.
                    c < ' ' -> fail<Unit>()
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Long {
            skipSpace()
            val start = pos
            if (pos < src.length && src[pos] == '-') pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
            if (pos == start) fail<Unit>()
            return src.substring(start, pos).toLongOrNull() ?: fail()
        }

        private fun readBoolean(): Boolean {
            skipSpace()
            return when {
                src.startsWith("true", pos) -> { pos += 4; true }
                src.startsWith("false", pos) -> { pos += 5; false }
                else -> fail()
            }
        }

        /** Skips an unrecognized member's value, so future keys stay readable. */
        private fun skipValue() {
            skipSpace()
            when (peek()) {
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> if (src.startsWith("null", pos)) pos += 4 else fail<Unit>()
                '{' -> { pos++; readMembers { skipValue() } }
                '[' -> {
                    pos++
                    skipSpace()
                    if (peek() == ']') {
                        pos++
                        return
                    }
                    while (true) {
                        skipValue()
                        skipSpace()
                        when (next()) {
                            ',' -> Unit
                            ']' -> return
                            else -> fail<Unit>()
                        }
                    }
                }
                else -> readNumber()
            }
        }

        private fun skipSpace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char = if (pos < src.length) src[pos] else fail()

        private fun next(): Char = if (pos < src.length) src[pos++] else fail()

        private fun expect(c: Char) {
            if (next() != c) fail<Unit>()
        }

        private fun <T> fail(): T = throw MalformedException()
    }
}
