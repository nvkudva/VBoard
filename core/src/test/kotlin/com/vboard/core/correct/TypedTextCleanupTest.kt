package com.vboard.core.correct

import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.FieldKind
import com.vboard.core.text.TranscriptCleaner
import com.vboard.core.text.UtteranceCommand
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The deterministic half of "AI fix" (VB-231).
 *
 * Two things are pinned here. First, that the *speech* stages of the Tier-1
 * engine do not fire on typed input — a typed "um" is a word, a typed "period"
 * is a word, and a typed "scratch that" is a sentence, not an instruction.
 * Second, the exact output for representative typed mistakes, so any rule change
 * shows up as a diff.
 */
class TypedTextCleanupTest {

    private val cleaner = TranscriptCleaner()

    private fun fix(text: String, fieldKind: FieldKind = FieldKind.TEXT) =
        TypedTextCleanup.clean(text, fieldKind, cleaner)

    // ------------------------------------------------- typed vs. spoken stages

    @Test
    fun `filler removal does not fire on typed text`() {
        // The speech pipeline eats these; typed, they are the user's own words.
        assertEquals("Um, ok, uh sure.", fix("um, ok, uh sure"))
        assertEquals("Hmm let me think about it.", fix("hmm let me think about it"))
    }

    @Test
    fun `the speech pipeline would have removed those same fillers`() {
        // Guards the test above from passing for the wrong reason: prove the
        // difference is the options, not the input.
        val spoken = cleaner.clean(
            CleanupRequest(
                transcript = "um, ok, uh sure",
                fieldKind = FieldKind.TEXT,
                ensureTerminalPunctuation = true,
            ),
        ).text
        assertNotEquals(fix("um, ok, uh sure"), spoken)
        assertTrue("um" !in spoken.lowercase(), "speech cleanup should have dropped the filler")
    }

    @Test
    fun `spoken punctuation commands stay words when typed`() {
        assertEquals("Add a period at the end.", fix("add a period at the end"))
        assertEquals("Start a new line here.", fix("start a new line here"))
        assertEquals("The question mark is missing.", fix("the question mark is missing"))
    }

    @Test
    fun `self-correction markers are content when typed`() {
        // "I mean" / "scratch that" would make the speech engine delete the
        // clause in front of them. Typed, every word survives.
        assertEquals(
            // The standalone-"i" rule still applies; the clause before it lives.
            "Send it to john I mean to mary.",
            fix("send it to john i mean to mary"),
        )
        assertEquals(
            "Tell him about it scratch that forget it.",
            fix("tell him about it scratch that forget it"),
        )
    }

    @Test
    fun `whole-utterance commands are never detected on typed text`() {
        // "scratch that" alone is a valid thing to type into a chat box, and the
        // speech engine would return an empty string plus a command for it.
        // Stage 2 of the cleaner is not gated by CleanupOptions, so such a line
        // is passed through untouched instead — un-capitalized, but present,
        // which is the only failure mode that actually matters here.
        assertEquals("scratch that", fix("scratch that"))
        assertEquals("stop listening", fix("stop listening"))
        assertEquals("Delete that now.", fix("delete that now"))
    }

    @Test
    fun `the speech pipeline does treat a bare scratch that as a command`() {
        val spoken = cleaner.clean(CleanupRequest(transcript = "scratch that"))
        assertEquals(UtteranceCommand.SCRATCH_THAT, spoken.command)
    }

    // ------------------------------------------------------- typed corrections

    data class Case(val name: String, val input: String, val expected: String)

    @TestFactory
    fun `typed-text golden corpus`(): List<DynamicTest> = CASES.map { case ->
        DynamicTest.dynamicTest(case.name) {
            assertEquals(case.expected, fix(case.input), "input: <${case.input}>")
        }
    }

    @Test
    fun `cleanup is idempotent`() {
        for (case in CASES) {
            val once = fix(case.input)
            assertEquals(once, fix(once), "not idempotent: <${case.input}>")
        }
    }

    // ------------------------------------------------------------ field kinds

    @Test
    fun `search fields keep query style`() {
        // VB-706: no terminal period in a search box. (Sentence casing is still
        // applied — FieldKind.SEARCH.allowsAutoCapitalize is true; the spec's
        // "relaxed capitalization" is not implemented in the Tier-1 engine.)
        assertEquals("Wireless earbuds under 100", fix("wireless earbuds under 100", FieldKind.SEARCH))
    }

    @Test
    fun `blank text is returned untouched`() {
        assertEquals("", fix(""))
        assertEquals("   ", fix("   "))
        assertEquals("\n\n", fix("\n\n"))
    }

    companion object {
        private val CASES = listOf(
            Case(
                "doubled word collapses",
                "i saw the the dog",
                "I saw the dog.",
            ),
            Case(
                "doubled bigram collapses",
                "i want i want to go home",
                "I want to go home.",
            ),
            Case(
                "legitimate double is kept",
                "that is very very good",
                "That is very very good.",
            ),
            Case(
                "missing sentence capital",
                "we should leave now",
                "We should leave now.",
            ),
            Case(
                "standalone i is capitalized",
                "tomorrow i will call and i'll explain",
                "Tomorrow I will call and I'll explain.",
            ),
            Case(
                "missing terminal punctuation",
                "the report is ready for review",
                "The report is ready for review.",
            ),
            Case(
                "question keeps a question mark",
                "what time does it start",
                "What time does it start?",
            ),
            Case(
                "run-on spacing collapses",
                "hello    world   again now",
                "Hello world again now.",
            ),
            Case(
                "space before punctuation is closed up",
                "yes , that works fine for me",
                "Yes, that works fine for me.",
            ),
            Case(
                "doubled punctuation collapses",
                "that is fine.. lets go now",
                "That is fine. Lets go now.",
            ),
            Case(
                "capitals mid-sentence per line",
                "hi there\nplease send the file over",
                "Hi there\nPlease send the file over.",
            ),
            Case(
                "blank lines and indentation survive",
                "first line\n\n  second line is indented",
                "First line\n\n  Second line is indented.",
            ),
            Case(
                "trailing newline survives",
                "one more thing to add\n",
                "One more thing to add.\n",
            ),
            Case(
                "two-word fragment gets no invented period",
                "on it",
                "On it",
            ),
        )
    }
}
