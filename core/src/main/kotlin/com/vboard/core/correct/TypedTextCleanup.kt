package com.vboard.core.correct

import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.FieldKind
import com.vboard.core.text.TranscriptCleaner
import com.vboard.core.text.UtteranceCommand

/**
 * The deterministic half of "AI fix": the Tier-1 rules engine, retuned for text
 * somebody typed rather than text somebody said.
 *
 * Three of the cleaner's stages are speech behaviours and are switched **off**
 * here, because on typed input they delete words the user chose deliberately:
 *
 *  - **Filler removal.** "Um" in a typed message is a written word ("um, sure?"),
 *    not a hesitation the microphone caught.
 *  - **Self-correction resolution.** A typed "I mean" or "scratch that" is
 *    content; the speech rule would drop the clause in front of it.
 *  - **Spoken commands.** A typed "period" or "new line" must stay the word it
 *    is, not become punctuation.
 *
 * What stays on is what typed text actually gets wrong: duplicated words,
 * sentence casing, standalone "i", run-on spacing and missing terminal
 * punctuation.
 *
 * Text is processed one line at a time so blank lines and indentation survive,
 * and each line is [ContentGuard]-shielded first so URLs, addresses, numbers,
 * code and emoji reach the other side byte-for-byte.
 */
object TypedTextCleanup {

    val OPTIONS = CleanupOptions(
        removeFillers = false,
        aggressiveFillers = false,
        resolveSelfCorrections = false,
        collapseRepetitions = true,
        autoPunctuate = true,
        autoCapitalize = true,
        spokenCommands = false,
        rawMode = false,
    )

    /** Returns [text] with the typed-text rules applied. Pure and idempotent. */
    fun clean(
        text: String,
        fieldKind: FieldKind = FieldKind.TEXT,
        cleaner: TranscriptCleaner = TranscriptCleaner(),
    ): String {
        if (text.isBlank()) return text
        val lines = text.split('\n')
        val lastContentLine = lines.indexOfLast { it.isNotBlank() }
        val out = ArrayList<String>(lines.size)
        for ((index, line) in lines.withIndex()) {
            if (line.isBlank()) {
                out.add(line)
                continue
            }
            val lead = line.takeWhile { it == ' ' || it == '\t' }
            val afterLead = line.substring(lead.length)
            val trail = afterLead.takeLastWhile { it == ' ' || it == '\t' }
            val body = afterLead.substring(0, afterLead.length - trail.length)
            val shield = ContentGuard.shield(body)
            val result = cleaner.clean(
                CleanupRequest(
                    transcript = shield.masked,
                    // Every line of a typed message starts a sentence as far as
                    // casing is concerned; "" is what makes the cleaner treat it
                    // that way (see TranscriptCleaner.sentenceStartsAt).
                    precedingText = "",
                    fieldKind = fieldKind,
                    options = OPTIONS,
                    // Only the final line of the message gets a terminal period,
                    // and never when it ends in a shielded span — nobody wants a
                    // full stop welded onto the end of a URL.
                    ensureTerminalPunctuation = index == lastContentLine &&
                        !shield.endsWithShieldedSpan,
                ),
            )
            // Stage 2 of the cleaner is not gated by CleanupOptions: a line that
            // reads exactly "scratch that" comes back as a command with empty
            // text. Typed, that is a sentence somebody wrote, so the line is
            // kept as it is rather than deleted.
            if (result.command != UtteranceCommand.NONE) {
                out.add(line)
                continue
            }
            out.add(lead + shield.restore(result.text) + trail)
        }
        return out.joinToString("\n")
    }
}
