package com.vboard.core.text

/** User-facing knobs for the cleanup pipeline; persisted by the app's settings. */
data class CleanupOptions(
    val removeFillers: Boolean = true,
    /** Also strips discourse fillers ("you know", "basically") beyond pure hesitations. */
    val aggressiveFillers: Boolean = false,
    val resolveSelfCorrections: Boolean = true,
    val collapseRepetitions: Boolean = true,
    val autoPunctuate: Boolean = true,
    val autoCapitalize: Boolean = true,
    /** Spoken punctuation/navigation: "new line", "comma", "question mark", ... */
    val spokenCommands: Boolean = true,
    /**
     * Raw transcript escape hatch: bypasses every transformation EXCEPT spoken
     * commands and whole-utterance commands, which must keep working so the
     * user can still say "stop listening".
     */
    val rawMode: Boolean = false,
) {
    companion object {
        val RAW = CleanupOptions(
            removeFillers = false,
            aggressiveFillers = false,
            resolveSelfCorrections = false,
            collapseRepetitions = false,
            autoPunctuate = false,
            autoCapitalize = false,
            spokenCommands = true,
            rawMode = true,
        )
    }
}

/** Commands that consume the whole utterance instead of producing text. */
enum class UtteranceCommand {
    NONE,

    /** "scratch that" / "delete that" / "undo that": remove the last committed utterance. */
    SCRATCH_THAT,

    /** "stop listening" / "stop dictation": end the dictation session. */
    STOP_LISTENING,
}

data class CleanupRequest(
    val transcript: String,
    /** Text already in the field before the insertion point; drives capitalization. */
    val precedingText: String = "",
    val fieldKind: FieldKind = FieldKind.TEXT,
    val options: CleanupOptions = CleanupOptions(),
    /**
     * When true and the field is free-form TEXT, a missing terminal "." is
     * appended. The app sets this for finalized utterances only, never for
     * live partials or SEARCH fields.
     */
    val ensureTerminalPunctuation: Boolean = false,
)

/**
 * The outcome of one cleanup pass.
 *
 * [toString] is hand-written to exclude [text] and everything derived from it,
 * including its length. This is a data class for its `copy`/destructuring, and a
 * generated `toString` would print the whole cleaned transcript the first time
 * anybody logged a result — so the override is the guard, not a nicety.
 * `CleanupRedactionTest` guards this.
 */
data class CleanupResult(
    val text: String,
    val command: UtteranceCommand = UtteranceCommand.NONE,
    val fillersRemoved: Int = 0,
    val correctionsResolved: Int = 0,
    val repetitionsCollapsed: Int = 0,
    /**
     * Spoken punctuation words replaced by their symbol ("comma" -> ","). Stage 3
     * deletes a word the user said, so it needs a disclosure counter of its own;
     * it counts substitutions only and never records which ones.
     */
    val spokenSubstitutions: Int = 0,
) {
    override fun toString(): String =
        "CleanupResult(text=<redacted>, command=$command, fillersRemoved=$fillersRemoved, " +
            "correctionsResolved=$correctionsResolved, repetitionsCollapsed=$repetitionsCollapsed, " +
            "spokenSubstitutions=$spokenSubstitutions)"
}
