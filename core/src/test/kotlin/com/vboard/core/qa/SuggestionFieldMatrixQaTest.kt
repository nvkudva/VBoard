package com.vboard.core.qa

import com.vboard.core.suggest.AutocorrectMode
import com.vboard.core.suggest.Lexicon
import com.vboard.core.suggest.Suggestion
import com.vboard.core.suggest.SuggestionEngine
import com.vboard.core.suggest.SuggestionRequest
import com.vboard.core.suggest.UserHistory
import com.vboard.core.text.FieldKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The suggestion engine against the **full cross product** of field kind and
 * autocorrect mode, plus the Unicode inputs a real keyboard receives.
 *
 * `SuggestionEngineQaTest` covers the important individual rules (no autocorrect
 * of lexicon words, casing gates, per-field emptiness). What it does not do is
 * walk the matrix — and the matrix is where an IME's field-gating bugs live,
 * because the gate is spread across `FieldKind` properties, an early return in
 * `suggest`, and a second check inside `decideAutocorrect`.
 *
 * The privacy-relevant cell is PASSWORD: the spec requires "voice input,
 * suggestions, and any learning MUST be disabled entirely".
 */
class SuggestionFieldMatrixQaTest {

    private val lexicon = Lexicon.english()

    private fun engine(history: UserHistory = UserHistory()) = SuggestionEngine(lexicon, history)

    private fun suggest(
        composing: String,
        previous: String? = null,
        kind: FieldKind = FieldKind.TEXT,
        mode: AutocorrectMode = AutocorrectMode.CONSERVATIVE,
        engine: SuggestionEngine = engine(),
    ) = engine.suggest(SuggestionRequest(composing, previous, kind, mode))

    private val composingSamples = listOf(
        "", "a", "teh", "hte", "hello", "i", "I", "HELLO", "iPhone", "don't", "dont",
        "'tis", "x", "zzzz", "thequickbrown", "a".repeat(64),
    )

    // ------------------------------------------------------------------ the matrix

    @Test
    fun `PASSWORD and NUMBER fields are inert in every mode, for every input`() {
        for (kind in listOf(FieldKind.PASSWORD, FieldKind.NUMBER)) {
            for (mode in AutocorrectMode.entries) {
                for (composing in composingSamples) {
                    for (previous in listOf(null, "the", "hello")) {
                        val result = suggest(composing, previous, kind, mode)
                        assertEquals(
                            emptyList(), result.suggestions,
                            "$kind/$mode offered suggestions for <$composing>",
                        )
                        assertEquals(null, result.autocorrect, "$kind/$mode autocorrected <$composing>")
                    }
                }
            }
        }
    }

    @Test
    fun `EMAIL and URI fields echo the literal and never rewrite it, in every mode`() {
        for (kind in listOf(FieldKind.EMAIL, FieldKind.URI)) {
            for (mode in AutocorrectMode.entries) {
                assertEquals(emptyList(), suggest("", null, kind, mode).suggestions, "$kind/$mode predicted")
                for (composing in composingSamples.filter { it.isNotEmpty() }) {
                    val result = suggest(composing, "the", kind, mode)
                    assertEquals(1, result.suggestions.size, "$kind/$mode for <$composing>")
                    assertEquals(composing, result.suggestions.single().text, "$kind/$mode rewrote <$composing>")
                    assertEquals(
                        Suggestion.Source.LITERAL, result.suggestions.single().source,
                        "$kind/$mode did not mark the echo as LITERAL",
                    )
                    assertEquals(null, result.autocorrect, "$kind/$mode autocorrected <$composing>")
                }
            }
        }
    }

    @Test
    fun `TEXT and SEARCH behave identically except where the field kinds differ`() {
        for (mode in AutocorrectMode.entries) {
            @Suppress("LoopWithTooManyJumpStatements")
            for (composing in composingSamples) {
                val text = suggest(composing, "the", FieldKind.TEXT, mode)
                val search = suggest(composing, "the", FieldKind.SEARCH, mode)
                // The single documented divergence: lone "i" -> "I" is TEXT-only
                // (SuggestionEngine.kt:229), and that autocorrect is then forced
                // into the TEXT strip, so both halves of the result differ.
                if (composing == "i" && mode != AutocorrectMode.OFF) {
                    assertEquals("I", text.autocorrect?.text, "TEXT should capitalize lone i in $mode")
                    assertEquals(null, search.autocorrect, "SEARCH should not capitalize lone i")
                    assertEquals(listOf("I", "i", "it"), text.suggestions.map { it.text })
                    assertEquals(listOf("i", "it", "in"), search.suggestions.map { it.text })
                    continue
                }
                assertEquals(
                    text.suggestions.map { it.text }, search.suggestions.map { it.text },
                    "TEXT and SEARCH disagreed on the strip for <$composing> in $mode",
                )
                assertEquals(
                    text.autocorrect?.text, search.autocorrect?.text,
                    "TEXT and SEARCH disagreed on autocorrect for <$composing> in $mode",
                )
            }
        }
    }

    @Test
    fun `OFF mode never autocorrects but still suggests`() {
        for (kind in FieldKind.entries) {
            for (composing in composingSamples.filter { it.isNotEmpty() }) {
                assertEquals(
                    null, suggest(composing, "the", kind, AutocorrectMode.OFF).autocorrect,
                    "OFF autocorrected <$composing> in $kind",
                )
            }
        }
        assertTrue(suggest("teh", "the", FieldKind.TEXT, AutocorrectMode.OFF).suggestions.isNotEmpty())
    }

    @Test
    fun `AGGRESSIVE is a superset of CONSERVATIVE, never a different answer`() {
        // A mode that is only "more willing" must not change its mind about what
        // the correction is; that would make the setting unpredictable.
        for (composing in composingSamples.filter { it.isNotEmpty() }) {
            val conservative = suggest(composing, "the", FieldKind.TEXT, AutocorrectMode.CONSERVATIVE).autocorrect
            val aggressive = suggest(composing, "the", FieldKind.TEXT, AutocorrectMode.AGGRESSIVE).autocorrect
            if (conservative != null) {
                assertEquals(
                    conservative.text, aggressive?.text,
                    "AGGRESSIVE disagreed with CONSERVATIVE for <$composing>",
                )
            }
        }
    }

    // ---------------------------------------------------------- structural invariants

    @Test
    fun `the strip never exceeds three entries and never repeats one`() {
        val random = Random(20260829)
        val alphabet = "abcdefghijklmnopqrstuvwxyzABC'".toList()
        repeat(6_000) {
            val composing = (0 until random.nextInt(0, 12)).map { alphabet.random(random) }.joinToString("")
            val kind = FieldKind.entries.random(random)
            val mode = AutocorrectMode.entries.random(random)
            val previous = listOf(null, "the", "i", "hello", "zzz").random(random)
            val result = assertDoesNotThrow("threw for <$composing> $kind/$mode") {
                suggest(composing, previous, kind, mode)
            }
            assertTrue(result.suggestions.size <= 3, "${result.suggestions.size} suggestions for <$composing>")
            assertEquals(
                result.suggestions.map { it.text }.distinct().size, result.suggestions.size,
                "duplicate suggestion for <$composing>: ${result.suggestions.map { it.text }}",
            )
            assertTrue(
                result.suggestions.none { it.text.isEmpty() },
                "empty suggestion for <$composing>",
            )
        }
    }

    @Test
    fun `the typed literal is always reachable in the strip (VB-306, VB-QA-09)`() {
        val random = Random(31337)
        val alphabet = "abcdefghijklmnopqrstuvwxyz".toList()
        repeat(6_000) {
            val composing = (1..random.nextInt(1, 9)).map { alphabet.random(random) }.joinToString("")
            for (kind in listOf(FieldKind.TEXT, FieldKind.SEARCH)) {
                val result = suggest(composing, "the", kind, AutocorrectMode.AGGRESSIVE)
                assertTrue(
                    result.suggestions.any { it.text.equals(composing, ignoreCase = true) },
                    "literal <$composing> is unreachable in $kind: ${result.suggestions.map { it.text }}",
                )
            }
        }
    }

    @Test
    fun `an autocorrect choice is always visible in the strip`() {
        // Committing a replacement the user was never shown is the interaction
        // Gboard is most criticised for; the engine already tries to guarantee
        // this (SuggestionEngine.kt:181) and it should stay guaranteed.
        val random = Random(112233)
        val alphabet = "abcdefghijklmnopqrstuvwxyz".toList()
        repeat(6_000) {
            val composing = (1..random.nextInt(2, 9)).map { alphabet.random(random) }.joinToString("")
            val result = suggest(composing, "the", FieldKind.TEXT, AutocorrectMode.AGGRESSIVE)
            val autocorrect = result.autocorrect ?: return@repeat
            assertTrue(
                result.suggestions.any { it.text == autocorrect.text },
                "autocorrect <${autocorrect.text}> for <$composing> is not in the strip",
            )
        }
    }

    // ------------------------------------------------------------------- learning

    @Test
    fun `learning is the caller's gate, and the engine says so`() {
        // recordCommittedWord does NOT check FieldKind — its doc says the caller
        // must. This is a real trap for the app layer (PASSWORD fields), so it is
        // worth an explicit standing assertion rather than a doc comment.
        val history = UserHistory()
        val engine = engine(history)
        engine.recordCommittedWord(null, "hunter2word")
        assertTrue(history.unigramCount("hunter2word") == 0, "digits should not be learned")
        engine.recordCommittedWord(null, "correcthorse")
        assertTrue(
            history.unigramCount("correcthorse") > 0,
            "the engine learned nothing, so this test proves nothing about the gate",
        )
    }

    @Test
    fun `learning ignores tokens that are not correctable words`() {
        val history = UserHistory()
        val engine = engine(history)
        for (word in listOf("", "   ", "123", "a1", "e=mc2", "👋", "a".repeat(49), "'", "-", "...")) {
            engine.recordCommittedWord("the", word)
        }
        assertEquals(0, history.continuationsOf("the").size, "a non-word was learned as a bigram")
    }

    // ----------------------------------------------------- Unicode composing text

    // ------------- VB-QA-32: accented words are not autocorrected into unrelated words

    @Test
    fun `text in a non-Latin script is never rewritten`() {
        // Where the whole token is outside the lexicon's alphabet, the fuzzy
        // matcher finds nothing and the literal is echoed. This half works.
        for (composing in listOf("日本語", "привет", "مرحبا", "ok👍", "🎉", "Việt", "İstanbul")) {
            for (mode in listOf(AutocorrectMode.CONSERVATIVE, AutocorrectMode.AGGRESSIVE)) {
                val result = suggest(composing, "the", FieldKind.TEXT, mode)
                assertEquals(null, result.autocorrect, "$mode autocorrected <$composing>")
                assertTrue(
                    result.suggestions.any { it.text == composing },
                    "<$composing> is not in its own strip: ${result.suggestions.map { it.text }}",
                )
            }
        }
    }

    @Test
    fun `an accented Latin word is never autocorrected into an unrelated word (pinned)`() {
        // The lexicon trie is a-z only, and the weighted edit distance used to
        // treat "è" as an ordinary character one substitution away from "i" — so
        // "crime" scored its full log-frequency against a literal worth only
        // LITERAL_PRIOR (2.0), cleared the margin, and was committed on the next
        // space. A letter the lexicon cannot spell now takes the token out of the
        // fuzzy matcher entirely, in every mode.
        val creme = suggest("crème", null, FieldKind.TEXT, AutocorrectMode.CONSERVATIVE)
        assertEquals(null, creme.autocorrect)
        val elan = suggest("élan", null, FieldKind.TEXT, AutocorrectMode.CONSERVATIVE)
        assertEquals(null, elan.autocorrect)
        // Two edits away, so this one used to need AGGRESSIVE; now neither fires.
        assertEquals(null, suggest("naïve", null, FieldKind.TEXT, AutocorrectMode.CONSERVATIVE).autocorrect)
        assertEquals(null, suggest("naïve", null, FieldKind.TEXT, AutocorrectMode.AGGRESSIVE).autocorrect)

        // Words that were never one edit from anything frequent are unaffected,
        // which is how this stays a pin on the rule rather than on the outcome.
        for (safe in listOf("café", "résumé", "Straße", "über", "señor", "façade", "jalapeño")) {
            assertEquals(null, suggest(safe, null, FieldKind.TEXT, AutocorrectMode.AGGRESSIVE).autocorrect, "for <$safe>")
        }
    }

    @Test
    fun `an accented Latin word should never be autocorrected into an unrelated word`() {
        for (word in listOf("crème", "élan", "naïve", "café", "résumé", "Müller")) {
            for (mode in listOf(AutocorrectMode.CONSERVATIVE, AutocorrectMode.AGGRESSIVE)) {
                assertEquals(null, suggest(word, null, FieldKind.TEXT, mode).autocorrect, "$mode rewrote <$word>")
            }
        }
    }

    @Test
    fun `a correctly spelled accented word leads its own strip (pinned)`() {
        // The milder half of the same defect: even where autocorrect declined, the
        // strip led with an unrelated ASCII word, so a one-tap mistake committed it.
        // With no fuzzy candidates the literal is the only thing to rank.
        assertEquals("naïve", suggest("naïve", null, FieldKind.TEXT).suggestions.first().text)
        assertEquals("Müller", suggest("Müller", null, FieldKind.TEXT).suggestions.first().text)
        // A single accented character too, which the enabled test's list does not
        // reach: "à" used to lose its own strip to the pronoun rule's "I".
        assertEquals("à", suggest("à", null, FieldKind.TEXT).suggestions.first().text)
    }

    @Test
    fun `the accented-word rule silences the trie, not the words the user taught`() {
        // VB-QA-32's suppression belongs to the a-z trie, whose edit distance
        // cannot measure "è". The user's own history has no such limit — it learns
        // accented words and predicts them — so a rule scoped to the whole engine
        // would cost a French or Turkish user their own vocabulary.
        val history = UserHistory()
        val engine = engine(history)
        engine.recordCommittedWord("crème", "brûlée")
        assertTrue(history.unigramCount("brûlée") > 0, "an accented word was not learned")
        assertEquals(
            "brûlée",
            suggest("", "crème", FieldKind.TEXT, engine = engine).suggestions.first().text,
            "a learned accented word was not predicted",
        )
    }

    @Test
    fun `a correctly spelled word should lead its own suggestion strip`() {
        for (word in listOf("naïve", "Müller", "café", "résumé")) {
            val result = suggest(word, null, FieldKind.TEXT, AutocorrectMode.CONSERVATIVE)
            assertEquals(word, result.suggestions.first().text, "strip did not lead with <$word>")
        }
    }

    @Test
    fun `composing text is trimmed, so surrounding space cannot change the answer`() {
        for (padded in listOf(" teh", "teh ", "  teh  ", "\tteh\t")) {
            assertEquals(
                suggest("teh", "the").autocorrect?.text,
                suggest(padded, "the").autocorrect?.text,
                "padding changed the answer for <$padded>",
            )
        }
    }
}
