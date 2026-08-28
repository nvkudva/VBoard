package com.vboard.core.suggest

import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LexiconTest {

    private fun lexiconOf(text: String): Lexicon =
        Lexicon.load(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

    @Nested
    @DisplayName("load")
    inner class Load {

        @Test
        fun `parses word-tab-frequency lines`() {
            val lexicon = lexiconOf("hello\t100\nworld\t50\n")
            assertEquals(2, lexicon.size)
            assertEquals(100L, lexicon.frequencyOf("hello"))
            assertEquals(50L, lexicon.frequencyOf("world"))
        }

        @Test
        fun `skips comment lines starting with hash`() {
            val lexicon = lexiconOf("# a header comment\n# another\nhello\t100\n")
            assertEquals(1, lexicon.size)
            assertTrue(lexicon.contains("hello"))
        }

        @Test
        fun `skips blank and malformed lines`() {
            val lexicon = lexiconOf(
                "\n" +                 // blank
                    "   \n" +          // whitespace only
                    "notab\n" +        // no tab separator
                    "word\tNaN\n" +    // non-numeric frequency
                    "zero\t0\n" +      // non-positive frequency
                    "neg\t-5\n" +      // negative frequency
                    "\t42\n" +         // empty word
                    "ok\t7\n",
            )
            assertEquals(1, lexicon.size)
            assertEquals(7L, lexicon.frequencyOf("ok"))
            assertEquals(0L, lexicon.frequencyOf("zero"))
        }

        @Test
        fun `lowercases entries and keeps the highest frequency for duplicates`() {
            val lexicon = lexiconOf("Hello\t100\nHELLO\t300\nhello\t200\n")
            assertEquals(1, lexicon.size)
            assertEquals(300L, lexicon.frequencyOf("hello"))
        }
    }

    @Nested
    @DisplayName("lookup")
    inner class Lookup {

        private val lexicon = Lexicon.fromEntries(
            listOf("hello" to 100L, "don't" to 500L, "the" to 900L),
        )

        @Test
        fun `contains is case-insensitive`() {
            assertTrue(lexicon.contains("hello"))
            assertTrue(lexicon.contains("Hello"))
            assertTrue(lexicon.contains("HELLO"))
            assertFalse(lexicon.contains("helloo"))
        }

        @Test
        fun `frequencyOf is case-insensitive and zero for absent words`() {
            assertEquals(100L, lexicon.frequencyOf("HeLLo"))
            assertEquals(0L, lexicon.frequencyOf("missing"))
        }

        @Test
        fun `stores words with internal apostrophes`() {
            assertTrue(lexicon.contains("don't"))
            assertEquals(500L, lexicon.frequencyOf("DON'T"))
        }

        @Test
        fun `fromEntries ignores blank words and non-positive frequencies`() {
            val lexicon = Lexicon.fromEntries(listOf("" to 5L, "ok" to 5L, "bad" to 0L))
            assertEquals(1, lexicon.size)
            assertTrue(lexicon.contains("ok"))
        }
    }

    @Nested
    @DisplayName("wordsWithPrefix")
    inner class PrefixSearch {

        private val lexicon = Lexicon.fromEntries(
            listOf(
                "the" to 1000L,
                "they" to 400L,
                "them" to 300L,
                "theme" to 50L,
                "then" to 350L,
                "thermal" to 5L,
                "cat" to 80L,
            ),
        )

        @Test
        fun `ranks matches by frequency descending`() {
            val words = lexicon.wordsWithPrefix("the", 10).map { it.word }
            assertEquals(listOf("the", "they", "then", "them", "theme", "thermal"), words)
        }

        @Test
        fun `respects the limit`() {
            val words = lexicon.wordsWithPrefix("the", 2).map { it.word }
            assertEquals(listOf("the", "they"), words)
        }

        @Test
        fun `is case-insensitive on the prefix`() {
            val words = lexicon.wordsWithPrefix("THE", 3).map { it.word }
            assertEquals(listOf("the", "they", "then"), words)
        }

        @Test
        fun `returns empty for unknown prefix or non-positive limit`() {
            assertTrue(lexicon.wordsWithPrefix("zzz", 5).isEmpty())
            assertTrue(lexicon.wordsWithPrefix("the", 0).isEmpty())
        }

        @Test
        fun `empty prefix yields the most frequent words overall`() {
            val words = lexicon.wordsWithPrefix("", 3).map { it.word }
            assertEquals(listOf("the", "they", "then"), words)
        }

        @Test
        fun `score carries the raw frequency`() {
            val top = lexicon.wordsWithPrefix("the", 1).single()
            assertEquals("the", top.word)
            assertEquals(1000.0, top.score)
        }
    }

    @Nested
    @DisplayName("bundled English lexicon")
    inner class BundledEnglish {

        private val english = Lexicon.english()

        @Test
        fun `loads at least 30k words`() {
            assertTrue(english.size >= 30_000, "expected >= 30000 words, got ${english.size}")
        }

        @Test
        fun `contains core vocabulary`() {
            for (word in listOf("hello", "the", "you", "keyboard", "voice", "a", "i", "and", "receive")) {
                assertTrue(english.contains(word), "missing core word: $word")
            }
        }

        @Test
        fun `contains common contractions with apostrophes`() {
            for (word in listOf("don't", "i'm", "can't", "won't", "you're", "that's", "it's", "i'll")) {
                assertTrue(english.contains(word), "missing contraction: $word")
            }
        }

        @Test
        fun `frequency ordering is sane`() {
            assertTrue(english.frequencyOf("the") > english.frequencyOf("keyboard"))
            assertTrue(english.frequencyOf("you") > english.frequencyOf("voice"))
        }

        @Test
        fun `does not contain slurs or blocklisted profanity`() {
            for (word in listOf("nigger", "faggot", "cunt", "kike", "fuck", "motherfucker")) {
                assertFalse(english.contains(word), "blocklisted word present: $word")
            }
        }

        @Test
        fun `does not contain apostrophe-dropped typo tokens`() {
            for (word in listOf("dont", "im", "thats", "youre", "isnt", "didnt")) {
                assertFalse(english.contains(word), "typo token present: $word")
            }
        }

        @Test
        fun `contains only lowercase letters and internal apostrophes`() {
            // Spot-check via prefix walks across the alphabet.
            val pattern = Regex("^[a-z]+(?:'[a-z]+)*$")
            for (prefix in listOf("a", "m", "z", "q", "o")) {
                for (scored in english.wordsWithPrefix(prefix, 200)) {
                    assertTrue(pattern.matches(scored.word), "malformed entry: ${scored.word}")
                }
            }
        }
    }
}
