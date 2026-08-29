package com.vboard.core.correct

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The length policy (VB-234).
 *
 * The one invariant that matters: whatever the chunker does, the pieces have to
 * put the text back together exactly. A chunk whose result cannot be reassembled
 * faithfully must never be sent to the model at all.
 */
class FixChunkerTest {

    private fun roundTrip(text: String): String {
        val segments = FixChunker.split(text)
        return FixChunker.assemble(segments, segments.map { it.body() })
    }

    @Test
    fun `splitting then assembling is lossless`() {
        val inputs = listOf(
            "",
            "one sentence",
            "One. Two! Three?",
            "Line one\nLine two\n\nLine four",
            "   leading and trailing   ",
            "Trailing newline.\n",
            "Ellipsis... then more. And more!!! Done?",
            "no terminator at all",
            "a".repeat(FixChunker.MAX_CHUNK_CHARS * 3),
            sentences(40),
        )
        for (input in inputs) {
            assertEquals(input, roundTrip(input), "round trip lost data for <${input.take(40)}…>")
        }
    }

    @Test
    fun `text under the chunk cap is one refinable chunk`() {
        val text = "This is short. So is this."
        val segments = FixChunker.split(text)
        assertEquals(1, segments.size)
        assertTrue(segments.single().refinable)
    }

    @Test
    fun `text just under the chunk cap stays a single chunk`() {
        val body = "word ".repeat(FixChunker.MAX_CHUNK_CHARS / 5 - 1).trim() + "."
        assertTrue(body.length <= FixChunker.MAX_CHUNK_CHARS)
        val segments = FixChunker.split(body)
        assertEquals(1, segments.size)
        assertTrue(segments.single().refinable)
    }

    @Test
    fun `text over the chunk cap splits on sentence boundaries`() {
        val text = sentences(60)
        assertTrue(text.length > FixChunker.MAX_CHUNK_CHARS)
        val segments = FixChunker.split(text)
        assertTrue(segments.size > 1, "expected multiple chunks, got ${segments.size}")
        for (segment in segments) {
            assertTrue(
                segment.body().length <= FixChunker.MAX_CHUNK_CHARS,
                "chunk over cap: ${segment.body().length}",
            )
            assertTrue(segment.refinable)
        }
        assertEquals(text, roundTrip(text))
    }

    @Test
    fun `a single sentence past the cap is marked unrefinable rather than cut`() {
        val monster = "word ".repeat(FixChunker.MAX_CHUNK_CHARS) + "end."
        val segments = FixChunker.split(monster)
        val oversized = segments.filter { it.body().length > FixChunker.MAX_CHUNK_CHARS }
        assertTrue(oversized.isNotEmpty(), "expected an oversized segment")
        oversized.forEach { assertFalse(it.refinable, "an oversized chunk must never be sent") }
        assertEquals(monster, roundTrip(monster))
    }

    @Test
    fun `whitespace at chunk boundaries is held out of the model's reach`() {
        val text = "  First one.   Second one.\n\nThird one.  "
        val segments = FixChunker.split(text)
        // Whatever the model returns for each body, the edges stay ours.
        val rewritten = FixChunker.assemble(segments, segments.map { "X" })
        assertEquals(segments.size, rewritten.count { it == 'X' })
        assertTrue(rewritten.startsWith("  "), "leading whitespace lost: <$rewritten>")
        assertTrue(rewritten.endsWith("  "), "trailing whitespace lost: <$rewritten>")
        segments.forEach {
            val body = it.body()
            assertFalse(body.first().isWhitespace(), "chunk body starts with whitespace")
            assertFalse(body.last().isWhitespace(), "chunk body ends with whitespace")
        }
    }

    @Test
    fun `assemble refuses a mismatched body count`() {
        val segments = FixChunker.split("One. Two.")
        val failure = runCatching { FixChunker.assemble(segments, segments.map { "x" } + "extra") }
        assertTrue(failure.isFailure)
    }

    @Test
    fun `segment toString carries no content`() {
        val segment = FixChunker.split("Top secret merger with Acme.").single()
        assertFalse("secret" in segment.toString())
        assertFalse("Acme" in segment.toString())
    }

    private fun sentences(count: Int): String =
        (1..count).joinToString(" ") { "This is sentence number $it in the message." }
}
