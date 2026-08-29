package com.vboard.core.text

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptCleanerTest {

    private val cleaner = TranscriptCleaner()

    private fun clean(
        transcript: String,
        preceding: String = "",
        options: CleanupOptions = CleanupOptions(),
        fieldKind: FieldKind = FieldKind.TEXT,
        terminal: Boolean = false,
    ): CleanupResult = cleaner.clean(
        CleanupRequest(
            transcript = transcript,
            precedingText = preceding,
            fieldKind = fieldKind,
            options = options,
            ensureTerminalPunctuation = terminal,
        ),
    )

    @Nested
    inner class SentenceStartAfterNewline {
        @Test
        fun `capitalizes the utterance after a spoken new line`() {
            // A plain trimEnd() also strips the newline, so this branch was unreachable
            // and the sentence after "new line" stayed lowercase.
            assertEquals(
                "Tuesday works for me.",
                clean("tuesday works for me", preceding = "See you then.\n", terminal = true).text,
            )
        }

        @Test
        fun `still capitalizes when the newline has trailing spaces`() {
            assertEquals(
                "Tuesday works for me.",
                clean("tuesday works for me", preceding = "See you then.\n  ", terminal = true).text,
            )
        }

        @Test
        fun `does not capitalize mid-sentence`() {
            assertEquals(
                "tuesday works for me.",
                clean("tuesday works for me", preceding = "I think ", terminal = true).text,
            )
        }
    }

    @Nested
    inner class Fillers {
        @Test
        fun `removes hesitation fillers`() {
            val result = clean("um so I was uh thinking we could uhm meet tomorrow")
            assertEquals("So I was thinking we could meet tomorrow", result.text)
            assertEquals(3, result.fillersRemoved)
        }

        @Test
        fun `removes filler with attached comma`() {
            assertEquals("Hello there", clean("um, hello there").text)
        }

        @Test
        fun `keeps you know unless aggressive`() {
            assertEquals("You know the answer", clean("you know the answer").text)
            val aggressive = clean(
                "it was you know really good",
                options = CleanupOptions(aggressiveFillers = true),
            )
            assertEquals("It was really good", aggressive.text)
        }

        @Test
        fun `utterance of only fillers becomes empty`() {
            assertEquals("", clean("um uh umm").text)
        }

        @Test
        fun `raw mode keeps fillers`() {
            assertEquals("um hello", clean("um hello", options = CleanupOptions.RAW).text)
        }
    }

    @Nested
    inner class SelfCorrections {
        @Test
        fun `no wait replaces aligned phrase`() {
            val result = clean("send it to john no wait to mary")
            assertEquals("Send it to mary", result.text)
            assertEquals(1, result.correctionsResolved)
        }

        @Test
        fun `no wait with commas around marker`() {
            assertEquals("Send it to mary", clean("send it to john, no wait, to mary").text)
        }

        @Test
        fun `wait no also works`() {
            assertEquals("Meet me on friday", clean("meet me on thursday wait no on friday").text)
        }

        @Test
        fun `i mean replaces aligned phrase`() {
            assertEquals("The red one", clean("the blue one I mean the red one").text)
        }

        @Test
        fun `i mean without alignment is preserved`() {
            assertEquals("I mean it sincerely", clean("I mean it sincerely").text)
        }

        @Test
        fun `make that swaps numbers`() {
            assertEquals("See you at 6", clean("see you at 5 make that 6").text)
            assertEquals("Order seven pizzas", clean("order three make that seven pizzas").text)
        }

        @Test
        fun `mid utterance scratch that drops the clause`() {
            assertEquals("Tell him wednesday", clean("tell him tuesday scratch that wednesday").text)
        }

        @Test
        fun `trailing no wait drops marker`() {
            assertEquals("Send it to john", clean("send it to john no wait").text)
        }

        @Test
        fun `fillers do not break alignment`() {
            assertEquals("Send it to mary", clean("send it to john um no wait to mary").text)
        }

        @Test
        fun `sorry without alignment is untouched`() {
            assertEquals("Sorry to hear that", clean("sorry to hear that").text)
        }

        @Test
        fun `sorry with alignment corrects`() {
            assertEquals("Meet me on friday", clean("meet me on thursday sorry on friday").text)
        }

        @Test
        fun `disabled corrections leave text alone`() {
            val options = CleanupOptions(resolveSelfCorrections = false)
            assertEquals(
                "Send it to john no wait to mary",
                clean("send it to john no wait to mary", options = options).text,
            )
        }
    }

    @Nested
    inner class Repetitions {
        @Test
        fun `collapses stuttered word`() {
            val result = clean("I want to to go home")
            assertEquals("I want to go home", result.text)
            assertEquals(1, result.repetitionsCollapsed)
        }

        @Test
        fun `collapses repeated bigram`() {
            assertEquals("I want to go", clean("I want I want to go").text)
        }

        @Test
        fun `keeps intentional repeats`() {
            assertEquals("It was very very good", clean("it was very very good").text)
            assertEquals("No no that's wrong", clean("no no that's wrong").text)
        }
    }

    @Nested
    inner class SpokenCommands {
        @ParameterizedTest
        @CsvSource(
            "'hello comma world', 'Hello, world'",
            "'are you coming question mark', 'Are you coming?'",
            "'stop exclamation mark', 'Stop!'",
            "'end of sentence period', 'End of sentence.'",
            "'wow ellipsis okay', 'Wow... okay'",
        )
        fun `converts spoken punctuation`(input: String, expected: String) {
            assertEquals(expected, clean(input).text)
        }

        @Test
        fun `new line and new paragraph become breaks`() {
            assertEquals("First line\nSecond line", clean("first line new line second line").text)
            assertEquals("First\n\nSecond", clean("first new paragraph second").text)
        }

        @Test
        fun `determiner guards the literal word`() {
            assertEquals("Add a comma here", clean("add a comma here").text)
            assertEquals("That period was rough", clean("that period was rough").text)
        }

        @Test
        fun `capitalizes after spoken sentence end`() {
            assertEquals("It works. It really does", clean("it works period it really does").text)
        }

        @Test
        fun `spoken commands survive raw mode`() {
            assertEquals("hello, world", clean("hello comma world", options = CleanupOptions.RAW).text)
        }

        @Test
        fun `commands can be disabled`() {
            val options = CleanupOptions(spokenCommands = false)
            assertEquals("Hello comma world", clean("hello comma world", options = options).text)
        }
    }

    @Nested
    inner class UtteranceCommands {
        @Test
        fun `scratch that alone is a command`() {
            val result = clean("scratch that")
            assertEquals(UtteranceCommand.SCRATCH_THAT, result.command)
            assertEquals("", result.text)
        }

        @Test
        fun `scratch that with hesitation still a command`() {
            assertEquals(UtteranceCommand.SCRATCH_THAT, clean("um scratch that").command)
        }

        @Test
        fun `stop listening is a command`() {
            assertEquals(UtteranceCommand.STOP_LISTENING, clean("stop listening").command)
        }

        @Test
        fun `stop listening embedded in sentence is not a command`() {
            val result = clean("I told him to stop listening")
            assertEquals(UtteranceCommand.NONE, result.command)
            assertEquals("I told him to stop listening", result.text)
        }

        @Test
        fun `commands work even in raw mode`() {
            assertEquals(
                UtteranceCommand.SCRATCH_THAT,
                clean("scratch that", options = CleanupOptions.RAW).command,
            )
        }
    }

    @Nested
    inner class Capitalization {
        @Test
        fun `capitalizes at start of empty field`() {
            assertEquals("Hello world", clean("hello world").text)
        }

        @Test
        fun `does not capitalize mid sentence`() {
            assertEquals("and then we left", clean("and then we left", preceding = "We got food").text)
        }

        @Test
        fun `capitalizes after sentence end in preceding text`() {
            assertEquals("Hello", clean("hello", preceding = "That was it. ").text)
        }

        @Test
        fun `capitalizes standalone i and contractions`() {
            assertEquals("you and I know I'm right", clean("you and i know i'm right", preceding = "so ").text)
        }

        @Test
        fun `email fields are not capitalized`() {
            assertEquals("john", clean("john", fieldKind = FieldKind.EMAIL).text)
        }

        @Test
        fun `capitalizes after inline punctuation`() {
            assertEquals("Yes. We should", clean("yes. we should").text)
        }
    }

    @Nested
    inner class TerminalPunctuation {
        @Test
        fun `appends period to finalized text utterance`() {
            assertEquals("Let's meet at noon.", clean("let's meet at noon", terminal = true).text)
        }

        @Test
        fun `does not double punctuation`() {
            assertEquals("Are we done?", clean("are we done?", terminal = true).text)
        }

        @Test
        fun `short interjections get no period`() {
            assertEquals("Okay", clean("okay", terminal = true).text)
        }

        @Test
        fun `search fields never get terminal period`() {
            assertEquals(
                "Coffee shops near me",
                clean("coffee shops near me", fieldKind = FieldKind.SEARCH, terminal = true).text,
            )
        }
    }

    @Nested
    inner class Robustness {
        @Test
        fun `empty input yields empty result`() {
            assertEquals("", clean("").text)
            assertEquals("", clean("   ").text)
        }

        @Test
        fun `strips asr artifacts`() {
            assertEquals("Hello world", clean("hello <unk> world [noise]").text)
        }

        @Test
        fun `pipeline is idempotent`() {
            val once = clean("um send it to john no wait to mary period")
            val twice = clean(once.text, preceding = "")
            assertEquals(once.text, twice.text)
        }

        @Test
        fun `combined stress case`() {
            val result = clean(
                "um so tell tell sarah I'll be there at 7 uh make that 8 comma and bring the the slides",
            )
            assertEquals("So tell sarah I'll be there at 8, and bring the slides", result.text)
            assertTrue(result.fillersRemoved >= 2)
            assertEquals(1, result.correctionsResolved)
            assertTrue(result.repetitionsCollapsed >= 2)
        }

        @Test
        fun `curly apostrophes are normalized`() {
            assertEquals("Don't stop", clean("don’t stop").text)
        }
    }
}
