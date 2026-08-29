package com.vboard.core.suggest

import com.vboard.core.text.FieldKind
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SuggestionEngineTest {

    private val english = Lexicon.english()

    private fun englishEngine(history: UserHistory = UserHistory()) = SuggestionEngine(english, history)

    private fun request(
        composing: String,
        previousWord: String? = null,
        fieldKind: FieldKind = FieldKind.TEXT,
        mode: AutocorrectMode = AutocorrectMode.CONSERVATIVE,
    ) = SuggestionRequest(composing, previousWord, fieldKind, mode)

    private fun texts(result: SuggestionResult): List<String> = result.suggestions.map { it.text }

    @Nested
    @DisplayName("completions")
    inner class Completions {

        @Test
        fun `completes a prefix to full lexicon words`() {
            val result = englishEngine().suggest(request("hel"))
            // Top slot completes the prefix; the typed literal keeps its slot (VB-306).
            assertTrue("help" in texts(result), "expected help in ${texts(result)}")
            assertTrue("hel" in texts(result), "expected literal hel in ${texts(result)}")
            assertTrue(result.suggestions.any { it.source == Suggestion.Source.COMPLETION })
        }

        @Test
        fun `returns at most three distinct suggestions`() {
            val result = englishEngine().suggest(request("hel"))
            assertTrue(result.suggestions.size <= 3)
            assertEquals(texts(result).distinct(), texts(result))
        }

        @Test
        fun `preserves lowercase composing casing`() {
            val result = englishEngine().suggest(request("hel"))
            assertTrue(texts(result).all { it == it.lowercase() }, "expected lowercase: ${texts(result)}")
        }

        @Test
        fun `capitalized composing capitalizes suggestions`() {
            val result = englishEngine().suggest(request("Hel"))
            assertTrue("Help" in texts(result), "expected Help in ${texts(result)}")
            assertTrue(texts(result).all { it.first().isUpperCase() })
        }

        @Test
        fun `all-caps composing uppercases suggestions`() {
            val result = englishEngine().suggest(request("HEL"))
            assertTrue(texts(result).all { it == it.uppercase() }, "expected all-caps: ${texts(result)}")
        }

        @Test
        fun `suggestions preserve lexicon apostrophes`() {
            val result = englishEngine().suggest(request("don"))
            assertTrue("don't" in texts(result), "expected don't in ${texts(result)}")
        }
    }

    @Nested
    @DisplayName("corrections")
    inner class Corrections {

        @Test
        fun `teh corrects to the`() {
            val result = englishEngine().suggest(request("teh"))
            assertEquals("the", texts(result).first())
            assertEquals("the", result.autocorrect?.text)
            assertEquals(Suggestion.Source.CORRECTION, result.autocorrect?.source)
        }

        @Test
        fun `helo corrects to hello`() {
            val result = englishEngine().suggest(request("helo"))
            assertEquals("hello", texts(result).first())
            assertEquals("hello", result.autocorrect?.text)
        }

        @Test
        fun `recieve corrects to receive`() {
            val result = englishEngine().suggest(request("recieve"))
            assertEquals("receive", texts(result).first())
            assertEquals("receive", result.autocorrect?.text)
        }

        @Test
        fun `adn corrects to and via transposition`() {
            val result = englishEngine().suggest(request("adn"))
            assertEquals("and", texts(result).first())
            assertEquals("and", result.autocorrect?.text)
        }

        @Test
        fun `hte corrects to the via transposition`() {
            val result = englishEngine().suggest(request("hte"))
            assertEquals("the", texts(result).first())
        }

        @Test
        fun `qwerty-adjacent substitution outranks a more frequent distant one`() {
            // "cut" is nearly 3x more frequent than "cat", but s->a is a neighboring key
            // while s->u is not, so "cst" should read as a fat-fingered "cat".
            val lexicon = Lexicon.fromEntries(listOf("cat" to 3000L, "cut" to 9000L, "cost" to 2000L))
            val result = SuggestionEngine(lexicon).suggest(request("cst"))
            assertEquals("cat", texts(result).first())
        }

        @Test
        fun `corrected text follows the typed casing`() {
            val result = englishEngine().suggest(request("Teh"))
            assertEquals("The", result.autocorrect?.text)
        }

        @Test
        fun `short words only allow one edit`() {
            // "xq" is 2 edits from anything real; no correction should fire for length 2.
            val result = englishEngine().suggest(request("xq"))
            assertNull(result.autocorrect)
        }

        @Test
        fun `user bigram history reorders otherwise stronger candidates`() {
            val lexicon = Lexicon.fromEntries(listOf("apple" to 500L, "apply" to 800L))
            val history = UserHistory()
            val engine = SuggestionEngine(lexicon, history)
            assertEquals("apply", texts(engine.suggest(request("appl", previousWord = "an"))).first())
            history.recordBigram("an", "apple")
            assertEquals("apple", texts(engine.suggest(request("appl", previousWord = "an"))).first())
        }
    }

    @Nested
    @DisplayName("contractions")
    inner class Contractions {

        private fun autocorrectOf(composing: String, mode: AutocorrectMode = AutocorrectMode.CONSERVATIVE): String? =
            englishEngine().suggest(request(composing, mode = mode)).autocorrect?.text

        @Test
        fun `common dropped-apostrophe forms are corrected`() {
            assertEquals("don't", autocorrectOf("dont"))
            assertEquals("I'm", autocorrectOf("im"))
            assertEquals("can't", autocorrectOf("cant"))
            assertEquals("won't", autocorrectOf("wont"))
            assertEquals("I'd", autocorrectOf("id"))
            assertEquals("I've", autocorrectOf("ive"))
            assertEquals("isn't", autocorrectOf("isnt"))
            assertEquals("didn't", autocorrectOf("didnt"))
            assertEquals("you're", autocorrectOf("youre"))
            assertEquals("that's", autocorrectOf("thats"))
        }

        @Test
        fun `contraction casing follows the typed word`() {
            assertEquals("Don't", autocorrectOf("Dont"))
            assertEquals("I'm", autocorrectOf("Im"))
        }

        @Test
        fun `its is a valid word and is never corrected`() {
            assertNull(autocorrectOf("its"))
        }

        @Test
        fun `ill is a valid word and is never corrected`() {
            assertNull(autocorrectOf("ill"))
        }

        @Test
        fun `contractions respect OFF mode`() {
            assertNull(autocorrectOf("dont", mode = AutocorrectMode.OFF))
        }

        @Test
        fun `contractions apply in search fields but not email fields`() {
            val engine = englishEngine()
            val search = engine.suggest(request("dont", fieldKind = FieldKind.SEARCH))
            assertEquals("don't", search.autocorrect?.text)
            val email = engine.suggest(request("dont", fieldKind = FieldKind.EMAIL))
            assertNull(email.autocorrect)
        }
    }

    @Nested
    @DisplayName("autocorrect gating")
    inner class AutocorrectGating {

        @Test
        fun `words already in the lexicon are never autocorrected`() {
            val engine = englishEngine()
            assertNull(engine.suggest(request("hello")).autocorrect)
            assertNull(engine.suggest(request("the")).autocorrect)
            assertNull(engine.suggest(request("don't")).autocorrect)
        }

        @Test
        fun `tokens containing digits are never autocorrected`() {
            assertNull(englishEngine().suggest(request("h3llo")).autocorrect)
            assertNull(englishEngine().suggest(request("teh2")).autocorrect)
        }

        @Test
        fun `all-caps tokens are never autocorrected`() {
            assertNull(englishEngine().suggest(request("TEH")).autocorrect)
        }

        @Test
        fun `mode OFF suppresses autocorrect but keeps suggestions`() {
            val result = englishEngine().suggest(request("teh", mode = AutocorrectMode.OFF))
            assertNull(result.autocorrect)
            assertEquals("the", texts(result).first())
        }

        @Test
        fun `single letters other than i are not autocorrected`() {
            assertNull(englishEngine().suggest(request("x")).autocorrect)
        }

        @Test
        fun `conservative mode refuses two-edit corrections that aggressive accepts`() {
            // "cinputer" -> "computer" needs two adjacent-key substitutions (i->o, n->m).
            val lexicon = Lexicon.fromEntries(listOf("computer" to 50_000L))
            val engine = SuggestionEngine(lexicon)
            val conservative = engine.suggest(request("cinputer", mode = AutocorrectMode.CONSERVATIVE))
            assertNull(conservative.autocorrect)
            val aggressive = engine.suggest(request("cinputer", mode = AutocorrectMode.AGGRESSIVE))
            assertEquals("computer", aggressive.autocorrect?.text)
        }

        @Test
        fun `lone i becomes I in text fields`() {
            val result = englishEngine().suggest(request("i"))
            assertEquals("I", result.autocorrect?.text)
            assertTrue("I" in texts(result))
        }

        @Test
        fun `lone i is untouched when mode is OFF or field is not TEXT`() {
            val engine = englishEngine()
            assertNull(engine.suggest(request("i", mode = AutocorrectMode.OFF)).autocorrect)
            assertNull(engine.suggest(request("i", fieldKind = FieldKind.EMAIL)).autocorrect)
        }
    }

    @Nested
    @DisplayName("field gating")
    inner class FieldGating {

        @Test
        fun `password fields get nothing`() {
            val result = englishEngine().suggest(request("teh", fieldKind = FieldKind.PASSWORD))
            assertEquals(SuggestionResult.EMPTY, result)
        }

        @Test
        fun `number fields get nothing`() {
            val result = englishEngine().suggest(request("teh", fieldKind = FieldKind.NUMBER))
            assertEquals(SuggestionResult.EMPTY, result)
        }

        @Test
        fun `email fields echo the literal only`() {
            val result = englishEngine().suggest(request("Teh", fieldKind = FieldKind.EMAIL))
            assertEquals(listOf("Teh"), texts(result))
            assertEquals(Suggestion.Source.LITERAL, result.suggestions.single().source)
            assertNull(result.autocorrect)
        }

        @Test
        fun `uri fields echo the literal only`() {
            val result = englishEngine().suggest(request("githb", fieldKind = FieldKind.URI))
            assertEquals(listOf("githb"), texts(result))
            assertNull(result.autocorrect)
        }

        @Test
        fun `email and uri fields never predict`() {
            val engine = englishEngine()
            assertEquals(SuggestionResult.EMPTY, engine.suggest(request("", previousWord = "thank", fieldKind = FieldKind.EMAIL)))
            assertEquals(SuggestionResult.EMPTY, engine.suggest(request("", previousWord = "thank", fieldKind = FieldKind.URI)))
        }
    }

    @Nested
    @DisplayName("predictions")
    inner class Predictions {

        @Test
        fun `empty composing yields predictions`() {
            val result = englishEngine().suggest(request("", previousWord = "thank"))
            assertTrue(result.suggestions.isNotEmpty())
            assertTrue(result.suggestions.all { it.source == Suggestion.Source.PREDICTION })
            assertNull(result.autocorrect)
        }

        @Test
        fun `built-in bigram table predicts thank -- you`() {
            val result = englishEngine().suggest(request("", previousWord = "thank"))
            assertEquals("you", texts(result).first())
        }

        @Test
        fun `user bigram history beats generic predictions`() {
            val history = UserHistory()
            history.recordBigram("thank", "goodness")
            val result = englishEngine(history).suggest(request("", previousWord = "thank"))
            assertEquals("goodness", texts(result).first())
            assertTrue("you" in texts(result), "built-in pair should still rank: ${texts(result)}")
        }

        @Test
        fun `never predicts the previous word again`() {
            val history = UserHistory()
            history.recordBigram("you", "you") // hostile history must not leak through
            val result = englishEngine(history).suggest(request("", previousWord = "you"))
            assertTrue(texts(result).none { it.equals("you", ignoreCase = true) }, "${texts(result)}")
        }

        @Test
        fun `predictions fall back to top unigrams without a previous word`() {
            val result = englishEngine().suggest(request(""))
            assertEquals(3, result.suggestions.size)
            assertTrue(result.suggestions.all { it.source == Suggestion.Source.PREDICTION })
        }

        @Test
        fun `pronoun i is presented capitalized in predictions`() {
            val result = englishEngine().suggest(request(""))
            assertTrue(texts(result).none { it == "i" }, "bare lowercase i in ${texts(result)}")
        }
    }

    @Nested
    @DisplayName("learning")
    inner class Learning {

        @Test
        fun `recordCommittedWord learns unigram and bigram`() {
            val history = UserHistory()
            val engine = englishEngine(history)
            engine.recordCommittedWord("good", "Morning!")
            assertEquals(1, history.unigramCount("morning"))
            assertEquals(1, history.bigramCount("good", "morning"))
        }

        @Test
        fun `recordCommittedWord ignores tokens with digits or symbols`() {
            val history = UserHistory()
            val engine = englishEngine(history)
            engine.recordCommittedWord(null, "abc123")
            engine.recordCommittedWord(null, "foo@bar")
            assertEquals(0, history.unigramCount("abc123"))
            assertEquals(0, history.unigramCount("foo@bar"))
        }

        @Test
        fun `learned words surface in later predictions`() {
            val history = UserHistory()
            val engine = englishEngine(history)
            repeat(3) { engine.recordCommittedWord("vboard", "rocks") }
            val result = engine.suggest(request("", previousWord = "vboard"))
            assertEquals("rocks", texts(result).first())
        }
    }

    @Nested
    @DisplayName("performance")
    inner class Performance {

        @Test
        fun `median suggest latency stays under 50ms on the bundled lexicon`() {
            val engine = englishEngine()
            val probes = listOf("teh", "recieve", "keyboa", "helo", "t", "understandin", "Hel", "")
            // Warm-up: JIT + lexicon lazy load.
            repeat(50) { engine.suggest(request(probes[it % probes.size], previousWord = "the")) }

            val timings = LongArray(120)
            for (i in timings.indices) {
                val probe = probes[i % probes.size]
                val start = System.nanoTime()
                engine.suggest(request(probe, previousWord = "the"))
                timings[i] = System.nanoTime() - start
            }
            timings.sort()
            val p50Millis = timings[timings.size / 2] / 1_000_000.0
            assertTrue(p50Millis < 50.0, "p50 suggest latency was $p50Millis ms")
        }
    }
}
