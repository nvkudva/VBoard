package com.vboard.core.qa

import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.FieldKind
import com.vboard.core.text.TranscriptCleaner
import com.vboard.core.text.UtteranceCommand
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage 3 (spoken punctuation) and stage 5 (self-correction) are the two stages
 * that can *delete words the user said*. Everything else in the pipeline either
 * adds punctuation or removes a closed vocabulary of hesitations.
 *
 * Both stages fire on surface form alone. Stage 3 converts a punctuation word to
 * its symbol unless the immediately preceding token is a determiner; stage 5
 * finds a marker phrase anywhere in the utterance and cuts everything back to the
 * alignment point. Neither consults a language model, a confidence score, or the
 * shape of the resulting sentence — so a large class of ordinary English
 * sentences is silently mangled.
 *
 * The utterances below are not adversarial constructions. Every one of them is
 * something a person plausibly dictates into a messaging app.
 */
class SpokenCommandSafetyQaTest {

    private val cleaner = TranscriptCleaner()

    private fun clean(text: String, options: CleanupOptions = CleanupOptions()) =
        cleaner.clean(CleanupRequest(text, "", FieldKind.TEXT, options, ensureTerminalPunctuation = true))

    private fun text(input: String) = clean(input).text

    // ------------------------------------------------ what the guards do catch

    @Test
    fun `the determiner guard protects the common false positives`() {
        assertEquals("Add a period at the end.", text("add a period at the end"))
        assertEquals("The comma is missing.", text("the comma is missing"))
        assertEquals("The dash cam footage.", text("the dash cam footage"))
        assertEquals("A colon and a semicolon.", text("a colon and a semicolon"))
        assertEquals("The ampersand key.", text("the ampersand key"))
        assertEquals("A hashtag for it.", text("a hashtag for it"))
    }

    @Test
    fun `the intended conversions still work`() {
        assertEquals("Hello, world", text("hello comma world"))
        assertEquals("Hello.\nWorld", text("hello period new line world"))
        assertEquals("Really?", text("really question mark"))
        assertEquals("Wow!", text("wow exclamation mark"))
    }

    @Test
    fun `whole-utterance commands are recognized regardless of casing and fillers`() {
        for (input in listOf("scratch that", "Scratch That", "SCRATCH THAT", "um scratch that um")) {
            assertEquals(UtteranceCommand.SCRATCH_THAT, clean(input).command, "for <$input>")
            assertEquals("", clean(input).text)
        }
        for (input in listOf("stop listening", "stop dictation", "stop dictating")) {
            assertEquals(UtteranceCommand.STOP_LISTENING, clean(input).command, "for <$input>")
        }
        // Embedded in a real sentence, they must NOT fire.
        assertEquals(UtteranceCommand.NONE, clean("please stop listening to him").command)
        assertEquals("Please stop listening to him.", text("please stop listening to him"))
    }

    // --------------------------------- VB-QA-18: punctuation words without a guard

    @Test
    fun `a punctuation word not preceded by a determiner is converted (pinned)`() {
        val pinned = mapOf(
            // Sentence-initial: nothing precedes, so the guard can never apply.
            "full stop the car" to "The car",
            "question mark placement" to "Placement",
            "new line of thinking" to "\nOf thinking",
            "open quote unquote" to "\"Unquote",
            "at sign up time" to "@Up time",
            // Mid-sentence with a non-determiner before it.
            "menstrual period tracking" to "Menstrual. Tracking",
            "use hashtag now" to "Use #now",
            "put comma here" to "Put, here",
            "use colon here" to "Use: here",
            "press dash now" to "Press - now",
            "on the next line item" to "On the\nItem.",
        )
        for ((input, expected) in pinned) {
            assertEquals(expected, text(input), "changed for <$input>")
        }
    }

    @Test
    @Disabled("VB-QA-18: spoken punctuation is substituted on surface form with only a preceding-determiner guard, so ordinary sentences containing 'period', 'comma', 'dash', 'full stop', 'question mark', 'new line', 'hashtag' are rewritten into punctuation")
    fun `an ordinary sentence containing a punctuation word should survive`() {
        assertEquals("Full stop the car.", text("full stop the car"))
        assertEquals("Question mark placement.", text("question mark placement"))
        assertEquals("Menstrual period tracking.", text("menstrual period tracking"))
        assertEquals("Use hashtag now.", text("use hashtag now"))
        assertEquals("On the next line item.", text("on the next line item"))
    }

    @Test
    @Disabled("VB-QA-18: a converted punctuation symbol is glued to the following word ('use hashtag now' -> 'Use #now') and can be dropped entirely when it lands at position 0")
    fun `a converted symbol should be spaced and never silently discarded`() {
        // Even when the conversion is what the user meant, the result must be
        // well-formed: "#" opens a tag so it attaches right, but "@"/quote
        // capitalizing the word they attach to is wrong, and a leading "." or "?"
        // being dropped by normalizePunctuationSequence loses the user's words.
        assertEquals("Use # now.", text("use hashtag now"))
        assertEquals("@ up time.", text("at sign up time"))
    }

    // ------------------------- VB-QA-19: "scratch that" eats the preceding clause

    @Test
    fun `scratch that mid-sentence deletes everything before it (pinned)`() {
        // No alignment for "itch", so the isScratch branch cuts back to the start
        // of the clause — which, with no punctuation in the utterance, is index 0.
        // Seven words in, one word out.
        assertEquals("Itch", text("tell him i need to scratch that itch"))
        // Marker at the end of the utterance: strong marker, drop everything.
        assertEquals("I need to.", text("i need to scratch that"))
        // Only the leading position is protected (findMarker requires i > 0).
        assertEquals("Scratch that itch.", text("scratch that itch"))
    }

    @Test
    @Disabled("VB-QA-19: 'scratch that' inside a sentence takes the isScratch branch and deletes back to the clause start, so 'tell him i need to scratch that itch' becomes 'Itch'")
    fun `scratch that used as ordinary English should not delete the sentence`() {
        assertEquals("Tell him I need to scratch that itch.", text("tell him i need to scratch that itch"))
        assertEquals("I need to scratch that.", text("i need to scratch that"))
    }

    // ------------------------------ VB-QA-20: "no wait" as a sentence-opening word

    @Test
    fun `no wait at the start of an utterance is treated as a correction marker (pinned)`() {
        // "no wait" has no i > 0 requirement, unlike "actually no", "scratch that"
        // and "make that", so an utterance that simply begins with it loses its
        // opening — and, because the remainder is short, its terminal punctuation.
        assertEquals("For me", text("no wait for me"))
        assertEquals("I am coming.", text("no wait i am coming"))
        assertEquals("I changed my mind.", text("wait no i changed my mind"))
        // The intended use, mid-utterance, is correct and must keep working.
        assertEquals("Call me at six.", text("call me at five no wait six"))
    }

    @Test
    @Disabled("VB-QA-20: findMarker (TranscriptCleaner.kt:283) accepts 'no wait'/'wait no' at index 0, unlike every other marker, so 'no wait for me' loses its first two words")
    fun `an utterance opening with no wait should keep its words`() {
        assertEquals("No wait for me.", text("no wait for me"))
        assertEquals("No wait I am coming.", text("no wait i am coming"))
        // Mid-utterance behaviour is unchanged.
        assertEquals("Call me at six.", text("call me at five no wait six"))
    }

    // ------------------------------------------------------------- invariants

    @Test
    fun `disabling spokenCommands makes every punctuation word inert`() {
        val options = CleanupOptions(spokenCommands = false)
        val inputs = listOf(
            "full stop the car", "question mark placement", "menstrual period tracking",
            "use hashtag now", "put comma here", "on the next line item", "press dash now",
        )
        for (input in inputs) {
            val out = clean(input, options).text
            assertTrue(
                out.none { it in ",;:#@\n-" },
                "spokenCommands=false still produced punctuation for <$input>: <$out>",
            )
            for (word in input.split(' ')) {
                assertTrue(word in out.lowercase(), "spokenCommands=false dropped <$word> from <$input>")
            }
        }
    }

    @Test
    fun `disabling resolveSelfCorrections keeps every word of the utterance`() {
        val options = CleanupOptions(resolveSelfCorrections = false)
        val inputs = listOf(
            "tell him i need to scratch that itch", "no wait for me",
            "call me at five no wait six", "meet me monday scratch that tuesday",
        )
        for (input in inputs) {
            val out = clean(input, options).text.lowercase()
            for (word in input.split(' ')) {
                assertTrue(word in out, "resolveSelfCorrections=false dropped <$word> from <$input>")
            }
            assertEquals(0, clean(input, options).correctionsResolved)
        }
    }

    @Test
    fun `a stage that deletes words always reports having done so`() {
        // The counters are the app's only signal that cleanup was destructive; a
        // silent deletion is the thing the "Cleaned" chip exists to disclose.
        val destructive = listOf(
            "tell him i need to scratch that itch",
            "no wait for me",
            "call me at five no wait six",
            "meet me monday scratch that tuesday",
            "the deal is off strike that we are back on",
        )
        for (input in destructive) {
            val result = clean(input)
            val inWords = input.split(' ').size
            val outWords = result.text.split(' ', '\n').filter { it.isNotBlank() }.size
            assertTrue(outWords < inWords, "expected <$input> to lose words")
            assertTrue(
                result.correctionsResolved > 0,
                "words were deleted from <$input> but correctionsResolved was 0",
            )
        }
    }

    @Test
    fun `a spoken command substitution is not reported by any counter`() {
        // Pinned gap, not a bug id of its own: stage 3 can delete a word (a
        // punctuation word becomes a symbol) and CleanupResult has no field that
        // says so. Anything building an "undo cleanup" affordance needs one.
        val result = clean("menstrual period tracking")
        assertEquals("Menstrual. Tracking", result.text)
        assertEquals(0, result.fillersRemoved)
        assertEquals(0, result.correctionsResolved)
        assertEquals(0, result.repetitionsCollapsed)
    }
}
