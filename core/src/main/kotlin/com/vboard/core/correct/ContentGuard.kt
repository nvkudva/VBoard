package com.vboard.core.correct

/**
 * Shields the parts of a typed message that the Tier-1 cleanup pass would
 * otherwise destroy.
 *
 * [com.vboard.core.text.Tokenizer] was built for ASR output, which is prose and
 * nothing else: it splits on punctuation, **drops** every symbol it does not
 * recognize, and re-renders with its own spacing rules. Run it over typed text
 * and `https://a.co/x` comes back as `https a.co x`, `3.14` as `3. 14`, and an
 * emoji simply disappears.
 *
 * So before the cleaner sees the text, every span that must survive verbatim is
 * swapped for an alphanumeric placeholder — which the tokenizer treats as an
 * ordinary word — and swapped back afterwards. What is left for the cleaner to
 * work on is exactly the plain prose that spelling, casing and duplicate-word
 * rules are safe on.
 *
 * A span is shielded when its core (the chunk minus wrapping punctuation) holds
 * any character outside `letter / digit / ' / -`, any digit at all, or an
 * uppercase letter past the first position. That covers URLs, email addresses,
 * @handles, #tags, file paths, code, currency, times, versions, dates, emoji,
 * and deliberate casing like `iPhone` or `ASAP`.
 */
object ContentGuard {

    /** Wrapping characters peeled off a chunk before deciding what to shield. */
    private val LEAD_TRIM = "([{\"'“‘".toSet()
    private val TRAIL_TRIM = ".,!?;:\"')]}’”".toSet()

    private const val PLACEHOLDER_BASE = "vbkeep"
    private const val PLACEHOLDER_TAIL = "x"

    /**
     * The masked form of one piece of text plus the spans needed to put it back.
     *
     * Deliberately not a data class: the shielded spans are user content and
     * must never reach a log through a generated `toString()`.
     */
    class Shield internal constructor(
        val masked: String,
        private val spans: List<String>,
        private val prefix: String,
        /**
         * True when the last non-whitespace chunk ended with a shielded span, so
         * callers know not to staple a terminal period onto a URL.
         */
        val endsWithShieldedSpan: Boolean,
    ) {
        val shieldedCount: Int get() = spans.size

        /** Reverses [shield] over cleaned text. */
        fun restore(text: String): String {
            if (spans.isEmpty()) return text
            var out = text
            for (i in spans.indices) {
                out = replaceIgnoringCase(out, placeholderAt(i), spans[i])
            }
            return out
        }

        private fun placeholderAt(index: Int): String = "$prefix$index$PLACEHOLDER_TAIL"

        override fun toString(): String = "Shield(spans=${spans.size})"
    }

    fun shield(text: String): Shield {
        val prefix = freshPrefix(text)
        val spans = mutableListOf<String>()
        val out = StringBuilder(text.length)
        var endsWithSpan = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isWhitespace()) {
                out.append(c)
                i++
                continue
            }
            var end = i
            while (end < text.length && !text[end].isWhitespace()) end++
            val chunk = text.substring(i, end)
            var coreStart = 0
            while (coreStart < chunk.length && chunk[coreStart] in LEAD_TRIM) coreStart++
            var coreEnd = chunk.length
            while (coreEnd > coreStart && chunk[coreEnd - 1] in TRAIL_TRIM) coreEnd--
            val core = chunk.substring(coreStart, coreEnd)
            if (core.isNotEmpty() && needsShield(core)) {
                out.append(chunk, 0, coreStart)
                out.append(prefix).append(spans.size).append(PLACEHOLDER_TAIL)
                spans.add(core)
                out.append(chunk, coreEnd, chunk.length)
                // A trailing "." that was peeled off is real punctuation and stays
                // in the prose stream, so only a chunk that *ends* in its span
                // suppresses terminal punctuation.
                endsWithSpan = coreEnd == chunk.length
            } else {
                out.append(chunk)
                endsWithSpan = false
            }
            i = end
        }
        return Shield(out.toString(), spans, prefix, endsWithSpan)
    }

    /** True when the cleaner must not be allowed to touch [core]. */
    fun needsShield(core: String): Boolean {
        var upperInside = false
        for (index in core.indices) {
            val ch = core[index]
            when {
                ch.isDigit() -> return true
                ch.isLetter() -> if (index > 0 && ch.isUpperCase()) upperInside = true
                ch == '\'' || ch == '-' -> Unit
                else -> return true
            }
        }
        return upperInside
    }

    /**
     * Picks a placeholder prefix the text does not already contain, so a user who
     * genuinely types "vbkeep0x" does not get someone else's URL pasted over it.
     */
    private fun freshPrefix(text: String): String {
        val lower = text.lowercase()
        var candidate = PLACEHOLDER_BASE
        var guard = 0
        while (lower.contains(candidate) && guard++ < 8) candidate += "q"
        return candidate
    }

    /**
     * Placeholders are lowercase, but the cleaner's sentence-start rule may have
     * capitalized the first letter, so matching ignores case.
     */
    private fun replaceIgnoringCase(haystack: String, needle: String, replacement: String): String {
        var index = haystack.indexOf(needle, ignoreCase = true)
        if (index < 0) return haystack
        val out = StringBuilder(haystack.length)
        var cursor = 0
        while (index >= 0) {
            out.append(haystack, cursor, index).append(replacement)
            cursor = index + needle.length
            index = haystack.indexOf(needle, cursor, ignoreCase = true)
        }
        out.append(haystack, cursor, haystack.length)
        return out.toString()
    }
}
