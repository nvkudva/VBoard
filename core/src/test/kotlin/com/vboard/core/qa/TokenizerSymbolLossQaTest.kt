package com.vboard.core.qa

import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.FieldKind
import com.vboard.core.text.Tok
import com.vboard.core.text.Tokenizer
import com.vboard.core.text.TranscriptCleaner
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Symbol fidelity through the tokenizer (VB-QA-12, -13, -21; Package A).
 *
 * `Tokenizer` used to recognize exactly the punctuation in a `PUNCT_CHARS` string
 * and silently delete every other symbol — and not even as a plain deletion, since
 * the `else` branch also called `flushWord()`, so `under_score` became two words
 * rather than one. Currency, math operators, paths, identifiers and email
 * addresses all lost characters the user dictated.
 *
 * The policy is now deny-list-drop: a symbol is carried through inside the word
 * unless it is on the small closed artifact list, and structural punctuation with
 * a word character on both sides is treated as intra-word.
 *
 * A second, unrelated deleter lives upstream: `TranscriptCleaner.ARTIFACT_REGEX`
 * matched `[...]` spans intended to catch ASR tags like `[music]` and could not
 * tell those from an ordinary bracketed aside (VB-QA-21). It now matches a closed
 * label vocabulary for the bracket and paren forms.
 */
class TokenizerSymbolLossQaTest {

    private val cleaner = TranscriptCleaner()

    private fun clean(text: String): String = cleaner
        .clean(CleanupRequest(text, "", FieldKind.TEXT, CleanupOptions(), ensureTerminalPunctuation = true))
        .text

    // ------------------------------------------------------------- what survives

    @Test
    fun `the structural punctuation set has not silently changed`() {
        // A canary over the characters later stages align on, collapse or attach.
        // The probe is space-separated on purpose: punctuation with a word
        // character on BOTH sides is intra-word ("a_b@c.com"), so "a.b" is one
        // word rather than a Punct token.
        val survives = ".,!?;:\"&@#%()".toList().filter { ch ->
            Tokenizer.tokenize("a $ch b").any { it is Tok.Punct }
        }
        assertEquals(13, survives.size, "structural punctuation changed: $survives")
        // The intra-word rule, stated as an assertion rather than as a comment.
        assertEquals(listOf<Tok>(Tok.Word("a.b")), Tokenizer.tokenize("a.b"))
    }

    @Test
    fun `curly quotes and em dashes are normalized rather than dropped`() {
        assertEquals("He said \"hi\" loudly.", clean("he said “hi” loudly"))
        assertEquals("Yes - really", clean("yes — really"))
        assertEquals("Wait... ok", clean("wait … ok"))
    }

    // -------------------------------------------------- VB-QA-13: symbol deletion

    /**
     * The family VB-QA-13 covers, now asserted the other way round: the policy is
     * deny-list-drop, so a symbol the tokenizer has no opinion about is carried
     * through inside the word instead of deleted.
     */
    @Test
    fun `symbols outside the structural set are carried through`() {
        val cases = mapOf(
            // currency: the amount and its unit both survive
            "it costs $75 dollars" to "It costs $75 dollars.",
            "the cost is €40" to "The cost is €40.",
            "£20 please now" to "£20 please now.",
            "¥300 yen total" to "¥300 yen total.",
            "₹500 rupees now" to "₹500 rupees now.",
            // mathematics: the operator is what carries the meaning
            "a + b = c" to "A + b = c.",
            "5 * 3 equals" to "5 * 3 equals.",
            "x^2 plus y" to "X^2 plus y.",
            "the ± range" to "The ± range.",
            "temp is 20° today" to "Temp is 20° today.",
            // structure: fractions, paths, identifiers, code
            "half is 1/2 cup" to "Half is 1/2 cup.",
            "path\\to\\file here" to "Path\\to\\file here", // two words: no terminal period
            "under_score name here" to "Under_score name here.",
            "C++ code here" to "C++ code here.",
            "a|b|c here" to "A|b|c here", // two words: no terminal period
            "less < more > than" to "Less < more > than.",
            "star *bold* text" to "Star *bold* text.",
            "back`tick here" to "Back`tick here", // two words: no terminal period
            // an email address, which is the single most common thing dictated
            // into a text field after a phone number
            "email me at a_b@c.com" to "Email me at a_b@c.com.",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, clean(input), "changed for <$input>")
        }
    }

    @Test
    fun `symbols the user dictated should survive cleanup`() {
        assertEquals("It costs $75 dollars.", clean("it costs $75 dollars"))
        assertEquals("The cost is €40.", clean("the cost is €40"))
        assertEquals("A + b = c.", clean("a + b = c"))
        assertEquals("Half is 1/2 cup.", clean("half is 1/2 cup"))
        assertEquals("Under_score name here.", clean("under_score name here"))
        assertEquals("Email me at a_b@c.com.", clean("email me at a_b@c.com"))
    }

    @Test
    fun `deleting a symbol does not split the word around it`() {
        // Dropping a character must never manufacture a word boundary that was
        // never spoken. Two halves to this now: symbols outside the deny-list are
        // not dropped at all...
        assertEquals("Under_score", clean("under_score"))
        assertEquals("A¦b", clean("a¦b"))
        // ...and the ones that ARE dropped are dropped in place, without flushing
        // the word they sat inside.
        assertEquals("Underscore", clean("under\u0000score"))
        assertEquals("Ab", clean("a\uFFFDb"))
    }

    // ------------------------------------------- VB-QA-21: bracketed prose deleted

    @Test
    fun `the ASR artifact scrubber matches a closed label vocabulary`() {
        // Intended targets — still removed.
        assertEquals("Hello", clean("<unk> hello"))
        assertEquals("Hello there", clean("(noise) hello there"))
        assertEquals("Plays now", clean("[music] plays now"))
        assertEquals("Hello there", clean("[inaudible] hello there"))
        // Ordinary prose in brackets is prose, not a recognizer label (VB-QA-21).
        assertEquals("See [see attached] for details.", clean("see [see attached] for details"))
        assertEquals("The [box] is here.", clean("the [box] is here"))
        assertEquals("Meet me at [the park] later.", clean("meet me at [the park] later"))
        assertEquals("Read [chapter one] now.", clean("read [chapter one] now"))
        assertEquals("A [b1] here.", clean("a [b1] here"))
    }

    @Test
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
