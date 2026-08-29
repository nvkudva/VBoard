package com.vboard.core.text

/**
 * Undoes the all-caps output some ASR models produce.
 *
 * The streaming Zipformer decodes against an uppercase token table, so its
 * partials arrive as "SEND HIM THE FILE" while the final Parakeet pass returns
 * ordinary case. Left alone, that shouts at the user in the voice bar's live
 * transcript — and reaches the field itself whenever the final pass fails and
 * the partial is committed instead ([com.vboard.core.session.FinalTranscriptPolicy]).
 *
 * The rule is deliberately narrow: only text with no lowercase letter at all is
 * touched, so a model that already returns mixed case is passed through
 * untouched and nothing the user actually typed is ever re-cased here. Casing
 * that matters — sentence starts, standalone "I" — is put back by
 * [TranscriptCleaner]; an acronym inside an all-caps utterance cannot be told
 * apart from the rest of it and comes out lowercase.
 */
object RecognizerCase {

    fun normalize(text: String): String {
        var sawLetter = false
        for (ch in text) {
            if (ch.isLowerCase()) return text
            if (ch.isLetter()) sawLetter = true
        }
        return if (sawLetter) text.lowercase() else text
    }
}
