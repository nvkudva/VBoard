package com.vboard.core.clipboard

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guard on the logging prohibition (docs/PRODUCT_SPEC.md): clip content must
 * never reach logcat or any diagnostic, and the single most likely way for it to
 * get there is somebody logging a clip — directly, inside a list, or inside an
 * object that holds one. A data class's generated `toString` would leak it, so
 * [ClipEntry] overrides it and this suite fails the moment that stops being true.
 *
 * The prohibition covers derived facts too: no character count, no prefix. Those
 * are asserted here as well, because "just the length" is exactly the compromise
 * somebody reaches for when debugging.
 */
class ClipRedactionTest {

    private val secret = "correct horse battery staple"

    @Test
    fun `toString does not contain the clip text`() {
        val entry = ClipEntry(secret, capturedAtMillis = 1_700_000_000_000L, pinned = false)
        assertFalse(entry.toString().contains(secret))
    }

    @Test
    fun `toString does not contain any word of the clip text`() {
        val entry = ClipEntry(secret, capturedAtMillis = 1_700_000_000_000L, pinned = true)
        val rendered = entry.toString()
        for (word in secret.split(' ')) {
            assertFalse(rendered.contains(word, ignoreCase = true), "toString leaked \"$word\"")
        }
    }

    @Test
    fun `toString does not contain a prefix of the clip text`() {
        val entry = ClipEntry(secret, capturedAtMillis = 1L, pinned = false)
        val rendered = entry.toString()
        for (n in 4..secret.length) {
            assertFalse(rendered.contains(secret.substring(0, n)), "toString leaked a $n-char prefix")
        }
    }

    @Test
    fun `toString does not contain the clip's character count`() {
        // A length is a fact about the content; "just the length" is the exact
        // compromise this test exists to refuse.
        val entry = ClipEntry("x".repeat(4_321), capturedAtMillis = 1L, pinned = false)
        assertFalse(entry.toString().contains("4321"))
        assertFalse(entry.toString().contains("4,321"))
    }

    @Test
    fun `toString is identical for two clips that differ only in content`() {
        val a = ClipEntry("alpha", capturedAtMillis = 7L, pinned = false)
        val b = ClipEntry("a completely different clip entirely", capturedAtMillis = 7L, pinned = false)
        assertEquals(a.toString(), b.toString())
    }

    @Test
    fun `a list of clips renders without content`() {
        // The realistic leak: Log.d(TAG, "clips=$entries").
        val entries = listOf(
            ClipEntry(secret, capturedAtMillis = 1L, pinned = false),
            ClipEntry("second secret", capturedAtMillis = 2L, pinned = true),
        )
        val rendered = entries.toString()
        assertFalse(rendered.contains(secret))
        assertFalse(rendered.contains("second secret"))
    }

    @Test
    fun `an offer result renders without content`() {
        val stored = OfferResult.Stored(
            ClipEntry(secret, capturedAtMillis = 1L, pinned = false),
            deduplicated = false,
        )
        assertFalse(stored.toString().contains(secret))

        val sessionOnly = OfferResult.SessionOnly(ClipEntry("483920", capturedAtMillis = 1L))
        assertFalse(sessionOnly.toString().contains("483920"))
    }

    @Test
    fun `discard reasons carry no content`() {
        for (reason in DiscardReason.entries) {
            assertTrue(reason.toString().all { it.isLetter() || it == '_' })
        }
    }
}
