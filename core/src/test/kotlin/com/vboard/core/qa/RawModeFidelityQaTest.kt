package com.vboard.core.qa

import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.FieldKind
import com.vboard.core.text.TranscriptCleaner
import com.vboard.core.text.UtteranceCommand
import java.text.Normalizer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Raw mode (VB-220) is the product's escape hatch: the setting a user turns on
 * precisely because cleanup mangled something. `Cleanup.kt:14-19` documents it as
 * *"Raw transcript escape hatch: bypasses every transformation EXCEPT spoken
 * commands and whole-utterance commands"*.
 *
 * It did not. `TranscriptCleaner.clean` runs `Tokenizer.tokenize` before it looks
 * at `rawMode` at all, and then re-renders through `Tokenizer.render`, so raw mode
 * inherited every deletion the tokenizer performed — emoji, unrecognized symbols,
 * combining marks (VB-QA-17).
 *
 * Package A closed that as a consequence of the tokenizer inversion rather than by
 * special-casing raw mode: once the tokenizer stops deleting content, the escape
 * hatch stops leaking it. Raw mode is additionally exempt from the NFC pass, since
 * normalization is itself a transformation.
 *
 * Two transformations of its own remain, and are still asserted below: render-level
 * respacing plus quote folding, and `capitalizeStandaloneI` (VB-QA-04).
 */
class RawModeFidelityQaTest {

    private val cleaner = TranscriptCleaner()

    private fun raw(text: String) = cleaner
        .clean(CleanupRequest(text, "", FieldKind.TEXT, CleanupOptions.RAW, ensureTerminalPunctuation = true))

    // ---------------------------------------------------- what raw mode does honour

    @Test
    fun `raw mode really does bypass the transformation stages`() {
        assertEquals("um uh the the thing no wait stuff", raw("um uh the the thing no wait stuff").text)
        assertEquals("what is this", raw("what is this").text)
        assertEquals("hello world this is a test", raw("hello world this is a test").text)
        val result = raw("um the the thing no wait stuff")
        assertEquals(0, result.fillersRemoved)
        assertEquals(0, result.correctionsResolved)
        assertEquals(0, result.repetitionsCollapsed)
    }

    @Test
    fun `raw mode keeps spoken and whole-utterance commands working`() {
        assertEquals(UtteranceCommand.STOP_LISTENING, raw("stop listening").command)
        assertEquals(UtteranceCommand.SCRATCH_THAT, raw("scratch that").command)
        assertEquals("hello, world", raw("hello comma world").text)
        assertEquals("hello\nworld", raw("hello new line world").text)
    }

    // -------------------------------------------- VB-QA-17: raw mode is not verbatim

    @Test
    fun `raw mode keeps the content the user turned it on to protect`() {
        // Every one of these used to be silently deleted by the tokenizer that runs
        // before rawMode is consulted (VB-QA-17).
        assertEquals("hello 👋", raw("hello 👋").text)
        assertEquals("$75", raw("$75").text)
        assertEquals("a‍b", raw("a‍b").text)
        assertEquals("under_score", raw("under_score").text)
        assertEquals("a + b = c", raw("a + b = c").text)
        assertEquals("see [see attached] for details", raw("see [see attached] for details").text)
        // Raw mode is also exempt from the NFC normalization the other modes apply,
        // because NFC is itself a transformation.
        assertEquals(
            Normalizer.normalize("café", Normalizer.Form.NFD),
            raw(Normalizer.normalize("café", Normalizer.Form.NFD)).text,
        )
        // ...and it still applies two transformations of its own.
        assertEquals("I think I'm ok", raw("i think i'm ok").text)   // VB-QA-04
        assertEquals("hello world", raw("hello    world").text)      // respacing
        assertEquals("he said \"hi\"", raw("he said “hi”").text)     // quote folding
    }

    @Test
    fun `raw mode should return the transcript verbatim`() {
        for (input in listOf("hello 👋", "$75", "under_score", "a + b = c", "see [see attached] for details")) {
            assertEquals(input, raw(input).text, "raw mode altered <$input>")
        }
        assertEquals(
            Normalizer.normalize("café", Normalizer.Form.NFD),
            raw(Normalizer.normalize("café", Normalizer.Form.NFD)).text,
        )
    }

    @Test
    fun `raw mode never loses a character that normal cleanup keeps`() {
        val normal = CleanupOptions()
        for (input in listOf("hello 👋 world", "$75 for it", "a + b = c", "café is open")) {
            val rawChars = raw(input).text.filterNot { it == ' ' }.toSet()
            val normalChars = cleaner
                .clean(CleanupRequest(input, "", FieldKind.TEXT, normal, true))
                .text.filterNot { it == ' ' || it == '.' }.lowercase().toSet()
            assertTrue(
                normalChars.all { it in rawChars.map { c -> c.lowercaseChar() } },
                "normal cleanup kept characters raw mode dropped, for <$input>",
            )
        }
    }

    // --------------------------------------------------------------- invariants

    @Test
    fun `raw mode never adds punctuation`() {
        val inputs = listOf(
            "hello world this is a test", "what is this", "one two three four",
            "the meeting is at five", "call me back later today",
        )
        for (input in inputs) {
            val out = raw(input).text
            assertTrue(out.none { it in ".!?" }, "raw mode added terminal punctuation to <$input>: <$out>")
        }
    }

    @Test
    fun `raw mode output is a subsequence of the input, ignoring case and spacing`() {
        // Whatever raw mode loses, it must never reorder or invent. This is the
        // weakest honest guarantee the escape hatch currently provides, and it is
        // worth pinning so a "fix" that rewrites instead of preserving is caught.
        val inputs = listOf(
            "hello 👋 world", "$75 for it", "a + b = c", "under_score name",
            "um uh the the thing", "he said “hi” loudly", "café is open now",
        )
        for (input in inputs) {
            val out = raw(input).text.lowercase().filter { it.isLetterOrDigit() }
            val src = input.lowercase().filter { it.isLetterOrDigit() }
            var i = 0
            for (ch in src) if (i < out.length && out[i] == ch) i++
            assertEquals(out.length, i, "raw output of <$input> is not a subsequence of the input")
        }
    }

    @Test
    fun `raw mode is idempotent`() {
        val inputs = listOf(
            "hello 👋 world", "$75 for it", "um uh the the thing", "hello    world",
            "he said “hi” loudly", "i think i'm ok", "hello comma world",
        )
        for (input in inputs) {
            val once = raw(input).text
            assertEquals(once, raw(once).text, "raw mode not idempotent for <$input>")
        }
    }
}
