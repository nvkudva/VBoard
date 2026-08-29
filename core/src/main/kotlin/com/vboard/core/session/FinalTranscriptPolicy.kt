package com.vboard.core.session

/**
 * Which text a finalized utterance commits.
 *
 * The final pass (Parakeet TDT) can come back with nothing: it timed out, it
 * threw, or the engines were released underneath it. None of those are a valid
 * transcription of speech the user just watched the streaming partial spell out,
 * so all of them fall back to that partial. Committing the empty string instead
 * silently deletes a sentence the user saw on screen — a data-loss bug that
 * looks, from the outside, like the keyboard ignoring them.
 *
 * Pure, so the rule is testable without an ASR engine or an Android runtime.
 */
object FinalTranscriptPolicy {

    /**
     * @param finalPassText what the final pass produced: null when it failed,
     *   timed out, or had no engine to run on.
     * @param streamingPartial the last streaming partial for the utterance.
     */
    fun choose(finalPassText: String?, streamingPartial: String): String =
        if (finalPassText.isNullOrBlank()) streamingPartial else finalPassText
}
