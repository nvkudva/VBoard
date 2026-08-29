package com.vboard.core.qa

import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.CommitPlanner
import com.vboard.core.text.FieldKind
import com.vboard.core.text.TextDiff
import com.vboard.core.text.TranscriptCleaner
import java.text.Normalizer
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seam between cleanup and the `InputConnection`.
 *
 * `CommitPlanner.joinForInsertion` decides the space between what is already in
 * the field and what is about to be committed; `doubleSpacePeriodApplies` decides
 * whether a second space becomes ". "; `TextDiff.replacement` decides the minimal
 * edit that turns the displayed partial into its replacement.
 *
 * All three classify the boundary between the preceding text and the new text,
 * and all three used to do it one UTF-16 code unit at a time — which is not a
 * character. They work in code points and grapheme clusters now. AOSP LatinIME,
 * HeliBoard and AnySoftKeyboard all carry dedicated tests for exactly this seam
 * (autospace around quotes and URLs, double-space-period revert, deletion of
 * multi-code-point text); we had none.
 */
class CommitSeamQaTest {

    private val cleaner = TranscriptCleaner()

    private fun clean(text: String, preceding: String = "", kind: FieldKind = FieldKind.TEXT) = cleaner
        .clean(CleanupRequest(text, preceding, kind, CleanupOptions(), ensureTerminalPunctuation = true))
        .text

    // -------------------------------------------------------- what joining gets right

    @Test
    fun `joining inserts one space between words and none around punctuation`() {
        assertEquals(" world", CommitPlanner.joinForInsertion("hello", "world"))
        assertEquals("world", CommitPlanner.joinForInsertion("hello ", "world"))
        assertEquals("world", CommitPlanner.joinForInsertion("", "world"))
        assertEquals(".", CommitPlanner.joinForInsertion("hello", "."))
        assertEquals("world", CommitPlanner.joinForInsertion("hello\n", "world"))
        assertEquals("a", CommitPlanner.joinForInsertion("(", "a"))
        assertEquals("a", CommitPlanner.joinForInsertion("\"", "a"))
        assertEquals("%", CommitPlanner.joinForInsertion("5", "%"))
        assertEquals("", CommitPlanner.joinForInsertion("hello", ""))
    }

    @Test
    fun `joining never produces a double space`() {
        val precedings = listOf("", " ", "a", "a ", "a  ", "(", "\"", "\n", ".", "5", "é", "👋", "你好")
        val texts = listOf("world", ".", ",", "👋", "'s", "-", "\n")
        for (p in precedings) for (t in texts) {
            val added = CommitPlanner.joinForInsertion(p, t)
            val boundary = p.takeLast(1) + added.take(1)
            assertTrue(boundary != "  ", "double space at the join of <$p> + <$t>")
            assertTrue(!(p.isEmpty() && added.startsWith(" ")), "leading space for <$p> + <$t>")
        }
        // Pinned gap: joinForInsertion trusts its argument. Text that already
        // starts with a space is passed through after a space, so the caller —
        // not the planner — is what keeps the field clean. Cleanup never emits a
        // leading space, so this is latent rather than live.
        assertEquals(" world", CommitPlanner.joinForInsertion(" ", " world"))
    }

    // --------------------------------------- VB-QA-22: joining rules cover real cases

    @Test
    fun `joining attaches a possessive, a hyphenated compound and a currency amount (pinned)`() {
        // An apostrophe opens when it precedes and closes when it follows, so a
        // suggestion-strip commit of a bare possessive stays attached.
        assertEquals("'s", CommitPlanner.joinForInsertion("hello", "'s"))
        assertEquals("’s", CommitPlanner.joinForInsertion("hello", "’s"))
        // A trailing hyphen opens, so a compound dictated in halves survives.
        assertEquals("world", CommitPlanner.joinForInsertion("hello-", "world"))
        // Currency is decided by category (Sc), so every sign behaves alike — the
        // euro is the one that catches a hard-coded '$'.
        assertEquals("5", CommitPlanner.joinForInsertion("$", "5"))
        assertEquals("5", CommitPlanner.joinForInsertion("€", "5"))
        // The typographic forms close exactly as their ASCII counterparts do.
        assertEquals("…", CommitPlanner.joinForInsertion("hello", "…"))
        assertEquals("”", CommitPlanner.joinForInsertion("hello", "”"))
    }

    @Test
    fun `joining should attach possessives, hyphenated compounds and currency`() {
        assertEquals("'s", CommitPlanner.joinForInsertion("hello", "'s"))
        assertEquals("’s", CommitPlanner.joinForInsertion("hello", "’s"))
        assertEquals("world", CommitPlanner.joinForInsertion("hello-", "world"))
        assertEquals("5", CommitPlanner.joinForInsertion("$", "5"))
        assertEquals("…", CommitPlanner.joinForInsertion("hello", "…"))
        assertEquals("”", CommitPlanner.joinForInsertion("hello", "”"))
    }

    // ---------------------- VB-QA-23: double-space-period is grapheme-aware

    @Test
    fun `double space becomes a period after any character that ends a word (pinned)`() {
        assertTrue(CommitPlanner.doubleSpacePeriodApplies("ab "))
        assertTrue(CommitPlanner.doubleSpacePeriodApplies("1 "))
        assertTrue(CommitPlanner.doubleSpacePeriodApplies(") "))
        assertTrue(CommitPlanner.doubleSpacePeriodApplies("你好 "))
        assertTrue(CommitPlanner.doubleSpacePeriodApplies("مرحبا "))
        assertTrue(CommitPlanner.doubleSpacePeriodApplies(Normalizer.normalize("café ", Normalizer.Form.NFC)))
        assertTrue(!CommitPlanner.doubleSpacePeriodApplies(". "))
        assertTrue(!CommitPlanner.doubleSpacePeriodApplies("a  "))
        assertTrue(!CommitPlanner.doubleSpacePeriodApplies(" "))
        assertTrue(!CommitPlanner.doubleSpacePeriodApplies(""))

        // The two that used to be wrong: an emoji is a surrogate pair whose low
        // half is not a letter or digit, and an NFD accented letter ends in a
        // combining mark. Both are ordinary ways to end a sentence.
        assertTrue(CommitPlanner.doubleSpacePeriodApplies("👋 "))
        assertTrue(CommitPlanner.doubleSpacePeriodApplies(Normalizer.normalize("café ", Normalizer.Form.NFD)))
    }

    @Test
    fun `double space should become a period after an emoji or a combining mark`() {
        assertTrue(CommitPlanner.doubleSpacePeriodApplies("👋 "))
        assertTrue(CommitPlanner.doubleSpacePeriodApplies(Normalizer.normalize("café ", Normalizer.Form.NFD)))
        // ...and consistently, whichever normalization form the text arrives in.
        assertEquals(
            CommitPlanner.doubleSpacePeriodApplies(Normalizer.normalize("café ", Normalizer.Form.NFC)),
            CommitPlanner.doubleSpacePeriodApplies(Normalizer.normalize("café ", Normalizer.Form.NFD)),
        )
    }

    // ----------------------------------------- VB-QA-28: TextDiff and grapheme clusters

    @Test
    fun `the partial-replacement diff never splits a surrogate pair`() {
        // This one IS handled (CommitPlanner.kt:58) and must stay handled.
        val pairs = listOf(
            "👋" to "👌", "a👋" to "a👌", "👋b" to "👌b", "hi 👨" to "hi 👩",
            "😀😀" to "😀😁", "" to "👋", "👋" to "",
        )
        for ((current, target) in pairs) {
            val r = TextDiff.replacement(current, target)
            assertTrue(
                r.keepPrefixLength == 0 || !current[r.keepPrefixLength - 1].isHighSurrogate(),
                "prefix ends on a lone high surrogate for <$current> -> <$target>",
            )
            assertEquals(
                target,
                current.take(r.keepPrefixLength) + r.insertText,
                "diff does not reconstruct the target for <$current> -> <$target>",
            )
            assertEquals(current.length - r.keepPrefixLength, r.deleteCount)
        }
    }

    @Test
    fun `the diff rounds the kept prefix back to a cluster boundary (pinned)`() {
        // A combining mark: base and mark are replaced together, so the field
        // never briefly shows an unaccented letter.
        val nfd = { s: String -> Normalizer.normalize(s, Normalizer.Form.NFD) }
        assertEquals(3, TextDiff.replacement(nfd("café"), nfd("cafê")).keepPrefixLength)
        // A regional-indicator flag: both halves go, so no lone 🇺 letter symbol.
        assertEquals(0, TextDiff.replacement("🇺🇸", "🇺🇦").keepPrefixLength)
        // A ZWJ sequence: the prefix stops before the family, not after a ZWJ.
        assertEquals(3, TextDiff.replacement("hi 👨‍👩‍👧", "hi 👨‍👩‍👦").keepPrefixLength)
        // The three cases BreakIterator's legacy clusters get wrong on this JDK,
        // which is why the boundary rule is hand-rolled: a skin-tone modifier is
        // part of the hand rather than a character after it...
        assertEquals(0, TextDiff.replacement("👋🏽", "👋🏻").keepPrefixLength)
        // ...a keycap is digit + U+FE0F + U+20E3, all three or none...
        assertEquals(0, TextDiff.replacement("1️⃣", "1").keepPrefixLength)
        // ...and a variation selector glues to whatever it is modifying.
        assertEquals(0, TextDiff.replacement("a️", "a").keepPrefixLength)
    }

    @Test
    fun `the diff should never split any grapheme cluster`() {
        val nfd = { s: String -> Normalizer.normalize(s, Normalizer.Form.NFD) }
        assertEquals(3, TextDiff.replacement(nfd("café"), nfd("cafê")).keepPrefixLength)
        assertEquals(0, TextDiff.replacement("🇺🇸", "🇺🇦").keepPrefixLength)
        assertEquals(3, TextDiff.replacement("hi 👨‍👩‍👧", "hi 👨‍👩‍👦").keepPrefixLength)
    }

    @Test
    fun `the diff always reconstructs the target, on random unicode`() {
        // Whatever it splits, it must be correct. 4000 randomized pairs over an
        // alphabet chosen to make grapheme boundaries likely.
        val alphabet = listOf(
            "a", "b", " ", ".", "👋", "👨‍👩", "🇺🇸",
            "é", "́", "️", "‍", "é", "日", "ا",
        )
        val random = Random(20260829)
        repeat(4_000) {
            val a = (0 until random.nextInt(0, 6)).joinToString("") { alphabet.random(random) }
            val b = (0 until random.nextInt(0, 6)).joinToString("") { alphabet.random(random) }
            val r = TextDiff.replacement(a, b)
            assertEquals(b, a.take(r.keepPrefixLength) + r.insertText, "diff wrong for <$a> -> <$b>")
            assertEquals(a.length - r.keepPrefixLength, r.deleteCount, "deleteCount wrong for <$a> -> <$b>")
            assertEquals(a == b, r.isNoop || (r.deleteCount == 0 && r.insertText.isEmpty()))
        }
    }

    // ---------------------------------------------- cleanup feeding commit planning

    @Test
    fun `cleaning then joining never yields a double space or a space before punctuation`() {
        val precedings = listOf("", "Hello", "Hello ", "Hello.", "Hello.\n", "(", "\"", "5", "👋")
        val utterances = listOf(
            "how are you doing", "comma this is next", "period new sentence",
            "um yeah ok", "hello world", "call me at five five five",
            "scratch that no wait hello", "hello 👋 there", "$75 please",
        )
        for (preceding in precedings) for (utterance in utterances) {
            val cleaned = clean(utterance, preceding)
            if (cleaned.isEmpty()) continue
            val joined = preceding + CommitPlanner.joinForInsertion(preceding, cleaned)
            assertTrue("  " !in joined, "double space: <$preceding> + <$utterance> -> <$joined>")
            assertTrue(" ." !in joined && " ," !in joined && " ?" !in joined && " !" !in joined,
                "space before punctuation: <$preceding> + <$utterance> -> <$joined>")
            assertTrue(!joined.endsWith(" "), "trailing space: <$preceding> + <$utterance> -> <$joined>")
        }
    }

    @Test
    fun `search fields never receive a terminal period through the seam`() {
        // VB-2xx: a search box must stay query-shaped. Verified through the whole
        // seam, not just the cleaner, because the join step could reintroduce one.
        for (utterance in listOf("weather in london today", "how tall is everest", "pizza near me now")) {
            val cleaned = clean(utterance, "", FieldKind.SEARCH)
            assertTrue(!cleaned.endsWith("."), "search query ended with a period: <$cleaned>")
            assertTrue(!cleaned.endsWith("?"), "search query ended with a question mark: <$cleaned>")
            assertEquals(cleaned, CommitPlanner.joinForInsertion("", cleaned))
        }
    }

    @Test
    fun `an empty cleanup result is never committed as a bare space`() {
        // "scratch that" and friends return "" with a command; joining must not
        // turn that into a stray space in the field.
        for (utterance in listOf("scratch that", "stop listening", "um", "   ", "")) {
            val cleaned = clean(utterance, "Hello")
            assertEquals("", CommitPlanner.joinForInsertion("Hello", cleaned))
        }
    }
}
