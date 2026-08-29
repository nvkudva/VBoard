package com.vboard.core.qa

import com.vboard.core.correct.ContentGuard
import com.vboard.core.correct.TypedTextCleanup
import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.CommitPlanner
import com.vboard.core.text.FieldKind
import com.vboard.core.text.TextDiff
import com.vboard.core.text.Tokenizer
import com.vboard.core.text.TranscriptCleaner
import org.junit.jupiter.api.Test

class ZzProbeTest {
    private val cleaner = TranscriptCleaner()

    private fun c(s: String, terminal: Boolean = true, opts: CleanupOptions = CleanupOptions()) =
        cleaner.clean(CleanupRequest(s, "", FieldKind.TEXT, opts, terminal))

    @Test
    fun probeSymbols() {
        val inputs = listOf(
            "it costs \$75 dollars", "up 50% today", "a + b = c", "5 * 3", "half is 1/2",
            "email me at a_b@c.com", "the cost is €40", "£20 please", "¥300", "₹500",
            "x^2", "a|b", "path\\to\\file", "under_score", "tilde ~ here", "back`tick",
            "less < more > than", "brackets [one] here", "braces {two} here",
            "star *bold* text", "100 degrees ~ warm", "C++ code", "R&D team",
            "café", "café", "naïve", "こんにちは", "مرحبا بالعالم", "שלום עולם",
            "hello 👋 world", "family 👨‍👩‍👧‍👦 here", "flag 🇺🇸 here", "thumbs 👍🏽 up",
            "keycap 1️⃣ here", "zero​width", "rtl‫mark‬", "a b",
        )
        for (i in inputs) println("PROBE_SYM  |$i| -> |${c(i).text}|")
    }

    @Test
    fun probeTokenizer() {
        val inputs = listOf("\$75", "50%", "a+b", "e=mc2", "3/4", "x_y", "a~b", "[a]", "{b}", "<c>",
            "👋", "é", "​", "a‍b")
        for (i in inputs) println("PROBE_TOK  |$i| -> ${Tokenizer.tokenize(i)} render=|${Tokenizer.render(Tokenizer.tokenize(i))}|")
    }

    @Test
    fun probeCommands() {
        val inputs = listOf(
            "scratch that itch", "i need to scratch that", "please stop listening to him",
            "we should make that decision", "sorry i am late", "i mean it",
            "no wait for me", "actually no thanks", "strike that deal",
            "he said period", "add a period at the end", "the comma is missing",
            "Scratch That", "SCRATCH THAT", "um scratch that um",
            "the dash between them", "a hashtag for it", "the colon operator",
        )
        for (i in inputs) { val r = c(i); println("PROBE_CMD  |$i| -> |${r.text}| cmd=${r.command}") }
    }

    @Test
    fun probeIdempotence() {
        val inputs = listOf(
            "hello world", "what is this", "he said period", "one two three",
            "the cat sat", "i am here", "why not", "is it ok", "a b c",
            "hello 👋 world", "café here now", "\$75 for it", "50% off now",
        )
        for (i in inputs) {
            val a = c(i).text; val b = c(a).text
            println("PROBE_IDEM |$i| -> |$a| -> |$b| ${if (a == b) "OK" else "**DIFF**"}")
        }
    }

    @Test
    fun probeJoin() {
        val cases = listOf(
            "hello" to "world", "hello " to "world", "" to "world", "hello" to ".",
            "(" to "a", "\"" to "a", "hello" to "👋", "hello-" to "world",
            "hello" to "'s", "café" to "x", "$" to "5", "5" to "%",
        )
        for ((p, t) in cases) println("PROBE_JOIN |$p|+|$t| -> |${CommitPlanner.joinForInsertion(p, t)}|")
        for (p in listOf("ab ", "a  ", "  ", " ", "a", "ab", ") ", "👋 ", "é ", "1 ", ". "))
            println("PROBE_DSP  |$p| -> ${CommitPlanner.doubleSpacePeriodApplies(p)}")
    }

    @Test
    fun probeDiff() {
        val cases = listOf(
            "hello" to "help", "" to "abc", "abc" to "", "ab" to "ab",
            "👋" to "👌",
            "hi 👨‍👩‍👧" to "hi 👨‍👩‍👦",
            "café" to "cafê",
            "a🇺🇸" to "a🇬🇧",
            "é" to "e",
        )
        for ((a, b) in cases) println("PROBE_DIFF |$a|->|$b| ${TextDiff.replacement(a, b)}")
    }

    @Test
    fun probeTyped() {
        val inputs = listOf(
            "hello world", "i went to teh store", "check https://a.co/x now",
            "the the cat", "scratch that", "um sure", "3.14 is pi",
            "hello 👋", "MEETING at 5", "line one\nline two", "  indented text",
            "a b non breaking", "«guillemets»", "café", "café",
        )
        for (i in inputs) println("PROBE_TYP  |$i| -> |${TypedTextCleanup.clean(i)}|")
        for (s in listOf("café", "café", "👋", "«a»", "a b", "naïve"))
            println("PROBE_GUARD |$s| needsShield=${ContentGuard.needsShield(s)}")
    }

    @Test
    fun probeRaw() {
        for (i in listOf("um hello", "the the cat", "hello 👋", "\$75", "café"))
            println("PROBE_RAW  |$i| -> |${c(i, opts = CleanupOptions.RAW).text}|")
    }
}
