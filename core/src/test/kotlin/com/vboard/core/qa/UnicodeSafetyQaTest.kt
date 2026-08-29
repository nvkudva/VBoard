package com.vboard.core.qa

import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.FieldKind
import com.vboard.core.text.Tok
import com.vboard.core.text.Tokenizer
import com.vboard.core.text.TranscriptCleaner
import java.text.Normalizer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unicode safety for the cleanup pipeline (VB-QA-14, -15, -16, -27; Package A).
 *
 * `Tokenizer.tokenize` used to keep a character only when `Char.isLetterOrDigit()`
 * was true for it or it appeared in a 17-character punctuation allow-list, which
 * deleted three whole categories from the user's text: non-BMP code points (every
 * emoji, CJK extension ideograph and math alphanumeric, because `Char` is a UTF-16
 * code unit and neither surrogate is a letter), combining marks (Mn/Mc — and the
 * `else` branch called `flushWord()`, so removing one also *split the word it was
 * attached to*), and format characters (Cf — bidi controls, ZWJ, ZWNJ, LRM/RLM).
 *
 * Package A inverted the policy: the tokenizer iterates **code points** and drops
 * only a small closed deny-list of ASR artifacts, and the non-raw path normalizes
 * to NFC so the output no longer depends on the normalization form the recognizer
 * happened to emit. The tests below assert the post-inversion behaviour.
 *
 * These are `core`-level tests of a pure function, so they run everywhere.
 */
class UnicodeSafetyQaTest {

    private val cleaner = TranscriptCleaner()

    private fun clean(text: String, options: CleanupOptions = CleanupOptions()): String = cleaner
        .clean(CleanupRequest(text, "", FieldKind.TEXT, options, ensureTerminalPunctuation = true))
        .text

    // ------------------------------------------------- what Unicode does survive

    @Test
    fun `scripts made of base letters pass through intact`() {
        // CJK, Cyrillic, Greek, Arabic and Hebrew letters are all isLetterOrDigit,
        // so they survive. Terminal punctuation is still ASCII, which is its own
        // (smaller) problem — see the disabled test at the end of this file.
        assertEquals("こんにちは 世界 です.", clean("こんにちは 世界 です"))
        assertEquals("Привет мир сегодня.", clean("привет мир сегодня"))
        assertEquals("مرحبا بالعالم اليوم.", clean("مرحبا بالعالم اليوم"))
        assertEquals("שלום עולם שלי.", clean("שלום עולם שלי"))
    }

    @Test
    fun `non-ASCII digits are treated as digits everywhere`() {
        // isNumberLike() uses Char.isDigit(), which is Unicode-aware, so
        // Arabic-Indic numerals get the VB-QA-01 anti-collapse exemption too.
        assertEquals("١٢٣ ٤٥٦ ٧٨٩.", clean("١٢٣ ٤٥٦ ٧٨٩"))
        assertEquals("٥ ٥ ٥ ١ ٢ ١ ٢.", clean("٥ ٥ ٥ ١ ٢ ١ ٢"))
    }

    @Test
    fun `precomposed accented Latin survives`() {
        assertEquals("Café is open now.", clean(nfc("café is open now")))
        assertEquals("Naïve résumé here.", clean(nfc("naïve résumé here")))
        assertEquals("Viet Việt Nam tiếng.", clean(nfc("viet Việt Nam tiếng")))
    }

    // ------------------------------------------- VB-QA-14: astral plane deletion

    @Test
    fun `every non-BMP character survives cleanup`() {
        // The tokenizer iterates code points, so an astral character is one unit
        // rather than two surrogates that both fail isLetterOrDigit (VB-QA-14).
        assertEquals(listOf<Tok>(Tok.Word("👋")), Tokenizer.tokenize("👋"))
        val cases = mapOf(
            "hello 👋 world here" to "Hello 👋 world here.",
            "family 👨‍👩‍👧 today" to "Family 👨‍👩‍👧 today.",   // ZWJ sequence
            "flag 🇺🇸 here now" to "Flag 🇺🇸 here now.", // regional indicators
            "thumbs 👍🏽 up now" to "Thumbs 👍🏽 up now.",   // skin-tone modifier
            "check ✔️ mark now" to "Check ✔️ mark now.",        // BMP glyph + VS16
            "math 𝐀𝐁 bold here" to "Math 𝐀𝐁 bold here.", // math alphanumerics
            "han 𠮷 char here" to "Han 𠮷 char here.",     // CJK ext-B ideograph
        )
        for ((input, expected) in cases) {
            assertEquals(expected, clean(input), "changed for <$input>")
        }
        // A keycap keeps its enclosing mark and VS16 as well as the digit.
        assertEquals("Keycap 1️⃣ here now.", clean("keycap 1️⃣ here now"))
    }

    @Test
    fun `emoji and other astral characters should survive cleanup`() {
        assertEquals("Hello 👋 world here.", clean("hello 👋 world here"))
        assertEquals("Family 👨‍👩‍👧 today.", clean("family 👨‍👩‍👧 today"))
        assertEquals("Han 𠮷 char here.", clean("han 𠮷 char here"))
    }

    // ------------------------------------- VB-QA-15: combining marks and Indic/Thai

    @Test
    fun `combining marks stay attached to their base letter in every script`() {
        // A combining mark is an ordinary word character now, and the non-raw path
        // normalizes to NFC, so the output no longer depends on which form the
        // recognizer emitted (VB-QA-15).
        assertEquals("Café is open now.", clean(nfc("café is open now")))
        assertEquals("Café is open now.", clean(nfd("café is open now")))
        assertEquals(nfc("Á b́ ć."), clean(nfd("á b́ ć")))

        // Devanagari and Thai have no precomposed forms to fall back on, so these
        // are the cases the old policy destroyed outright.
        assertEquals("Hindi नमस्ते दुनिया आज.", clean("hindi नमस्ते दुनिया आज"))
        assertEquals("Thai สวัสดี โลก นี้.", clean("thai สวัสดี โลก นี้"))

        assertEquals("Viet Việt Nam tiếng.", clean(nfd("viet Việt Nam tiếng")))
    }

    @Test
    fun `combining marks should survive and stay attached to their base letter`() {
        assertEquals("Café is open now.", clean(nfd("café is open now")))
        assertEquals("Hindi नमस्ते दुनिया आज.", clean("hindi नमस्ते दुनिया आज"))
        assertEquals("Thai สวัสดี โลก นี้.", clean("thai สวัสดี โลก นี้"))
    }

    @Test
    fun `cleanup output should not depend on the input normalization form`() {
        for (text in listOf("café is open now", "naïve résumé here", "viet Việt Nam tiếng")) {
            assertEquals(
                nfc(clean(nfc(text))),
                nfc(clean(nfd(text))),
                "NFC and NFD disagree for <$text>",
            )
        }
    }

    // ------------------------------------------- VB-QA-16: format characters

    @Test
    fun `format characters are preserved so RTL and joined text render as dictated`() {
        // RLE/PDF around an Arabic run: the override that fixes the visual order
        // now survives, so the rendered order cannot change (VB-QA-16).
        assertEquals("‫هذا نص‬ عربي هنا.", clean("‫هذا نص‬ عربي هنا"))
        // A standalone LRM is carried through as its own token.
        assertEquals("Test ‎ ltr mark here.", clean("test ‎ ltr mark here"))
        // ZWSP and ZWJ join rather than split, so the word stays one word.
        assertEquals("Zero​width", clean("zero​width"))
        assertEquals("A‍b", clean("a‍b"))
        // A non-breaking space is still space, so it still separates words.
        assertEquals("A b", clean("a\u00A0b"))
    }

    @Test
    fun `bidi controls should be preserved so RTL text renders as dictated`() {
        assertEquals("‫هذا نص‬ عربي هنا.", clean("‫هذا نص‬ عربي هنا"))
    }

    // ------------------------------------- VB-QA-27: sentence terminator vocabulary

    @Test
    fun `an unterminated or ambiguous preceding text does not start a sentence`() {
        fun cap(preceding: String) = cleaner.clean(
            CleanupRequest("hello there friend", preceding, FieldKind.TEXT, CleanupOptions(), true),
        ).text

        assertEquals("Hello there friend.", cap("abc."))
        assertEquals("Hello there friend.", cap("abc!"))
        assertEquals("Hello there friend.", cap("abc?"))
        assertEquals("Hello there friend.", cap("abc.  "))
        assertEquals("Hello there friend.", cap("abc\n"))
        // A straight apostrophe is deliberately NOT treated as a closing quote:
        // it is ambiguous with a word-final apostrophe, so it is left out of the
        // closer set that VB-QA-27 widened.
        assertEquals("hello there friend.", cap("abc.'"))
        // And a bare closer with no terminator behind it is still not a sentence end.
        for (preceding in listOf("abc\"", "abc)", "abc,")) {
            assertEquals("hello there friend.", cap(preceding), "unexpected capitalization after <$preceding>")
        }
    }

    @Test
    fun `a sentence after a closing quote or a non-ASCII terminator should be capitalized`() {
        fun cap(preceding: String) = cleaner.clean(
            CleanupRequest("hello there friend", preceding, FieldKind.TEXT, CleanupOptions(), true),
        ).text
        for (preceding in listOf("abc.\"", "abc.)", "abc…", "abc。", "abc！", "abc？", "abc؟", "abc।")) {
            assertEquals("Hello there friend.", cap(preceding), "not capitalized after <$preceding>")
        }
    }

    // --------------------------------------------------------------- invariants

    @Test
    fun `cleanup never throws on any single code point in a sentence frame`() {
        // Every Unicode plane, sampled. A crash here would take the IME down
        // mid-dictation, which is the worst failure this component can have.
        var checked = 0
        var cp = 0
        while (cp <= 0x10FFFF) {
            if (!Character.isDefined(cp) || Character.getType(cp) == Character.SURROGATE.toInt()) {
                cp += 97; continue
            }
            val s = String(Character.toChars(cp))
            clean("the word $s here")
            clean(s)
            clean(s + s)
            checked++
            cp += 97 // a prime stride: ~3k defined code points across every plane
        }
        assertTrue(checked > 2_500, "sampled only $checked code points")
    }

    @Test
    fun `letters the tokenizer keeps are never reordered`() {
        // Whatever cleanup deletes, it must never permute what it keeps. This is
        // the one guarantee that still holds for RTL text after VB-QA-16.
        val inputs = listOf(
            "مرحبا بالعالم اليوم", "שלום עולם שלי", "привет мир сегодня",
            "hindi नमस्ते दुनिया आज", "こんにちは 世界 です", "hello 👋 world here",
        )
        for (input in inputs) {
            val kept = clean(input).filter { it.isLetter() }.lowercase()
            val original = input.filter { it.isLetter() }.lowercase()
            assertTrue(
                isSubsequence(kept, original),
                "letters were reordered or invented for <$input>: <$kept> is not a subsequence of <$original>",
            )
        }
    }

    private fun isSubsequence(needle: String, haystack: String): Boolean {
        var i = 0
        for (ch in haystack) if (i < needle.length && needle[i] == ch) i++
        return i == needle.length
    }

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)
    private fun nfd(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
}
