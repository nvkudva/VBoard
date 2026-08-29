package com.vboard.core.suggest

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class UserHistoryTest {

    @Nested
    @DisplayName("recording")
    inner class Recording {

        @Test
        fun `unigram counts accumulate`() {
            val history = UserHistory()
            history.recordUnigram("kotlin")
            history.recordUnigram("kotlin")
            history.recordUnigram("java")
            assertEquals(2, history.unigramCount("kotlin"))
            assertEquals(1, history.unigramCount("java"))
            assertEquals(0, history.unigramCount("rust"))
        }

        @Test
        fun `words are normalized to lowercase`() {
            val history = UserHistory()
            history.recordUnigram("Kotlin")
            assertEquals(1, history.unigramCount("kotlin"))
            assertEquals(1, history.unigramCount("KOTLIN"))
        }

        @Test
        fun `bigram counts accumulate per pair`() {
            val history = UserHistory()
            history.recordBigram("good", "morning")
            history.recordBigram("good", "morning")
            history.recordBigram("good", "night")
            assertEquals(2, history.bigramCount("good", "morning"))
            assertEquals(1, history.bigramCount("good", "night"))
            assertEquals(0, history.bigramCount("morning", "good"))
        }

        @Test
        fun `blank input is ignored`() {
            val history = UserHistory()
            history.recordUnigram("   ")
            history.recordBigram("", "x")
            assertEquals(0, history.unigramCount(""))
            assertEquals(0, history.bigramCount("", "x"))
        }
    }

    @Nested
    @DisplayName("bounding")
    inner class Bounding {

        @Test
        fun `evicts least recently used unigrams beyond maxEntries`() {
            val history = UserHistory(maxEntries = 3)
            history.recordUnigram("one")
            history.recordUnigram("two")
            history.recordUnigram("three")
            // Touch "one" so it becomes recently used; "two" is now the LRU entry.
            history.recordUnigram("one")
            history.recordUnigram("four")
            assertEquals(0, history.unigramCount("two"))
            assertEquals(2, history.unigramCount("one"))
            assertEquals(1, history.unigramCount("three"))
            assertEquals(1, history.unigramCount("four"))
        }

        @Test
        fun `bigram map is bounded independently`() {
            val history = UserHistory(maxEntries = 2)
            history.recordBigram("a", "b")
            history.recordBigram("c", "d")
            history.recordBigram("e", "f")
            assertEquals(0, history.bigramCount("a", "b"))
            assertEquals(1, history.bigramCount("c", "d"))
            assertEquals(1, history.bigramCount("e", "f"))
        }
    }

    @Nested
    @DisplayName("serialization")
    inner class Serialization {

        @Test
        fun `snapshot and restore round-trips counts`() {
            val history = UserHistory()
            history.recordUnigram("hello")
            history.recordUnigram("hello")
            history.recordUnigram("world")
            history.recordBigram("hello", "world")
            history.recordBigram("hello", "world")
            history.recordBigram("good", "morning")

            val restored = UserHistory.restore(history.snapshot())
            assertEquals(2, restored.unigramCount("hello"))
            assertEquals(1, restored.unigramCount("world"))
            assertEquals(2, restored.bigramCount("hello", "world"))
            assertEquals(1, restored.bigramCount("good", "morning"))
            assertEquals(0, restored.bigramCount("hello", "morning"))
        }

        @Test
        fun `snapshot has one entry per line in the documented format`() {
            val history = UserHistory()
            history.recordUnigram("hi")
            history.recordBigram("hi", "there")
            val lines = history.snapshot().trim().lines()
            assertEquals(2, lines.size)
            assertTrue(lines.contains("u\thi\t1"), "unigram line missing in: $lines")
            assertTrue(lines.contains("b\thi\tthere\t1"), "bigram line missing in: $lines")
        }

        @Test
        fun `restore ignores malformed lines`() {
            val restored = UserHistory.restore(
                "u\thello\t3\n" +
                    "garbage\n" +
                    "u\tmissingcount\n" +
                    "u\tbadcount\tNaN\n" +
                    "b\tonly\t2\n" +           // bigram with too few fields
                    "x\twrong\ttag\t1\n" +
                    "b\tgood\tmorning\t4\n",
            )
            assertEquals(3, restored.unigramCount("hello"))
            assertEquals(4, restored.bigramCount("good", "morning"))
            assertEquals(0, restored.unigramCount("missingcount"))
        }

        @Test
        fun `restore honors maxEntries`() {
            val history = UserHistory()
            history.recordUnigram("one")
            history.recordUnigram("two")
            history.recordUnigram("three")
            val restored = UserHistory.restore(history.snapshot(), maxEntries = 2)
            // Oldest entries are evicted first when restoring beyond the bound.
            assertEquals(0, restored.unigramCount("one"))
            assertEquals(1, restored.unigramCount("two"))
            assertEquals(1, restored.unigramCount("three"))
        }

        @Test
        fun `round-trip preserves recency order for later eviction`() {
            val history = UserHistory(maxEntries = 3)
            history.recordUnigram("old")
            history.recordUnigram("mid")
            history.recordUnigram("new")
            val restored = UserHistory.restore(history.snapshot(), maxEntries = 3)
            restored.recordUnigram("extra")
            assertEquals(0, restored.unigramCount("old"))
            assertEquals(1, restored.unigramCount("new"))
        }
    }
}
