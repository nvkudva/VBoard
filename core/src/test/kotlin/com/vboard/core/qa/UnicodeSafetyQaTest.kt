package com.vboard.core.qa

import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.FieldKind
import com.vboard.core.text.Tokenizer
import com.vboard.core.text.TranscriptCleaner
import java.text.Normalizer
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unicode safety for the cleanup pipeline.
 *
 * `Tokenizer.tokenize` keeps a character only when `Char.isLetterOrDigit()` is
 * true for it, or when it appears in a 17-character punctuation allow-list
 * (`Tokens.kt:53-77`). Three whole categories of character fail both tests and
 * are therefore deleted from the user's text:
 *
 *  - **Non-BMP characters.** `Char` is a UTF-16 code unit, so an astral code
 *    point arrives as two surrogates and `isLetterOrDigit()` is false for both.
 *    Every emoji, every CJK extension ideograph, every mathematical-alphanumeric
 *    letter is removed.
 *  - **Combining marks** (Unicode category Mn/Mc). `isLetterOrDigit()` is false
 *    for a combining acute, a Devanagari matra, a Thai vowel sign. They are not
 *    merely dropped: the `else` branch calls `flushWord()`, so removing one also
 *    *splits the word it was attached to*.
 *  - **Format characters** (Cf) — bidi controls, ZWJ, ZWNJ, LRM/RLM.
 *
 * For Latin the effect depends on the input's normalization form, which no ASR
 * engine guarantees: NFC "café" survives, NFD "café" becomes "cafe". For
 * Devanagari and Thai — which have no precomposed forms to fall back on — the
 * text is destroyed outright.
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
    fun `every non-BMP character is deleted (pinned)`() {
        assertEquals(listOf(), Tokenizer.tokenize("👋"))
        val pinned = mapOf(
            "hello 👋 world here" to "Hello world here.",
            "family 👨‍👩‍👧 today" to "Family today",   // ZWJ sequence
            "flag 🇺🇸 here now" to "Flag here now.", // regional indicators
            "thumbs 👍🏽 up now" to "Thumbs up now.",   // skin-tone modifier
            "check ✔️ mark now" to "Check mark now.",        // BMP glyph + VS16
            "math 𝐀𝐁 bold here" to "Math bold here.", // math alphanumerics
            "han 𠮷 char here" to "Han char here.",     // CJK ext-B ideograph
        )
        for ((input, expected) in pinned) {
            assertEquals(expected, clean(input), "changed for <$input>")
        }
        // A keycap loses its enclosing mark and VS16 but keeps the ASCII digit,
        // so "1️⃣" silently becomes a plain "1".
        assertEquals("Keycap 1 here now.", clean("keycap 1️⃣ here now"))
    }

    @Test
    @Disabled("VB-QA-14: Tokenizer inspects UTF-16 Char values, so every astral code point (all emoji, CJK extensions, math alphanumerics) fails isLetterOrDigit and is deleted")
    fun `emoji and other astral characters should survive cleanup`() {
        assertEquals("Hello 👋 world here.", clean("hello 👋 world here"))
        assertEquals("Family 👨‍👩‍👧 today.", clean("family 👨‍👩‍👧 today"))
        assertEquals("Han 𠮷 char here.", clean("han 𠮷 char here"))
    }

    // ------------------------------------- VB-QA-15: combining marks and Indic/Thai

    @Test
    fun `combining marks are deleted and split the word they attach to (pinned)`() {
        // Latin: identical on screen, different after cleanup, decided purely by
        // which normalization form the ASR engine happened to emit.
        assertEquals("Café is open now.", clean(nfc("café is open now")))
        assertEquals("Cafe is open now.", clean(nfd("café is open now")))
        assertEquals("A b c.", clean(nfd("á b́ ć")))

        // Devanagari and Thai have no precomposed escape hatch. Every matra,
        // virama and tone mark is a combining character, so the words are not
        // just de-accented, they are cut into fragments.
        assertEquals("Hindi नमस त द न य आज.", clean("hindi नमस्ते दुनिया आज"))
        assertEquals("Thai สว สด โลก น.", clean("thai สวัสดี โลก นี้"))

        // Decomposed Vietnamese loses both the diacritic and the word boundary.
        assertEquals("Viet Vie t Nam tie ng.", clean(nfd("viet Việt Nam tiếng")))
    }

    @Test
    @Disabled("VB-QA-15: combining marks fail isLetterOrDigit, so they are dropped AND flush the current word; NFD Latin is de-accented and Devanagari/Thai words are cut into fragments")
    fun `combining marks should survive and stay attached to their base letter`() {
        assertEquals("Café is open now.", clean(nfd("café is open now")))
        assertEquals("Hindi नमस्ते दुनिया आज.", clean("hindi नमस्ते दुनिया आज"))
        assertEquals("Thai สวัสดี โลก นี้.", clean("thai สวัสดี โลก นี้"))
    }

    @Test
    @Disabled("VB-QA-15: cleanup is not normalization-independent - NFC and NFD forms of the same text produce different output")
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
    fun `bidi controls and zero-width joiners are deleted (pinned)`() {
        // RLE/PDF around an Arabic run: the override that fixed the visual order
        // is removed while the letters stay, so the rendered order can change.
        assertEquals("هذا نص عربي هنا.", clean("‫هذا نص‬ عربي هنا"))
        // LRM disappears.
        assertEquals("Test ltr mark here.", clean("test ‎ ltr mark here"))
        // ZWSP and ZWJ both split a word rather than joining or vanishing.
        assertEquals("Zero width", clean("zero​width"))
        assertEquals("A b", clean("a‍b"))
        // A non-breaking space becomes an ordinary space.
        assertEquals("A b", clean("a\u00A0b"))
    }

    @Test
    @Disabled("VB-QA-16: bidi control characters (RLE/PDF/LRM/RLM) are stripped, which can change the rendered order of mixed-direction text")
    fun `bidi controls should be preserved so RTL text renders as dictated`() {
        assertEquals("‫هذا نص‬ عربي هنا.", clean("‫هذا نص‬ عربي هنا"))
    }

    // ------------------------------------- VB-QA-27: sentence terminator vocabulary

    @Test
    fun `only ASCII terminators start a new sentence in the preceding text (pinned)`() {
        fun cap(preceding: String) = cleaner.clean(
            CleanupRequest("hello there friend", preceding, FieldKind.TEXT, CleanupOptions(), true),
        ).text

        assertEquals("Hello there friend.", cap("abc."))
        assertEquals("Hello there friend.", cap("abc!"))
        assertEquals("Hello there friend.", cap("abc?"))
        assertEquals("Hello there friend.", cap("abc.  "))
        assertEquals("Hello there friend.", cap("abc\n"))
        // Everything else does not, including the terminators of every writing
        // system VBoard claims to support and the extremely common
        // "end of a quoted sentence" shape.
        for (preceding in listOf("abc.\"", "abc.'", "abc.)", "abc…", "abc。", "abc！", "abc？", "abc؟", "abc।")) {
            assertEquals("hello there friend.", cap(preceding), "unexpected capitalization after <$preceding>")
        }
    }

    @Test
    @Disabled("VB-QA-27: sentenceStartsAt (TranscriptCleaner.kt:445) knows only . ! ? and newline, so a sentence after a closing quote, an ellipsis, or any non-ASCII terminator is not capitalized")
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
