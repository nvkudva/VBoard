package com.vboard.core.text

/**
 * Decides how cleaned text should be joined onto what is already in the field.
 * The IME calls this right before committing an utterance or an autocorrected word.
 */
object CommitPlanner {

    /** Characters after which no space is needed before new text. */
    private val OPENERS = setOf('(', '[', '{', '"', '\'', '@', '#', '\n', ' ', '\t')

    /** New text starting with one of these attaches directly to the previous word. */
    private val CLOSERS = setOf('.', ',', '!', '?', ';', ':', ')', ']', '}', '%', '\n')

    /**
     * Returns [text] with a leading space added when the field's preceding
     * character requires one ("hello" + "world" -> " world").
     */
    fun joinForInsertion(precedingText: String, text: String): String {
        if (text.isEmpty()) return text
        if (precedingText.isEmpty()) return text
        val prev = precedingText.last()
        val first = text.first()
        if (prev in OPENERS) return text
        if (first in CLOSERS) return text
        return " $text"
    }

    /**
     * True when a double-space was just typed and should become ". "
     * (only after a word character, mirroring Gboard's double-space period).
     */
    fun doubleSpacePeriodApplies(precedingText: String): Boolean {
        if (precedingText.length < 2) return false
        if (precedingText.last() != ' ') return false
        val beforeSpace = precedingText[precedingText.length - 2]
        return beforeSpace.isLetterOrDigit() ||
            beforeSpace in setOf(')', ']', '}', '"', '\'', '%')
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
    fun replacement(current: String, target: String): Replacement {
        if (current == target) return Replacement(current.length, 0, "")
        var prefix = 0
        val max = minOf(current.length, target.length)
        while (prefix < max && current[prefix] == target[prefix]) prefix++
        // Never split a surrogate pair.
        if (prefix > 0 && current[prefix - 1].isHighSurrogate()) prefix--
        return Replacement(
            keepPrefixLength = prefix,
            deleteCount = current.length - prefix,
            insertText = target.substring(prefix),
        )
    }
}
