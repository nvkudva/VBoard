package com.vboard.core.text

/**
 * Decides how cleaned text should be joined onto what is already in the field.
 * The IME calls this right before committing an utterance or an autocorrected word.
 */
object CommitPlanner {

    /**
     * Characters after which no space is needed before new text. A trailing
     * hyphen is here because it is how a hyphenated compound is dictated one
     * half at a time ("hello-" + "world").
     */
    private val OPENERS = setOf('(', '[', '{', '"', '\'', '@', '#', '\n', ' ', '\t', '-')

    /**
     * New text starting with one of these attaches directly to the previous word.
     * The apostrophes are for a suggestion-strip commit of a bare possessive, and
     * the ellipsis and closing curly quote are the typographic forms of members
     * that were already here — a closing quote closes, so it never opens.
     */
    private val CLOSERS = setOf('.', ',', '!', '?', ';', ':', ')', ']', '}', '%', '\n', '\'', '’', '…', '”')

    /** Chars that end a sentence-worthy token without being letters or digits. */
    private val WORD_ENDERS = setOf(')', ']', '}', '"', '\'', '%')

    /**
     * Returns [text] with a leading space added when the field's preceding
     * character requires one ("hello" + "world" -> " world").
     */
    fun joinForInsertion(precedingText: String, text: String): String {
        if (text.isEmpty()) return text
        if (precedingText.isEmpty()) return text
        // Code points, not chars: a currency sign can be astral (U+1ECB0), and the
        // last char of an emoji is a low surrogate that classifies as nothing.
        val prev = precedingText.codePointBefore(precedingText.length)
        val first = text.codePointAt(0)
        if (isOpener(prev)) return text
        if (isCloser(first)) return text
        return " $text"
    }

    private fun isOpener(cp: Int): Boolean =
        isBmp(cp) && cp.toChar() in OPENERS ||
            // By category, not by '$': hard-coding the ASCII sign is the bug this
            // replaced, and "€5" and "₹5" are the same utterance.
            Character.getType(cp) == Character.CURRENCY_SYMBOL.toInt()

    private fun isCloser(cp: Int): Boolean = isBmp(cp) && cp.toChar() in CLOSERS

    private fun isBmp(cp: Int): Boolean = cp < Character.MIN_SUPPLEMENTARY_CODE_POINT

    /**
     * True when a double-space was just typed and should become ". "
     * (only after a word character, mirroring Gboard's double-space period).
     */
    fun doubleSpacePeriodApplies(precedingText: String): Boolean {
        if (precedingText.length < 2) return false
        if (precedingText.last() != ' ') return false
        // Walk back over combining marks to the base, then take the whole code
        // point. An NFD-accented letter ends in a mark and an emoji ends in a low
        // surrogate; both are ordinary ways to end a sentence, and inspecting a
        // single UTF-16 char classifies neither.
        var index = precedingText.length - 1
        while (index > 0) {
            val cp = precedingText.codePointBefore(index)
            if (!isMark(cp)) break
            index -= Character.charCount(cp)
        }
        if (index <= 0) return false
        val base = precedingText.codePointBefore(index)
        if (Character.isLetterOrDigit(base)) return true
        if (isBmp(base) && base.toChar() in WORD_ENDERS) return true
        // Symbols only. Punctuation stays out, so ". " does not double up.
        return when (Character.getType(base)) {
            Character.OTHER_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            -> true
            else -> false
        }
    }

    private fun isMark(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
    }
}

/**
 * Minimal replacement between the currently displayed partial text and its
 * replacement, so live updates only rewrite the changed tail instead of the
 * whole composing region (prevents visual flicker during streaming).
 */
data class Replacement(val keepPrefixLength: Int, val deleteCount: Int, val insertText: String) {
    val isNoop: Boolean get() = deleteCount == 0 && insertText.isEmpty()
}

object TextDiff {
    private const val ZWJ = 0x200D
    private val VARIATION_SELECTORS = 0xFE00..0xFE0F
    private val VARIATION_SELECTORS_SUPPLEMENT = 0xE0100..0xE01EF
    private val EMOJI_MODIFIERS = 0x1F3FB..0x1F3FF
    private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF

    fun replacement(current: String, target: String): Replacement {
        if (current == target) return Replacement(current.length, 0, "")
        var prefix = 0
        val max = minOf(current.length, target.length)
        while (prefix < max && current[prefix] == target[prefix]) prefix++
        // Never split a surrogate pair.
        if (prefix > 0 && current[prefix - 1].isHighSurrogate()) prefix--
        prefix = graphemeStart(current, prefix)
        return Replacement(
            keepPrefixLength = prefix,
            deleteCount = current.length - prefix,
            insertText = target.substring(prefix),
        )
    }

    /**
     * Rounds [at] down to the start of the extended grapheme cluster of [s] it
     * falls inside, so a live partial update rewrites whole glyphs.
     *
     * The rule is spelled out rather than delegated to
     * `BreakIterator.getCharacterInstance`, which implements *legacy* clusters on
     * this toolchain: measured on JDK 17 it splits a flag between its two regional
     * indicators, splits at every ZWJ, and splits before a skin-tone modifier.
     *
     * Erring towards gluing is safe. It only shortens the kept prefix, and a
     * shorter prefix still reconstructs the target exactly.
     */
    private fun graphemeStart(s: String, at: Int): Int {
        var p = at
        while (p > 0 && p < s.length && continuesCluster(s, p)) {
            p -= Character.charCount(s.codePointBefore(p))
        }
        return p
    }

    private fun continuesCluster(s: String, at: Int): Boolean {
        val cp = s.codePointAt(at)
        if (isExtend(cp)) return true
        // Anything directly after a ZWJ belongs to the sequence the ZWJ started.
        if (s.codePointBefore(at) == ZWJ) return true
        if (cp in EMOJI_MODIFIERS) return true
        // A flag is a *pair* of regional indicators, so only the second of an
        // odd-length run continues; the third starts a new flag.
        return cp in REGIONAL_INDICATORS && precedingRegionalIndicators(s, at) % 2 == 1
    }

    private fun precedingRegionalIndicators(s: String, at: Int): Int {
        var count = 0
        var i = at
        while (i > 0) {
            val cp = s.codePointBefore(i)
            if (cp !in REGIONAL_INDICATORS) break
            count++
            i -= Character.charCount(cp)
        }
        return count
    }

    private fun isExtend(cp: Int): Boolean {
        if (cp == ZWJ || cp in VARIATION_SELECTORS || cp in VARIATION_SELECTORS_SUPPLEMENT) return true
        return when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            -> true
            else -> false
        }
    }
}
