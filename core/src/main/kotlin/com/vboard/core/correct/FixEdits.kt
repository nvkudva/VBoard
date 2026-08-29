package com.vboard.core.correct

/**
 * Who made a change, which is the only thing the user needs to know about it.
 *
 * The split is free: it falls straight out of which tier produced the edit, from
 * a diff of the rules output against the final text. It matters because the two
 * classes deserve different amounts of ceremony — nobody wants to be asked about
 * a capital letter, and everybody wants to be told when a word was swapped.
 */
enum class EditKind {
    /**
     * The deterministic rules: casing, a doubled word, run-on spacing, a missing
     * full stop. Predictable, reversible by eye, and applied with no ceremony.
     */
    MECHANICAL,

    /**
     * The model: a substituted word, a reworded clause. Applied, but attributed,
     * and revertible on its own without throwing away the rest of the fix.
     */
    EDITORIAL,
}

/**
 * One contiguous change, addressed against the **corrected** text so the UI can
 * point at it, and carrying what it replaced so it can be taken back alone.
 *
 * Not a data class, and offsets stay out of `toString()`: both the text and its
 * position are things this codebase does not log (VB-238).
 */
class FixEdit internal constructor(
    val kind: EditKind,
    /** Start offset in the corrected text, inclusive. */
    val start: Int,
    /** End offset in the corrected text, exclusive. */
    val end: Int,
    private val before: String,
    private val after: String,
) {
    fun beforeText(): String = before

    fun afterText(): String = after

    override fun toString(): String = "FixEdit(kind=$kind)"
}

/**
 * Per-change revert (the other half of undo).
 *
 * Whole-field undo is the safety net; this is the scalpel. Both refuse to act on
 * a field that has moved under them: an edit whose span no longer reads the way
 * it was written is stale, and applying it anyway would corrupt text the user
 * has since typed.
 */
object FixEdits {

    /**
     * Reverses [edit] in [currentText], or returns null when the span no longer
     * matches and the revert cannot be trusted.
     */
    fun revert(currentText: String, edit: FixEdit): String? {
        val after = edit.afterText()
        if (edit.start < 0 || edit.end > currentText.length || edit.start > edit.end) return null
        if (edit.end - edit.start != after.length) return null
        if (!currentText.regionMatches(edit.start, after, 0, after.length)) return null
        return currentText.substring(0, edit.start) + edit.beforeText() +
            currentText.substring(edit.end)
    }

    /**
     * Moves the remaining [edits] to where they now live after [reverted] was
     * applied. Edits that overlap the reverted span are dropped — their spans no
     * longer describe anything.
     */
    fun rebase(edits: List<FixEdit>, reverted: FixEdit): List<FixEdit> {
        val delta = reverted.beforeText().length - reverted.afterText().length
        return edits.mapNotNull { edit ->
            when {
                edit === reverted -> null
                edit.end <= reverted.start -> edit
                edit.start >= reverted.end -> FixEdit(
                    kind = edit.kind,
                    start = edit.start + delta,
                    end = edit.end + delta,
                    before = edit.beforeText(),
                    after = edit.afterText(),
                )
                else -> null
            }
        }
    }
}

/**
 * A word-level diff, used to say what a fix actually did.
 *
 * Text is split into atoms — runs of whitespace and runs of non-whitespace, so
 * the atoms concatenate back to the original — then a common prefix and suffix
 * are trimmed and the remainder is aligned by longest common subsequence.
 * Aligning on atoms rather than characters is what makes a change read as "this
 * word became that word" instead of "these three letters moved".
 *
 * The LCS table is quadratic, so a middle section past [MAX_DIFF_ATOMS] is
 * reported as one coarse change rather than allocating a 16-million-cell table
 * for a 20,000-character field.
 */
internal object TextDiffer {

    private const val MAX_DIFF_ATOMS = 400

    /** A contiguous divergence, in character offsets on each side. */
    class Run(
        val beforeStart: Int,
        val beforeEnd: Int,
        val afterStart: Int,
        val afterEnd: Int,
    )

    fun runs(before: String, after: String): List<Run> {
        if (before == after) return emptyList()
        val beforeAtoms = atoms(before)
        val afterAtoms = atoms(after)

        var head = 0
        val minSize = minOf(beforeAtoms.size, afterAtoms.size)
        while (head < minSize && beforeAtoms[head].text == afterAtoms[head].text) head++
        var tail = 0
        while (tail < minSize - head &&
            beforeAtoms[beforeAtoms.size - 1 - tail].text == afterAtoms[afterAtoms.size - 1 - tail].text
        ) {
            tail++
        }

        val beforeMiddle = beforeAtoms.subList(head, beforeAtoms.size - tail)
        val afterMiddle = afterAtoms.subList(head, afterAtoms.size - tail)
        if (beforeMiddle.isEmpty() && afterMiddle.isEmpty()) return emptyList()

        val beforeOffset = offsetAt(beforeAtoms, head, before.length)
        val afterOffset = offsetAt(afterAtoms, head, after.length)
        val beforeEnd = endOffsetAt(beforeAtoms, beforeAtoms.size - tail, before.length)
        val afterEnd = endOffsetAt(afterAtoms, afterAtoms.size - tail, after.length)

        if (beforeMiddle.size > MAX_DIFF_ATOMS || afterMiddle.size > MAX_DIFF_ATOMS) {
            return listOf(Run(beforeOffset, beforeEnd, afterOffset, afterEnd))
        }
        return align(beforeMiddle, afterMiddle, beforeOffset, afterOffset, before, after)
    }

    private fun align(
        beforeMiddle: List<Atom>,
        afterMiddle: List<Atom>,
        beforeFallback: Int,
        afterFallback: Int,
        before: String,
        after: String,
    ): List<Run> {
        val n = beforeMiddle.size
        val m = afterMiddle.size
        // lcs[i][j] = length of the longest common subsequence of the suffixes.
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (beforeMiddle[i].text == afterMiddle[j].text) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }

        val out = mutableListOf<Run>()
        var i = 0
        var j = 0
        var runBeforeStart = -1
        var runAfterStart = -1

        fun atomStart(atoms: List<Atom>, index: Int, fallbackEnd: Int): Int =
            atoms.getOrNull(index)?.start ?: fallbackEnd

        fun closeRun(beforeEnd: Int, afterEnd: Int) {
            if (runBeforeStart >= 0) {
                out.add(Run(runBeforeStart, beforeEnd, runAfterStart, afterEnd))
                runBeforeStart = -1
                runAfterStart = -1
            }
        }

        while (i < n || j < m) {
            val same = i < n && j < m && beforeMiddle[i].text == afterMiddle[j].text
            if (same) {
                closeRun(beforeMiddle[i].start, afterMiddle[j].start)
                i++
                j++
                continue
            }
            if (runBeforeStart < 0) {
                runBeforeStart = atomStart(beforeMiddle, i, beforeFallback)
                runAfterStart = atomStart(afterMiddle, j, afterFallback)
            }
            val takeBefore = j >= m || (i < n && lcs[i + 1][j] >= lcs[i][j + 1])
            if (takeBefore) i++ else j++
        }
        closeRun(
            beforeMiddle.lastOrNull()?.let { it.start + it.text.length } ?: before.length,
            afterMiddle.lastOrNull()?.let { it.start + it.text.length } ?: after.length,
        )
        return out
    }

    private class Atom(val text: String, val start: Int)

    private fun atoms(text: String): List<Atom> {
        val out = mutableListOf<Atom>()
        var i = 0
        while (i < text.length) {
            val whitespace = text[i].isWhitespace()
            var end = i
            while (end < text.length && text[end].isWhitespace() == whitespace) end++
            out.add(Atom(text.substring(i, end), i))
            i = end
        }
        return out
    }

    private fun offsetAt(atoms: List<Atom>, index: Int, fallback: Int): Int =
        atoms.getOrNull(index)?.start ?: fallback

    private fun endOffsetAt(atoms: List<Atom>, index: Int, fallback: Int): Int =
        atoms.getOrNull(index)?.start ?: fallback
}

/**
 * Turns "here is the old text, here is the new text, and here is what the rules
 * alone would have produced" into an attributed list of changes.
 */
internal object FixAttribution {

    fun attribute(original: String, rules: String, final: String): List<FixEdit> {
        if (original == final) return emptyList()
        // Every pair the rules alone produced. A change in the final text that
        // matches one of these is the rules engine's work; anything else is the
        // model's.
        val mechanicalPairs = TextDiffer.runs(original, rules)
            .map { original.substring(it.beforeStart, it.beforeEnd) to rules.substring(it.afterStart, it.afterEnd) }
            .toHashSet()

        return TextDiffer.runs(original, final).map { run ->
            val before = original.substring(run.beforeStart, run.beforeEnd)
            val after = final.substring(run.afterStart, run.afterEnd)
            FixEdit(
                kind = if (before to after in mechanicalPairs) {
                    EditKind.MECHANICAL
                } else {
                    EditKind.EDITORIAL
                },
                start = run.afterStart,
                end = run.afterEnd,
                before = before,
                after = after,
            )
        }
    }
}
