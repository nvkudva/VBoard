package com.vboard.core.qa

import com.vboard.core.suggest.AutocorrectMode
import com.vboard.core.suggest.Lexicon
import com.vboard.core.suggest.SuggestionEngine
import com.vboard.core.suggest.SuggestionRequest
import com.vboard.core.suggest.SuggestionResult
import com.vboard.core.text.FieldKind
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial autocorrect QA (VB-305/VB-306, VB-702..705, risk R5): the engine
 * must NEVER corrupt what the user meant. These tests attack the gating rules
 * with valid words, deliberate casing, apostrophes, short tokens, and
 * restricted field kinds.
 */
class SuggestionEngineQaTest {

    private val english = Lexicon.english()
    private val engine = SuggestionEngine(english)

    private fun request(
        composing: String,
        previousWord: String? = null,
        fieldKind: FieldKind = FieldKind.TEXT,
        mode: AutocorrectMode = AutocorrectMode.CONSERVATIVE,
    ) = SuggestionRequest(composing, previousWord, fieldKind, mode)

    private fun autocorrectOf(composing: String, mode: AutocorrectMode = AutocorrectMode.CONSERVATIVE): String? =
        engine.suggest(request(composing, mode = mode)).autocorrect?.text

    /**
     * Apostrophe-dropped forms the engine DELIBERATELY rewrites even though some
     * are themselves dictionary words (mirrors SuggestionEngine.CONTRACTIONS;
     * the "id" -> "I'd" / "cant" -> "can't" trade-off is a documented product
     * decision, noted in docs/QA_REPORT.md).
     */
    private val contractionKeys = setOf(
        "dont", "im", "cant", "wont", "id", "ive", "isnt", "didnt", "doesnt",
        "wasnt", "werent", "arent", "aint", "couldnt", "wouldnt", "shouldnt",
        "havent", "hasnt", "hadnt", "youre", "youve", "youll", "youd", "theyre",
        "theyve", "theyll", "weve", "thats", "whats", "theres", "heres",
        "wheres", "whos", "hes", "shes", "yall", "oclock", "wouldve", "couldve",
        "shouldve",
    )

    /** Deterministic sample of lexicon words spread across the alphabet. */
    private fun sampleLexiconWords(count: Int, seed: Long): List<String> {
        val pool = mutableListOf<String>()
        for (c in 'a'..'z') {
            english.wordsWithPrefix(c.toString(), 200).mapTo(pool) { it.word }
        }
        return pool
            .filter { it !in contractionKeys && it != "i" }
            .shuffled(Random(seed))
            .take(count)
    }

    // ------------------------------------------------------------ valid words are sacred

    @Test
    fun `100 sampled lexicon words are never autocorrected`() {
        val sample = sampleLexiconWords(100, seed = 7)
        assertEquals(100, sample.size)
        for (word in sample) {
            val result = engine.suggest(request(word))
            assertNull(result.autocorrect, "valid word <$word> would be autocorrected to <${result.autocorrect?.text}>")
        }
    }

    @Test
    fun `short valid words stay reachable in the suggestion strip`() {
        // VB-QA-09 fixed: the typed literal is forced into the strip (left slot)
        // whenever ranking would have dropped it.
        for (word in listOf("aw", "ad", "bo")) {
            val texts = engine.suggest(request(word)).suggestions.map { it.text.lowercase() }
            assertTrue(word in texts, "literal <$word> missing from strip $texts")
        }
    }

    @Test
    fun `sampled lexicon words are never autocorrected in aggressive mode either`() {
        for (word in sampleLexiconWords(60, seed = 13)) {
            val result = engine.suggest(request(word, mode = AutocorrectMode.AGGRESSIVE))
            assertNull(result.autocorrect, "valid word <$word> autocorrected in AGGRESSIVE mode")
        }
    }

    // ------------------------------------------------------------ deliberate casing

    @Test
    fun `all-caps tokens are never autocorrected`() {
        for (word in listOf("TEH", "HELO", "ASAP", "NASA", "IDK", "OK", "AB", "RECIEVE")) {
            assertNull(autocorrectOf(word), "ALL-CAPS <$word> must be untouchable")
        }
    }

    @Test
    fun `camelcase brand-like tokens with no close frequent neighbor are untouched`() {
        for (word in listOf("McDonald", "JavaScript", "LaTeX", "eBay")) {
            assertNull(autocorrectOf(word), "CamelCase <$word> must be untouchable")
        }
    }

    @Test
    fun `camelcase tokens near a frequent word are also untouched`() {
        // VB-QA-06 fixed: internal capitals gate autocorrect like ALL-CAPS does.
        for (word in listOf("iPhone", "iOS", "VBoard")) {
            assertNull(autocorrectOf(word), "CamelCase <$word> must be untouchable")
        }
    }

    // ------------------------------------------------------------ apostrophes & odd tokens

    @Test
    fun `words with apostrophes are never autocorrected`() {
        for (word in listOf("don't", "o'clock", "y'all", "rock'n'roll", "can't", "shouldn't've")) {
            assertNull(autocorrectOf(word), "apostrophe word <$word> must be untouchable")
        }
    }

    @Test
    fun `tokens with digits symbols or edge apostrophes are never autocorrected`() {
        for (word in listOf("h3llo", "teh2", "user@name", "well-known", "x2", "123", "'em", "dont'", "a'")) {
            assertNull(autocorrectOf(word), "non-letter token <$word> must be untouchable")
        }
    }

    // ------------------------------------------------------------ short composing

    @Test
    fun `single characters are never autocorrected except lone i in text fields`() {
        for (c in 'a'..'z') {
            val s = c.toString()
            if (s == "i") continue
            assertNull(autocorrectOf(s), "1-char <$s> must never autocorrect")
        }
        assertEquals("I", autocorrectOf("i"))
        // ...but not outside free-form text fields, and not when OFF.
        assertNull(engine.suggest(request("i", fieldKind = FieldKind.SEARCH)).autocorrect)
        assertNull(engine.suggest(request("i", mode = AutocorrectMode.OFF)).autocorrect)
    }

    // ------------------------------------------------------------ OFF mode

    @Test
    fun `OFF mode is fully inert for autocorrect over typo and contraction inputs`() {
        val typos = listOf("teh", "helo", "recieve", "adn", "dont", "im", "cant", "i", "thsi", "wierd")
        for (typo in typos) {
            val result = engine.suggest(request(typo, mode = AutocorrectMode.OFF))
            assertNull(result.autocorrect, "OFF mode autocorrected <$typo>")
            assertTrue(result.suggestions.isNotEmpty(), "OFF mode should still suggest for <$typo>")
        }
    }

    // ------------------------------------------------------------ field gating (VB-702..704)

    @Test
    fun `email and uri fields return exactly the literal and never autocorrect`() {
        for (kind in listOf(FieldKind.EMAIL, FieldKind.URI)) {
            for (composing in listOf("teh", "dont", "JohnDoe", "gmail", "i")) {
                val result = engine.suggest(request(composing, fieldKind = kind))
                assertEquals(listOf(composing), result.suggestions.map { it.text }, "kind=$kind")
                assertNull(result.autocorrect, "kind=$kind autocorrected <$composing>")
            }
            // Empty composing must not predict into an address/URL.
            assertEquals(SuggestionResult.EMPTY, engine.suggest(request("", previousWord = "thank", fieldKind = kind)))
        }
    }

    @Test
    fun `password and number fields return nothing at all`() {
        for (kind in listOf(FieldKind.PASSWORD, FieldKind.NUMBER)) {
            for (composing in listOf("", "teh", "hunter2", "i", "correcthorse")) {
                for (mode in AutocorrectMode.entries) {
                    val result = engine.suggest(request(composing, previousWord = "the", fieldKind = kind, mode = mode))
                    assertEquals(SuggestionResult.EMPTY, result, "kind=$kind composing=<$composing> mode=$mode leaked")
                }
            }
        }
    }

    // ------------------------------------------------------------ ranking sanity

    @Test
    fun `typing a clearly frequent full word ranks that word first`() {
        for (word in listOf("hello", "the", "morning", "keyboard")) {
            val texts = engine.suggest(request(word)).suggestions.map { it.text }
            assertEquals(word, texts.first(), "expected <$word> first, got $texts")
        }
    }

    @Test
    fun `hostile user history cannot make a valid word autocorrect away`() {
        val history = com.vboard.core.suggest.UserHistory()
        // Heavily reinforce a neighboring word, then type the valid word.
        repeat(200) { history.recordUnigram("held") }
        repeat(200) { history.recordBigram("i", "held") }
        val poisoned = SuggestionEngine(english, history)
        val result = poisoned.suggest(request("help", previousWord = "i"))
        assertNull(result.autocorrect, "history boost autocorrected a valid word")
        assertTrue("help" in result.suggestions.map { it.text })
    }
}
