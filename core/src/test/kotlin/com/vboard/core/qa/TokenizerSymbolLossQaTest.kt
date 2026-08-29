package com.vboard.core.qa

import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.FieldKind
import com.vboard.core.text.Tok
import com.vboard.core.text.Tokenizer
import com.vboard.core.text.TranscriptCleaner
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Tokenizer` recognizes exactly the punctuation in its `PUNCT_CHARS` string and
 * **silently deletes** every other symbol (`Tokens.kt:77`,
 * `else -> flushWord() // drop unrecognized symbols from ASR output`).
 *
 * VB-QA-12 already pinned the `$` case. This file pins the rest of the family,
 * because they are not one bug each: they are one policy applied to every symbol
 * outside a 17-character allow-list, and a fix has to change the policy.
 *
 * The deletion is not even a plain deletion. `flushWord()` also *ends the current
 * word*, so `under_score` does not become `underscore`, it becomes two words.
 * Any symbol the tokenizer does not know both disappears and inserts a space.
 *
 * A second, unrelated deleter lives upstream: `TranscriptCleaner.ARTIFACT_REGEX`
 * (`TranscriptCleaner.kt:491`) removes `[...]` spans intended to catch ASR tags
 * like `[music]`, and it cannot tell those from an ordinary bracketed aside.
 */
class TokenizerSymbolLossQaTest {

    private val cleaner = TranscriptCleaner()

    private fun clean(text: String): String = cleaner
        .clean(CleanupRequest(text, "", FieldKind.TEXT, CleanupOptions(), ensureTerminalPunctuation = true))
        .text

    // ------------------------------------------------------------- what survives

    @Test
    fun `the recognized punctuation set has not silently widened`() {
        // A canary: if somebody widens PUNCT_CHARS, the disabled tests below should
        // be revisited rather than left rotting.
        val survives = ".,!?;:\"&@#%()".toList().filter { ch ->
            Tokenizer.tokenize("a${ch}b").any { it is Tok.Punct }
        }
        assertEquals(13, survives.size, "recognized punctuation changed: $survives")
    }

    @Test
    fun `curly quotes and em dashes are normalized rather than dropped`() {
        assertEquals("He said \"hi\" loudly.", clean("he said “hi” loudly"))
        assertEquals("Yes - really", clean("yes — really"))
        assertEquals("Wait... ok", clean("wait … ok"))
    }

    // -------------------------------------------------- VB-QA-13: symbol deletion

    /**
     * Current behaviour, pinned. Every one of these is a silent content change the
     * user never asked for; several change the *meaning* of the text.
     */
    @Test
    fun `unrecognized symbols are deleted and split the surrounding word (pinned)`() {
        val pinned = mapOf(
            // currency: the amount survives, the unit does not
            "it costs $75 dollars" to "It costs 75 dollars.",
            "the cost is €40" to "The cost is 40.",
            "£20 please now" to "20 please now.",
            "¥300 yen total" to "300 yen total.",
            "₹500 rupees now" to "500 rupees now.",
            // mathematics: the operator vanishes, so the statement inverts or dissolves
            "a + b = c" to "A b c.",
            "5 * 3 equals" to "5 3 equals.",
            "x^2 plus y" to "X 2 plus y.",
            "the ± range" to "The range",
            "temp is 20° today" to "Temp is 20 today.",
            // structure: fractions, paths, identifiers, code
            "half is 1/2 cup" to "Half is 1 2 cup.",
            "path\\to\\file here" to "Path to file here.",
            "under_score name here" to "Under score name here.",
            "C++ code here" to "C code here.",
            "a|b|c here" to "A b c here.",
            "less < more > than" to "Less more than.",
            "star *bold* text" to "Star bold text.",
            "back`tick here" to "Back tick here.",
            // an email address, which is the single most common thing dictated
            // into a text field after a phone number
            "email me at a_b@c.com" to "Email me at a b @c. Com.",
        )
        for ((input, expected) in pinned) {
            assertEquals(expected, clean(input), "changed for <$input>")
        }
    }

    @Test
    @Disabled("VB-QA-13: Tokenizer drops every symbol outside PUNCT_CHARS (Tokens.kt:77) - currency, math and structural characters are deleted from the user's text")
    fun `symbols the user dictated should survive cleanup`() {
        assertEquals("It costs $75 dollars.", clean("it costs $75 dollars"))
        assertEquals("The cost is €40.", clean("the cost is €40"))
        assertEquals("A + b = c.", clean("a + b = c"))
        assertEquals("Half is 1/2 cup.", clean("half is 1/2 cup"))
        assertEquals("Under_score name here.", clean("under_score name here"))
        assertEquals("Email me at a_b@c.com.", clean("email me at a_b@c.com"))
    }

    @Test
    @Disabled("VB-QA-13: an unrecognized symbol also calls flushWord(), so it splits the word it sits inside instead of merely disappearing")
    fun `deleting a symbol should not split the word around it`() {
        // Even if the product decides symbols may be dropped, dropping one must not
        // manufacture a word boundary that was never spoken.
        assertEquals("Underscore.", clean("under_score"))
        assertEquals("Ab.", clean("a¦b"))
    }

    // ------------------------------------------- VB-QA-21: bracketed prose deleted

    @Test
    fun `the ASR artifact scrubber removes any bracketed lowercase phrase (pinned)`() {
        // Intended targets — correct.
        assertEquals("Hello", clean("<unk> hello"))
        assertEquals("Hello there", clean("(noise) hello there"))
        assertEquals("Plays now", clean("[music] plays now"))
        // Collateral damage: ordinary prose in brackets, gone without trace.
        assertEquals("See for details.", clean("see [see attached] for details"))
        assertEquals("The is here.", clean("the [box] is here"))
        assertEquals("Meet me at later.", clean("meet me at [the park] later"))
        assertEquals("Read now", clean("read [chapter one] now"))
        // Anything with a digit inside escapes, which is the only reason
        // "[b1]" survives. That is an accident, not a rule.
        assertEquals("A b1 here.", clean("a [b1] here"))
    }

    @Test
    @Disabled("VB-QA-21: ARTIFACT_REGEX (TranscriptCleaner.kt:491) matches [a-z_ ]+ inside brackets, so an ordinary bracketed aside is deleted as if it were an ASR tag")
    fun `bracketed prose the user dictated should survive`() {
        assertEquals("See [see attached] for details.", clean("see [see attached] for details"))
        assertEquals("Meet me at [the park] later.", clean("meet me at [the park] later"))
        // The known ASR tag vocabulary is small and closed; it should be matched
        // as a vocabulary, not as "any lowercase word in brackets".
        assertEquals("Plays now", clean("[music] plays now"))
    }

    // --------------------------------------------------------------- invariants

    @Test
    fun `every digit in the input survives cleanup`() {
        // Digits are the one class the tokenizer never drops, and phone numbers,
        // amounts and codes depend on it. This is the guarantee VB-QA-13 does NOT
        // break, and it is worth a standing check.
        val inputs = listOf(
            "call me at 555 1212 today", "it costs $75 dollars", "half is 1/2 cup",
            "the code is 4 8 15 16 23 42", "meet at 9 30 sharp", "₹500 rupees now",
        )
        for (input in inputs) {
            val inDigits = input.filter { it.isDigit() }
            val outDigits = clean(input).filter { it.isDigit() }
            assertEquals(inDigits, outDigits, "digits changed for <$input>")
        }
    }

    @Test
    fun `cleanup never introduces a character class the input did not contain`() {
        // The only characters cleanup is allowed to *add* are the ones it is
        // documented to add: sentence punctuation, spaces and newlines.
        val allowedNew = ".,!?;:\"'()#@%- \n".toSet()
        val inputs = listOf(
            "it costs $75 dollars", "a + b = c", "hello 👋 world",
            "under_score name here", "path\\to\\file here", "café is open now",
            "مرحبا بالعالم اليوم",
        )
        for (input in inputs) {
            val seen = input.toSet()
            val added = clean(input).toSet() - seen - allowedNew
            // Case changes are expected; compare case-insensitively.
            val realAdded = added.filterNot { it.lowercaseChar() in seen }
            assertTrue(realAdded.isEmpty(), "cleanup invented $realAdded for <$input>")
        }
    }
}
