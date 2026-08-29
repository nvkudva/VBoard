package com.vboard.core.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guard on [CleanupResult]'s redaction, and the sibling of
 * `ClipRedactionTest` in the clipboard package.
 *
 * [CleanupResult] is a data class holding the cleaned transcript in its first
 * component, so its *generated* `toString` would print everything the user just
 * said — into logcat, into a crash report, into whatever diagnostic reached for
 * `"$result"`. The hand-written override is the only thing preventing that, and
 * it is one careless `data class` regeneration away from vanishing. This suite
 * fails the moment it does.
 *
 * The prohibition covers derived facts too: no character count, no prefix. The
 * counters are exempt by design — they are the disclosure the "Cleaned" chip is
 * built on, and they say how much changed, never what.
 */
class CleanupRedactionTest {

    // Deliberately shares no substring with any field name in the rendered
    // toString ("correct" would collide with correctionsResolved).
    private val spoken = "purple zebra kettle drifting"

    private fun resultOf(text: String) =
        CleanupResult(
            text = text,
            fillersRemoved = 1,
            correctionsResolved = 2,
            repetitionsCollapsed = 3,
            spokenSubstitutions = 4,
        )

    @Test
    fun `toString does not contain the cleaned text`() {
        assertFalse(resultOf(spoken).toString().contains(spoken))
    }

    @Test
    fun `toString does not contain any word of the cleaned text`() {
        val rendered = resultOf(spoken).toString()
        for (word in spoken.split(' ')) {
            assertFalse(rendered.contains(word, ignoreCase = true), "toString leaked \"$word\"")
        }
    }

    @Test
    fun `toString does not contain a prefix of the cleaned text`() {
        val rendered = resultOf(spoken).toString()
        for (n in 4..spoken.length) {
            assertFalse(rendered.contains(spoken.substring(0, n)), "toString leaked a $n-char prefix")
        }
    }

    @Test
    fun `toString does not contain the text's character count`() {
        // A length is a fact about the content; "just the length" is the exact
        // compromise this test exists to refuse.
        val rendered = CleanupResult(text = "x".repeat(4_321)).toString()
        assertFalse(rendered.contains("4321"))
        assertFalse(rendered.contains("4,321"))
    }

    @Test
    fun `toString is identical for two results that differ only in text`() {
        assertEquals(resultOf("alpha").toString(), resultOf("a completely different sentence").toString())
    }

    @Test
    fun `a list of results renders without content`() {
        // The realistic leak: Log.d(TAG, "results=$batch").
        val batch = listOf(resultOf(spoken), resultOf("second secret"))
        val rendered = batch.toString()
        assertFalse(rendered.contains(spoken))
        assertFalse(rendered.contains("second secret"))
    }

    @Test
    fun `the disclosure counters do survive toString`() {
        // The counters are the point of the object: a redaction that hid them too
        // would make the "Cleaned" chip undebuggable and invite someone to log the
        // text instead.
        val rendered = resultOf(spoken).toString()
        for (count in listOf("1", "2", "3", "4")) {
            assertTrue(rendered.contains(count), "toString dropped the counter $count")
        }
    }

    @Test
    fun `a real cleanup result renders without the transcript`() {
        // End to end, through the cleaner rather than a constructed value.
        val result = TranscriptCleaner().clean(CleanupRequest("um call me at five no wait six"))
        val rendered = result.toString()
        assertFalse(rendered.contains("six", ignoreCase = true), rendered)
        assertFalse(rendered.contains("call", ignoreCase = true), rendered)
    }
}
