package com.vboard.core.qa

import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.CleanupResult
import com.vboard.core.text.FieldKind
import com.vboard.core.text.TranscriptCleaner
import com.vboard.core.text.UtteranceCommand
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Property-style checks for the Tier-1 cleanup engine:
 *
 *  - idempotency (VB-206): clean(clean(x)) == clean(x)
 *  - totality: cleanup never throws, on anything
 *  - output hygiene: no double spaces, no leading/trailing spaces
 *  - raw mode (VB-220): bypasses every stage except spoken + whole-utterance commands
 *  - option independence: each disabled CleanupOptions flag removes exactly that stage
 */
class CleanupPropertyTest {

    private val cleaner = TranscriptCleaner()

    private fun clean(
        transcript: String,
        preceding: String = "",
        options: CleanupOptions = CleanupOptions(),
        fieldKind: FieldKind = FieldKind.TEXT,
        terminal: Boolean = true,
    ): CleanupResult = cleaner.clean(CleanupRequest(transcript, preceding, fieldKind, options, terminal))

    /** ~30 adversarial inputs. The three known idempotency breakers (VB-QA-05) live separately. */
    private val adversarial = listOf(
        "",
        "   ",
        "\t \t",
        "um uh er hmm mm mhm",
        "period period period",
        "comma",
        ", , , . . .",
        "no wait no wait",
        "i mean i mean i mean",
        "make that make that",
        "sorry sorry sorry",
        "“hello” she said — with feeling…",
        "hello 👋 world 🎉",
        "HELLO WORLD THIS IS ALL CAPS",
        "MiXeD CaSe AsR OuTpUt hello World",
        "the the the the the",
        "very very very very",
        "open quote open quote close quote",
        "question mark question mark",
        "a b c d e f g",
        "one",
        "ok",
        "I'll i'll I'LL i'm i'd i've",
        "don't can't won't shouldn't've",
        "call me at five five five one two one two",
        "5 5 5 1 2 1 2",
        "tab\tseparated\ttext",
        "line1\nline2\r\nline3",
        "trailing spaces   ",
        "   leading spaces",
        "dash dash dash",
        "at sign at sign",
        "<unk> [noise] (laughter) hello",
        "um scratch um that um",
    )

    private val allInputs: List<String>
        get() = CleanupGoldenCorpusTest.CASES.map { it.input } + adversarial + listOf(longUtterance())

    /** A realistic ~200-word run-on dictation with scattered fillers and stutters. */
    private fun longUtterance(): String {
        val rnd = Random(20260828)
        val vocab = listOf(
            "project", "timeline", "budget", "meeting", "review", "draft", "launch",
            "customer", "feedback", "release", "metrics", "roadmap", "design", "voice",
            "keyboard", "model", "update", "quarter", "milestone", "planning",
        )
        val sb = StringBuilder()
        repeat(200) { i ->
            if (i % 23 == 7) sb.append("um ")
            if (i % 41 == 13) sb.append("uh ")
            sb.append(vocab[rnd.nextInt(vocab.size)])
            if (i % 17 == 5) sb.append(" comma")
            sb.append(' ')
        }
        return sb.toString().trim()
    }

    // ------------------------------------------------------------ idempotency

    @Test
    fun `clean is idempotent over the golden corpus`() {
        for (case in CleanupGoldenCorpusTest.CASES) {
            val once = clean(case.input, case.preceding, fieldKind = case.fieldKind, terminal = case.terminal)
            val twice = clean(once.text, case.preceding, fieldKind = case.fieldKind, terminal = case.terminal)
            assertEquals(once.text, twice.text, "not idempotent for input: <${case.input}>")
        }
    }

    @Test
    fun `clean is idempotent over adversarial inputs`() {
        for (input in adversarial + listOf(longUtterance())) {
            val once = clean(input)
            val twice = clean(once.text)
            assertEquals(once.text, twice.text, "not idempotent for input: <$input>")
            // A cleaned utterance must not morph into a command on re-cleaning.
            assertEquals(
                UtteranceCommand.NONE, twice.command,
                "re-cleaning output of <$input> produced command ${twice.command}",
            )
        }
    }

    @Test
    @Disabled("VB-QA-05: idempotency violations (VB-206) - stacked markers, command re-trigger, break collapse")
    fun `clean is idempotent over stacked-marker and break inputs`() {
        // (a) 5 stacked markers exhaust the 4-iteration guard; the leftover "No wait"
        //     is removed by a second pass. (b) "scratch that scratch that" cleans to
        //     the TEXT "Scratch that"; re-cleaning that output yields a SCRATCH_THAT
        //     command - on the VB-124 double-cleanup fallback path this would delete
        //     a previously committed utterance. (c) "\n\n\n" re-tokenizes to "\n\n".
        for (input in listOf(
            "no wait no wait no wait no wait no wait",
            "scratch that scratch that",
            "new line new line new line",
        )) {
            val once = clean(input)
            val twice = clean(once.text)
            assertEquals(once.text, twice.text, "not idempotent for input: <$input>")
            assertEquals(UtteranceCommand.NONE, twice.command, "command re-trigger for input: <$input>")
        }
    }

    // ------------------------------------------------------------ totality & hygiene

    @Test
    fun `cleanup never throws and output has clean spacing, on everything`() {
        val fieldKinds = FieldKind.entries
        for (input in allInputs) {
            for (kind in fieldKinds) {
                for (terminal in listOf(true, false)) {
                    val result = assertDoesNotThrow("threw for <$input> kind=$kind") {
                        clean(input, fieldKind = kind, terminal = terminal)
                    }
                    assertSpacingHygiene(result.text, input)
                }
            }
        }
    }

    @Test
    fun `cleanup never throws on seeded random garbage`() {
        val rnd = Random(4242)
        val alphabet = "abc defgh ijklmnop.,!?;:\"'()-’“”…\n\t😀é你 um uh "
        val commandWords = listOf(
            "period", "comma", "new", "line", "paragraph", "no", "wait", "scratch",
            "that", "i", "mean", "make", "sorry", "question", "mark", "open", "quote",
        )
        repeat(300) {
            val sb = StringBuilder()
            val len = rnd.nextInt(0, 60)
            repeat(len) {
                if (rnd.nextInt(4) == 0) {
                    sb.append(commandWords[rnd.nextInt(commandWords.size)]).append(' ')
                } else {
                    sb.append(alphabet[rnd.nextInt(alphabet.length)])
                }
            }
            val input = sb.toString()
            val result = assertDoesNotThrow("threw for fuzzed input <$input>") { clean(input) }
            assertSpacingHygiene(result.text, input)
        }
    }

    private fun assertSpacingHygiene(text: String, input: String) {
        assertFalse(text.contains("  "), "double space in <$text> from <$input>")
        assertEquals(text.trim(' '), text, "leading/trailing space in <$text> from <$input>")
        assertFalse(text.contains(" \n"), "space before newline in <$text> from <$input>")
        assertFalse(text.contains("\n "), "space after newline in <$text> from <$input>")
    }

    // ------------------------------------------------------------ raw mode (VB-220)

    @Test
    fun `raw mode keeps fillers corrections repetitions casing and punctuation`() {
        val raw = CleanupOptions.RAW
        // Already-clean text passes through byte-identical.
        assertEquals("hello world", clean("hello world", options = raw).text)
        // Fillers kept.
        assertEquals("um so uh hello", clean("um so uh hello", options = raw).text)
        // Correction markers kept.
        assertEquals(
            "send it to john no wait to mary",
            clean("send it to john no wait to mary", options = raw).text,
        )
        // Repetitions kept.
        assertEquals("the the the", clean("the the the", options = raw).text)
        // No sentence capitalization, no terminal period.
        assertEquals("this stays lowercase", clean("this stays lowercase", options = raw).text)
    }

    @Test
    fun `raw mode still applies spoken and whole-utterance commands`() {
        val raw = CleanupOptions.RAW
        assertEquals("hello, world", clean("hello comma world", options = raw).text)
        assertEquals("first\nsecond", clean("first new line second", options = raw).text)
        assertEquals(UtteranceCommand.SCRATCH_THAT, clean("scratch that", options = raw).command)
        assertEquals(UtteranceCommand.STOP_LISTENING, clean("stop listening", options = raw).command)
    }

    @Test
    fun `raw mode reports zero transformations`() {
        val result = clean("um the the thing no wait stuff", options = CleanupOptions.RAW)
        assertEquals(0, result.fillersRemoved)
        assertEquals(0, result.correctionsResolved)
        assertEquals(0, result.repetitionsCollapsed)
    }

    // NOTE (VB-QA-04, documented in docs/QA_REPORT.md): raw mode is not fully
    // verbatim - standalone "i"/"i'm"/"i'll"/"i'd"/"i've" are still capitalized
    // and tokenization normalizes whitespace/curly quotes and drops emoji. The
    // assertion below pins that behavior so a fix (or a product decision) is a
    // visible diff.
    @Test
    fun `raw mode still capitalizes standalone i (VB-QA-04 pinned behavior)`() {
        assertEquals("I think I'm ok", clean("i think i'm ok", options = CleanupOptions.RAW).text)
    }

    // ------------------------------------------------------------ option independence

    @Test
    fun `removeFillers=false keeps hesitations and nothing else changes stage-wise`() {
        val options = CleanupOptions(removeFillers = false)
        val result = clean("um hello there", options = options)
        assertEquals("Um hello there.", result.text)
        assertEquals(0, result.fillersRemoved)
    }

    @Test
    fun `resolveSelfCorrections=false keeps correction markers`() {
        val options = CleanupOptions(resolveSelfCorrections = false)
        val result = clean("send it to john no wait to mary", options = options)
        assertEquals("Send it to john no wait to mary.", result.text)
        assertEquals(0, result.correctionsResolved)
    }

    @Test
    fun `collapseRepetitions=false keeps stutters`() {
        val options = CleanupOptions(collapseRepetitions = false)
        val result = clean("the the meeting moved", options = options)
        assertEquals("The the meeting moved.", result.text)
        assertEquals(0, result.repetitionsCollapsed)
    }

    @Test
    fun `autoPunctuate=false never appends a terminal period`() {
        val options = CleanupOptions(autoPunctuate = false)
        assertEquals("Hello there my friend", clean("hello there my friend", options = options).text)
    }

    @Test
    fun `autoCapitalize=false keeps sentence case except the pronoun I`() {
        val options = CleanupOptions(autoCapitalize = false)
        // The pronoun "i" is still fixed by the always-on standalone-I pass.
        assertEquals("hello world I said.", clean("hello world i said", options = options).text)
    }

    @Test
    fun `spokenCommands=false leaves command words as content`() {
        val options = CleanupOptions(spokenCommands = false)
        assertEquals("Hello comma world period.", clean("hello comma world period", options = options).text)
    }

    @Test
    fun `aggressiveFillers=false keeps discourse fillers and true removes them`() {
        assertEquals(
            "It was you know basically fine.",
            clean("it was you know basically fine").text,
        )
        assertEquals(
            "It was fine.",
            clean("it was you know basically fine", options = CleanupOptions(aggressiveFillers = true)).text,
        )
    }

    @Test
    fun `each single disabled option still yields a total, hygienic function`() {
        val toggles: List<CleanupOptions> = listOf(
            CleanupOptions(removeFillers = false),
            CleanupOptions(aggressiveFillers = true),
            CleanupOptions(resolveSelfCorrections = false),
            CleanupOptions(collapseRepetitions = false),
            CleanupOptions(autoPunctuate = false),
            CleanupOptions(autoCapitalize = false),
            CleanupOptions(spokenCommands = false),
            CleanupOptions.RAW,
        )
        for (options in toggles) {
            for (input in allInputs) {
                val result = assertDoesNotThrow("threw for <$input> with $options") {
                    clean(input, options = options)
                }
                assertSpacingHygiene(result.text, input)
            }
        }
    }

    @Test
    fun `blank input maps to empty output for every option set`() {
        for (options in listOf(CleanupOptions(), CleanupOptions.RAW, CleanupOptions(autoPunctuate = false))) {
            assertEquals("", clean("", options = options).text)
            assertEquals("", clean("   ", options = options).text)
            assertEquals("", clean("\t \t", options = options).text)
        }
    }

    @Test
    fun `filler-only utterance is empty, never a stray period`() {
        val result = clean("um uh umm er")
        assertEquals("", result.text)
        assertTrue(result.fillersRemoved >= 4)
    }
}
