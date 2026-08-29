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
 * any character outside `letter / digit / ' / -` (a combining mark on a letter
 * excepted), any digit at all, a hyphen on either edge, or an uppercase letter
 * past the first position. That covers URLs, email addresses, @handles, #tags,
 * file paths, code, currency, times, versions, dates, emoji, command-line flags,
 * and deliberate casing like `iPhone` or `ASAP`.
 */
object ContentGuard {

    /** Wrapping characters peeled off a chunk before deciding what to shield. */
    private val LEAD_TRIM = "([{\"'“‘".toSet()
    private val TRAIL_TRIM = ".,!?;:\"')]}’”".toSet()

    private const val PLACEHOLDER_BASE = "vbkeep"
    private const val PLACEHOLDER_TAIL = "x"

    /**
     * Punctuation [com.vboard.core.text.Tokenizer] recognizes. Anything else it
     * silently deletes, so a chunk made only of punctuation still has to be
     * shielded when it contains something from outside this set — otherwise a
     * lone `{` in pasted code just vanishes.
     */
    private val TOKENIZER_SAFE_PUNCT = ".,!?;:\"“”&@#%()-—…'".toSet()

    /**
     * The masked form of one piece of text plus the spans needed to put it back.
     *
     * Deliberately not a data class: the shielded spans are user content and
     * must never reach a log through a generated `toString()`. Making this a data
     * class would re-expose [masked], `spans` and `prefix` in one keystroke, so
     * the shape is pinned by ContentPreservationTest rather than left to
     * convention.
     */
    class Shield internal constructor(
        val masked: String,
        private val spans: List<String>,
        private val prefix: String,
        /**
         * True when the last non-whitespace chunk ended with a shielded *address*
         * span, so callers know not to staple a terminal period onto a URL.
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
            val shieldWholeChunk = core.isEmpty() &&
                chunk.any { it !in TOKENIZER_SAFE_PUNCT }
            when {
                shieldWholeChunk -> {
                    out.append(prefix).append(spans.size).append(PLACEHOLDER_TAIL)
                    spans.add(chunk)
                    endsWithSpan = false
                }
                core.isNotEmpty() && needsShield(core) -> {
                    out.append(chunk, 0, coreStart)
                    out.append(prefix).append(spans.size).append(PLACEHOLDER_TAIL)
                    spans.add(core)
                    out.append(chunk, coreEnd, chunk.length)
                    // A trailing "." that was peeled off is real punctuation and
                    // stays in the prose stream, so only a chunk that *ends* in
                    // its span can suppress terminal punctuation — and then only
                    // when the span is an address, where a stapled-on full stop
                    // would be copied along with the link.
                    endsWithSpan = coreEnd == chunk.length && looksLikeAddress(core)
                }
                else -> {
                    out.append(chunk)
                    endsWithSpan = false
                }
            }
            i = end
        }
        return Shield(out.toString(), spans, prefix, endsWithSpan)
    }

    /** URLs, email addresses and paths — things a trailing period would spoil. */
    fun looksLikeAddress(core: String): Boolean =
        core.contains("://") ||
            core.startsWith("www.", ignoreCase = true) ||
            core.contains('/') ||
            (core.contains('@') && core.contains('.'))

    /** True when the cleaner must not be allowed to touch [core]. */
    fun needsShield(core: String): Boolean {
        if (core.isEmpty()) return false
        // A hyphen is word-internal only in the middle. On either edge it marks a
        // command-line flag, a markdown bullet or a dashed fragment, all of which
        // the tokenizer emits as bare punctuation and the renderer then spaces out
        // ("-m" -> "- m"). The letter-or-digit clause is load-bearing: a lone "-"
        // bullet has to stay in the prose stream, or the word after it is no longer
        // at a sentence start and never gets capitalized.
        if ((core.first() == '-' || core.last() == '-') && core.any { it.isLetterOrDigit() }) return true
        var upperInside = false
        for (index in core.indices) {
            val ch = core[index]
            when {
                ch.isDigit() -> return true
                ch.isLetter() -> if (index > 0 && ch.isUpperCase()) upperInside = true
                ch == '\'' || ch == '-' -> Unit
                // A mark on a letter is only the NFD spelling of an accented word,
                // which the cleaner handles fine; shielding it would make casing
                // depend on which normalization form the text arrived in. A mark
                // anywhere else — leading, or after an emoji, where U+FE0F is
                // itself Mn — is real content and still has to be shielded.
                isMark(ch) && index > 0 && core[index - 1].isLetter() -> Unit
                else -> return true
            }
        }
        return upperInside
    }

    private fun isMark(ch: Char): Boolean = when (Character.getType(ch)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
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
