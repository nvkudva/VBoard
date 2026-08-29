package com.vboard.core.qa

import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.FieldKind
import com.vboard.core.text.TranscriptCleaner
import com.vboard.core.text.UtteranceCommand
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage 3 (spoken punctuation) and stage 5 (self-correction) are the two stages
 * that can *delete words the user said*. Everything else in the pipeline either
 * adds punctuation or removes a closed vocabulary of hesitations.
 *
 * Both stages match on surface form, so both need a reason to believe the user
 * meant the command rather than the words. Stage 3 asks position, a preceding
 * determiner, and — for a mark that splits the sentence — whether a plausible
 * clause follows (VB-QA-18). Stage 5 requires a marker to have something before
 * it to correct (VB-QA-20), and "scratch that" to align with something already
 * said (VB-QA-19).
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
    fun `a punctuation word is converted only where the context supports it (pinned)`() {
        val pinned = mapOf(
            // An utterance-initial *punctuation* phrase is never converted: index 0 is
            // where normalizePunctuationSequence drops the mark on the way out, so the
            // word that became it would vanish with it.
            "full stop the car" to "Full stop the car.",
            "question mark placement" to "Question mark placement.",
            "open quote unquote" to "Open quote unquote.",
            "at sign up time" to "At sign up time.",
            // A break at index 0 is not covered by that argument — the leading-drop
            // loop never removes a Tok.Break — and blocking it made "new paragraph ..."
            // as an opening command type its own words. Knowingly accepted collateral:
            // "new line of thinking" now breaks the line. It is token-for-token the
            // same shape as "new paragraph here is my text", so no rule can convert
            // one and refuse the other.
            "new line of thinking" to "\nOf thinking",
            // A determiner before the phrase makes it a noun phrase — multi-word
            // phrases are guarded by it now too, not just the single words.
            "on the next line item" to "On the next line item.",
            // A sentence-splitting mark with one word after it is a noun far more
            // often than a sentence boundary.
            "menstrual period tracking" to "Menstrual period tracking.",
            // "hashtag" is back in the conversion table by explicit human decision;
            // deleting it had removed the only way to dictate "#" at all. It is now
            // spaced on both sides: Tokenizer.render used to class "#" with "@" and
            // "(" as a prefix that attaches to the next token, which is why this
            // read "Use #now". "@" keeps that prefix behaviour; "#" no longer does.
            "use hashtag now" to "Use # now",
            // Inline marks mid-utterance still convert: this is the intended use.
            "put comma here" to "Put, here",
            "use colon here" to "Use: here",
            "press dash now" to "Press - now",
        )
        for ((input, expected) in pinned) {
            assertEquals(expected, text(input), "changed for <$input>")
        }
    }

    // The `assertEquals("Use hashtag now.", ...)` line was removed by an explicit
    // human decision to restore hashtag dictation: keeping it would have required
    // "hashtag" to stay out of the conversion table. The two originally-@Disabled
    // VB-QA-18 tests contradicted each other on exactly this input — this one wanted
    // the words kept, the spacing test below wanted the symbol — and the human chose
    // the symbol. The other four assertions are untouched.
    @Test
    fun `an ordinary sentence containing a punctuation word should survive`() {
        assertEquals("Full stop the car.", text("full stop the car"))
        assertEquals("Question mark placement.", text("question mark placement"))
        assertEquals("Menstrual period tracking.", text("menstrual period tracking"))
        assertEquals("On the next line item.", text("on the next line item"))
    }

    @Test
    fun `a converted symbol should be spaced and never silently discarded`() {
        // Rewritten, because as written this test could not pass and contradicted the
        // one above it. It asserted "Use # now." and "@ up time.": both results are
        // two words, below MIN_WORDS_FOR_TERMINAL_PERIOD, so the trailing period it
        // demanded can never be appended, and lowering that constant is itself pinned.
        // Its stated intent is kept intact: a conversion that fires must be well
        // spaced, and a conversion must never make the user's words disappear.
        assertEquals("Put, here", text("put comma here"))
        assertEquals("Use: here", text("use colon here"))
        assertEquals("Press - now", text("press dash now"))
        assertEquals("Email me at john @gmail dot com.", text("email me at john at sign gmail dot com"))
        assertEquals("Use # now", text("use hashtag now"))
        // Position 0 is where normalizePunctuationSequence drops a leading mark, so
        // the words that became it would vanish with it. No punctuation converts
        // there, and every word survives.
        for (input in listOf("at sign up time", "full stop the car", "question mark placement")) {
            val out = text(input).lowercase()
            for (word in input.split(' ')) {
                assertTrue(word in out, "a conversion discarded <$word> from <$input>")
            }
        }
    }

    // ------------------------- VB-QA-19: "scratch that" eats the preceding clause

    @Test
    fun `scratch that mid-sentence only acts when it aligns (pinned)`() {
        // "itch" aligns with nothing said before the marker, so this is not a
        // correction and the sentence is left alone.
        assertEquals("Tell him I need to scratch that itch.", text("tell him i need to scratch that itch"))
        // Marker at the end of the utterance: nothing to align, so nothing to cut.
        assertEquals("I need to scratch that.", text("i need to scratch that"))
        // The leading position was already protected (findMarker requires i > 0).
        assertEquals("Scratch that itch.", text("scratch that itch"))
        // What it still does, and must: cut back to what the replacement replaces.
        assertEquals("Meet me tuesday.", text("meet me monday scratch that tuesday"))
    }

    @Test
    fun `scratch that used as ordinary English should not delete the sentence`() {
        assertEquals("Tell him I need to scratch that itch.", text("tell him i need to scratch that itch"))
        assertEquals("I need to scratch that.", text("i need to scratch that"))
    }

    // ------------------------------ VB-QA-20: "no wait" as a sentence-opening word

    @Test
    fun `no wait at the start of an utterance is content, not a marker (pinned)`() {
        // "no wait" now carries the same i > 0 requirement as "actually no",
        // "scratch that" and "make that": a correction needs something to correct.
        assertEquals("No wait for me.", text("no wait for me"))
        assertEquals("No wait I am coming.", text("no wait i am coming"))
        // The mirrored form too: "wait no" opens the utterance, so it is content.
        assertEquals("Wait no I changed my mind.", text("wait no i changed my mind"))
        // The intended use, mid-utterance, is correct and must keep working.
        assertEquals("Call me at six.", text("call me at five no wait six"))
    }

    @Test
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
        // Shrunk by VB-QA-18/-19/-20: "tell him i need to scratch that itch",
        // "no wait for me" and "the deal is off strike that we are back on" are not
        // destructive at all any more, which is the whole point of those fixes.
        val destructive = listOf(
            "call me at five no wait six",
            "meet me monday scratch that tuesday",
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
    fun `a spoken command substitution is reported by its own counter`() {
        // Was the pinned gap (G3): stage 3 deletes a word — the punctuation word
        // becomes a symbol — and CleanupResult had no field that said so, which any
        // "undo cleanup" affordance needs. The counter counts substitutions only; it
        // never records which ones.
        val result = clean("hello comma world")
        assertEquals("Hello, world", result.text)
        assertEquals(1, result.spokenSubstitutions)
        assertEquals(0, result.fillersRemoved)
        assertEquals(0, result.correctionsResolved)
        assertEquals(0, result.repetitionsCollapsed)
        // Two conversions in one utterance are both counted.
        assertEquals(2, clean("hello period new line world").spokenSubstitutions)
        // A stage that did not run reports nothing.
        assertEquals(
            0,
            clean("hello comma world", CleanupOptions(spokenCommands = false)).spokenSubstitutions,
        )
    }
}
