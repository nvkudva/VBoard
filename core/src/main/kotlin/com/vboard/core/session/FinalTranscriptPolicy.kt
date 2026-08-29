package com.vboard.core.session

/**
 * Which text a finalized utterance commits.
 *
 * The high-accuracy final pass (Parakeet TDT) is an optional download, not a
 * dependency: a user with only the streaming recognizer installed must still get
 * their words. That makes "no final pass" indistinguishable, here, from "the
 * final pass timed out" or "the final pass returned nothing" — in all three
 * cases the streaming partial the user watched appear is the best transcript we
 * have, and dropping it silently deletes a sentence they saw on screen.
 *
 * Pure, so the rule is testable without an ASR engine or an Android runtime.
 */
object FinalTranscriptPolicy {

    /**
     * @param finalPassText what the final pass produced: null when the model is
     *   absent, failed or timed out.
     * @param streamingPartial the last streaming partial for the utterance.
     */
    fun choose(finalPassText: String?, streamingPartial: String): String =
        if (finalPassText.isNullOrBlank()) streamingPartial else finalPassText
}
