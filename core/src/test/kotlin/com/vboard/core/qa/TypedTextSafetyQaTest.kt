package com.vboard.core.qa

import com.vboard.core.correct.ContentGuard
import com.vboard.core.correct.TypedTextCleanup
import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.FieldKind
import com.vboard.core.text.TranscriptCleaner
import java.text.Normalizer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `TranscriptCleaner` is safe on speech and unsafe on everything else, and
 * nothing in its type signature says so.
 *
 * `TypedTextCleanup` is the one caller that knows this: it turns off three
 * stages and, crucially, runs `ContentGuard.shield` first so URLs, decimals,
 * code and emoji are swapped for placeholders before the tokenizer sees them.
 * That is a workaround maintained by convention. Any future caller — an LLM
 * refinement pass, a paste normalizer, a "clean up this note" action — that
 * calls `TranscriptCleaner.clean` directly gets the ASR behaviour applied to
 * text nobody spoke.
 *
 * This file does two things: it fuzzes the guarded path for content loss, and it
 * demonstrates the size of the gap between the guarded and unguarded paths, so
 * the cost of forgetting the guard is a number rather than a warning in a
 * KDoc comment.
 */
class TypedTextSafetyQaTest {

    private val cleaner = TranscriptCleaner()

    private fun unguarded(text: String): String = cleaner
        .clean(CleanupRequest(text, "", FieldKind.TEXT, TypedTextCleanup.OPTIONS, true))
        .text

    // ------------------------------------------ the guard does what it claims

    @Test
    fun `content the tokenizer would destroy survives the guarded path`() {
        val survives = listOf(
            "check https://a.co/x now",
            "the value is 3.14 exactly",
            "call me at 555-1212 today",
            "email me at a_b@c.com soon",
            "it costs $75 in total",
            "the path is /usr/local/bin here",
            "nice work 👋 thanks",
            "use the iPhone setting now",
            "read section 2.1.3 first",
            "the tag is #release-v2 now",
            "50% off everything today",
            "«guillemets» stay put here",
        )
        for (input in survives) {
            val out = TypedTextCleanup.clean(input)
            for (chunk in input.split(' ')) {
                assertTrue(
                    chunk in out || chunk.replaceFirstChar { it.uppercaseChar() } in out,
                    "TypedTextCleanup lost <$chunk> from <$input> -> <$out>",
                )
            }
        }
    }

    @Test
    fun `the guarded path still does the job it exists for`() {
        assertEquals("Hello world", TypedTextCleanup.clean("hello world"))
        assertEquals("The cat sat.", TypedTextCleanup.clean("the the cat sat"))
        assertEquals("I am here.", TypedTextCleanup.clean("i am here"))
        assertEquals("Line one\nLine two", TypedTextCleanup.clean("line one\nline two"))
        assertEquals("  Indented text", TypedTextCleanup.clean("  indented text"))
        // Speech-only stages stay off, so typed words are never deleted.
        assertEquals("Um sure", TypedTextCleanup.clean("um sure"))
        assertEquals("scratch that", TypedTextCleanup.clean("scratch that"))
        assertEquals("Say period out loud.", TypedTextCleanup.clean("say period out loud"))
        assertEquals("I mean it.", TypedTextCleanup.clean("i mean it"))
        // A trailing URL gets no stapled-on full stop.
        assertEquals("See https://a.co/x", TypedTextCleanup.clean("see https://a.co/x"))
    }

    @Test
    fun `the guarded path is idempotent`() {
        val inputs = listOf(
            "hello world", "the the cat sat", "check https://a.co/x now", "3.14 is pi",
            "  indented text", "line one\n\nline three", "nice work 👋 thanks",
            "MEETING at 5", "um sure", "scratch that", "café is open",
        )
        for (input in inputs) {
            val once = TypedTextCleanup.clean(input)
            assertEquals(once, TypedTextCleanup.clean(once), "not idempotent for <$input>")
        }
    }

    // ------------------------------- how much the guard is actually carrying

    @Test
    fun `the unguarded cleaner no longer mangles this text`() {
        // Same options, same cleaner, only ContentGuard removed. This is exactly
        // what a new caller gets by writing the obvious thing — and after Package A
        // it gets the same answer, because the mangling these cases measured was
        // the tokenizer's allow-list, not something the guard has to carry.
        // See QA_REPORT VB-QA-33/-34: the guard is doing much less work now.
        val comparison = mapOf(
            "check https://a.co/x now" to ("Check https://a.co/x now." to "Check https://a.co/x now."),
            "the value is 3.14 exactly" to ("The value is 3.14 exactly." to "The value is 3.14 exactly."),
            "it costs $75 in total" to ("It costs $75 in total." to "It costs $75 in total."),
            "nice work 👋 thanks" to ("Nice work 👋 thanks." to "Nice work 👋 thanks."),
            "the path is /usr/local/bin here" to
                ("The path is /usr/local/bin here." to "The path is /usr/local/bin here."),
            "email me at a_b@c.com soon" to
                ("Email me at a_b@c.com soon." to "Email me at a_b@c.com soon."),
        )
        for ((input, expected) in comparison) {
            val (guarded, raw) = expected
            assertEquals(guarded, TypedTextCleanup.clean(input), "guarded path changed for <$input>")
            assertEquals(raw, unguarded(input), "unguarded path changed for <$input>")
        }
    }

    @Test
    fun `nothing in the API stops a caller reaching the unguarded path`() {
        // TranscriptCleaner.clean takes a CleanupRequest with no field saying
        // "this text was typed, not spoken", and CleanupOptions has no flag that
        // disables tokenizer-level destruction (rawMode does not — see VB-QA-17).
        // The only protection is that TypedTextCleanup happens to be the caller.
        val fields = CleanupRequest::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(
            fields.none { it.contains("shield", ignoreCase = true) || it.contains("typed", ignoreCase = true) },
            "a guard field appeared on CleanupRequest - update this pin and the report",
        )
        val flags = CleanupOptions::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(
            flags.none { it.contains("shield", ignoreCase = true) || it.contains("preserve", ignoreCase = true) },
            "a preservation flag appeared on CleanupOptions - update this pin and the report",
        )
    }

    // ------------------------------- VB-QA-34: hyphen-led tokens reach the shield

    @Test
    fun `a token starting with a hyphen is shielded, but a bare bullet is not (pinned)`() {
        // An edge hyphen is not word-internal, so "-m" and "--verbose" are shielded
        // and survive the tokenizer, which would otherwise emit the hyphen as bare
        // punctuation and let the renderer space it out.
        //
        // The bullet is the boundary of the rule and the reason it carries a
        // letter-or-digit clause: "-" on its own has to stay in the prose stream,
        // because shielding it would hide the sentence start from the caser and
        // "first" would never become "First".
        assertEquals("Run git commit -m \"fix\" first.", TypedTextCleanup.clean("run git commit -m \"fix\" first"))
        assertEquals("Use --verbose here.", TypedTextCleanup.clean("use --verbose here"))
        assertEquals("- First item", TypedTextCleanup.clean("- first item"))
        // A leading hyphen followed by a digit was already shielded by the digit
        // rule, and must stay that way.
        assertEquals("The value is -5 today.", TypedTextCleanup.clean("the value is -5 today"))
        assertTrue(ContentGuard.needsShield("-m"))
        assertTrue(ContentGuard.needsShield("-5"))
    }

    @Test
    fun `a hyphen-led token should be shielded like any other non-prose chunk`() {
        assertEquals("Run git commit -m \"fix\" first.", TypedTextCleanup.clean("run git commit -m \"fix\" first"))
        assertEquals("Use --verbose here.", TypedTextCleanup.clean("use --verbose here"))
        assertEquals("- First item", TypedTextCleanup.clean("- first item"))
    }

    // --------------------------------------------------- VB-QA-33: guard holes

    @Test
    fun `an NFD-accented word is not shielded, so it is capitalized (pinned)`() {
        // A combining mark sitting on a letter is just the decomposed spelling of
        // an accented word, so needsShield lets it through and the sentence-casing
        // rule sees it — same answer as its NFC twin. Identical text on screen,
        // one result.
        val nfc = Normalizer.normalize("café is open", Normalizer.Form.NFC)
        val nfd = Normalizer.normalize("café is open", Normalizer.Form.NFD)
        assertEquals(Normalizer.normalize("Café is open.", Normalizer.Form.NFC), TypedTextCleanup.clean(nfc))
        assertEquals(Normalizer.normalize("Café is open.", Normalizer.Form.NFC), TypedTextCleanup.clean(nfd))
        assertTrue(!ContentGuard.needsShield(Normalizer.normalize("café", Normalizer.Form.NFC)))
        assertTrue(!ContentGuard.needsShield(Normalizer.normalize("café", Normalizer.Form.NFD)))
    }

    @Test
    fun `typed cleanup should not depend on the normalization form`() {
        for (text in listOf("café is open", "naïve idea here", "résumé attached now")) {
            assertEquals(
                Normalizer.normalize(TypedTextCleanup.clean(Normalizer.normalize(text, Normalizer.Form.NFC)), Normalizer.Form.NFC),
                Normalizer.normalize(TypedTextCleanup.clean(Normalizer.normalize(text, Normalizer.Form.NFD)), Normalizer.Form.NFC),
                "NFC and NFD disagree for <$text>",
            )
        }
    }

    // -------------------------------------------------------------- properties

    @Test
    fun `the guarded path never loses a non-space character`() {
        // The property that matters for an "AI fix" button: it may recase and it
        // may add terminal punctuation, but it must not delete what was typed.
        val fragments = listOf(
            "hello", "world", "the", "the", "um", "https://a.co/x", "3.14", "a_b@c.com",
            "$75", "50%", "👋", "iPhone", "MEETING", "-m", "\"fix\"", "#tag", "café",
            "/usr/bin", "2.1.3", "i", "scratch", "that", "period", "«x»", "C++", "a+b",
        )
        val random = Random(20260829)
        repeat(4_000) {
            val input = (1..random.nextInt(1, 10)).joinToString(" ") { fragments.random(random) }
            val out = assertDoesNotThrow("threw for <$input>") { TypedTextCleanup.clean(input) }
            // Deletion of a duplicate word is allowed (collapseRepetitions is on)
            // and one terminal "." may be added; nothing else may appear. Compare
            // as multisets, case-folded.
            val before = input.filterNot { it.isWhitespace() }.lowercase()
                .groupingBy { it }.eachCount()
            val after = out.filterNot { it.isWhitespace() }.lowercase()
                .groupingBy { it }.eachCount()
            val added = after.entries.sumOf { (ch, n) -> maxOf(0, n - (before[ch] ?: 0)) }
            assertTrue(
                added <= 1,
                "typed cleanup added $added characters to <$input> -> <$out>",
            )
        }
    }

    @Test
    fun `the guarded path preserves every line and its indentation`() {
        val random = Random(987)
        val lines = listOf("hello world", "", "  indented", "\tt tabbed", "https://a.co/x", "3.14", "   ")
        repeat(2_000) {
            val input = (1..random.nextInt(1, 8)).joinToString("\n") { lines.random(random) }
            val out = TypedTextCleanup.clean(input)
            assertEquals(
                input.count { it == '\n' }, out.count { it == '\n' },
                "line count changed for <$input> -> <$out>",
            )
            for ((before, after) in input.split('\n').zip(out.split('\n'))) {
                assertEquals(
                    before.takeWhile { it == ' ' || it == '\t' },
                    after.takeWhile { it == ' ' || it == '\t' },
                    "indentation changed on a line of <$input>",
                )
                if (before.isBlank()) assertEquals(before, after, "a blank line changed in <$input>")
            }
        }
    }

    @Test
    fun `shield and restore round-trip any text the cleaner leaves alone`() {
        val random = Random(24680)
        val fragments = listOf(
            "hello", "https://a.co/x", "3.14", "a_b@c.com", "$75", "👋", "vbkeep0x",
            "vbkeep", "«x»", "C++", "a+b", "iPhone", "#tag", "/usr/bin", "e=mc2",
        )
        repeat(4_000) {
            val input = (1..random.nextInt(1, 10)).joinToString(" ") { fragments.random(random) }
            val shield = ContentGuard.shield(input)
            assertEquals(input, shield.restore(shield.masked), "shield/restore did not round-trip <$input>")
        }
    }

    @Test
    fun `shield picks a placeholder prefix the text does not already contain`() {
        // The one way restore could paste someone else's URL over a user's word.
        for (input in listOf(
            "vbkeep0x https://a.co/x", "vbkeep vbkeepq https://a.co/x",
            "VBKEEP0X https://a.co/x", "vbkeepqqqqqqqq https://a.co/x",
        )) {
            val shield = ContentGuard.shield(input)
            assertEquals(input, shield.restore(shield.masked), "collision for <$input>")
        }
    }

    @Test
    fun `typed cleanup never throws on arbitrary input`() {
        val random = Random(1_000_003)
        repeat(2_000) {
            val length = random.nextInt(0, 120)
            val sb = StringBuilder(length)
            repeat(length) {
                when (random.nextInt(5)) {
                    0 -> sb.append(random.nextInt(0x20, 0x7F).toChar())
                    1 -> sb.append('\n')
                    2 -> sb.append(random.nextInt(0x80, 0x3000).toChar())
                    3 -> sb.appendCodePoint(random.nextInt(0x10000, 0x110000))
                    else -> sb.append(" \t".random(random))
                }
            }
            assertDoesNotThrow { TypedTextCleanup.clean(sb.toString()) }
        }
    }
}
