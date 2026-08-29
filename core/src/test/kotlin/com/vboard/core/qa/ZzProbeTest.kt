package com.vboard.core.qa

import com.vboard.core.clipboard.ClipClassifier
import com.vboard.core.suggest.AutocorrectMode
import com.vboard.core.suggest.Lexicon
import com.vboard.core.suggest.SuggestionEngine
import com.vboard.core.suggest.SuggestionRequest
import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.CommitPlanner
import com.vboard.core.text.FieldKind
import com.vboard.core.text.TranscriptCleaner
import org.junit.jupiter.api.Test

class ZzProbeTest {
    private val cleaner = TranscriptCleaner()

    private fun c(s: String, terminal: Boolean = true, opts: CleanupOptions = CleanupOptions()) =
        cleaner.clean(CleanupRequest(s, "", FieldKind.TEXT, opts, terminal))

    @Test
    fun probeSelfCorrection() {
        val inputs = listOf(
            "no wait for me", "no wait i am coming", "wait no i changed my mind",
            "tell him i need to scratch that itch", "i need to scratch that",
            "sorry about the delay", "sorry i am late for the meeting",
            "rather than that we should go", "i would rather stay home",
            "make that decision by friday", "we make that call every week",
            "actually no i think you are right", "actually no",
            "she said sorry to him", "please make that happen",
            "the deal is off strike that we are back on",
            "i mean what i say", "you know i mean business",
            "call me at five no wait six",
            "meet me monday scratch that tuesday",
        )
        for (i in inputs) { val r = c(i); println("P2_SC   |$i| -> |${r.text}| resolved=${r.correctionsResolved}") }
    }

    @Test
    fun probeSpokenCmd() {
        val inputs = listOf(
            "use hashtag now", "put comma here", "the period at the end of history",
            "menstrual period tracking", "a colon and a semicolon", "use colon here",
            "the dash cam footage", "press dash now", "the ampersand key",
            "new line of thinking", "on the next line item", "full stop the car",
            "question mark placement", "open quote unquote", "at sign up time",
        )
        for (i in inputs) println("P2_CMD  |$i| -> |${c(i).text}|")
    }

    @Test
    fun probeUnicodeMore() {
        val inputs = listOf(
            "مرحبا بالعالم اليوم", "שלום עולם שלי", "привет мир сегодня",
            "‫هذا نص‬ عربي هنا", "test ‎ ltr mark here",
            "ＦＵＬＬＷＩＤＴＨ ｔｅｘｔ ｈｅｒｅ", "١٢٣ ٤٥٦ ٧٨٩",
            "á b́ ć", "𝐀𝐁 math bold",
            "ǅungla test word", "ß straße here", "ﬁ ligature test",
            "x".repeat(5000) + " end",
        )
        for (i in inputs) {
            val o = c(i).text
            println("P2_UNI  in=${i.length}ch |${i.take(40)}| -> out=${o.length}ch |${o.take(60)}|")
        }
    }

    @Test
    fun probeClip() {
        val cases = listOf(
            "123456", "١٢٣٤٥٦", "１２３４５６", " 123456 ", "12 34 56",
            "4111111111111111", "4111 1111 1111 1111", "٤١١١١١١１١١１１１１１１",
            "-----BEGIN RSA PRIVATE KEY-----", "12345678901234567890",
            " ", "​", "\n\t ",
        )
        for (t in cases) println("P2_CLIP |$t| -> ${ClipClassifier.classify(t)}")
        println("P2_LUHN arabic=" + ClipClassifier.luhnValid("١٢٣٤"))
        println("P2_LUHN fullwidth=" + ClipClassifier.luhnValid("１２３４"))
    }

    @Test
    fun probeSuggest() {
        val lex = Lexicon.builtin()
        val e = SuggestionEngine(lex)
        val cases = listOf("teh", "hte", "i", "I", "café", "naïve", "ok👍", "don't", "'tis",
            "a".repeat(60), "İstanbul", "STRASSE", "ß", "é", "  hello  ")
        for (s in cases) {
            val r = e.suggest(SuggestionRequest(s, null, FieldKind.TEXT, AutocorrectMode.CONSERVATIVE))
            println("P2_SUG  |$s| ac=${r.autocorrect?.text} sugg=${r.suggestions.map { it.text }}")
        }
        for (k in FieldKind.entries) {
            val r = e.suggest(SuggestionRequest("teh", "the", k, AutocorrectMode.AGGRESSIVE))
            println("P2_FLD  $k ac=${r.autocorrect?.text} n=${r.suggestions.size}")
        }
    }

    @Test
    fun probeJoin2() {
        val cases = listOf(
            "hello" to "'s", "hello-" to "world", "\$" to "5", "hello" to "-",
            "hello" to "\"", "…" to "a", "hello" to "…", "hello" to "’s",
            "1" to "°", "(" to ")", "hello\n" to "world", "hello " to "world",
        )
        for ((p, t) in cases) println("P2_JOIN |$p|+|$t| -> |${CommitPlanner.joinForInsertion(p, t)}|")
        for (p in listOf("café ", "café ", "你好 ", "مرحبا ", "👋 ", "_ ", "- "))
            println("P2_DSP  |$p| -> ${CommitPlanner.doubleSpacePeriodApplies(p)}")
    }

    @Test
    fun probeFieldKinds() {
        for (k in FieldKind.entries) {
            val r = c("hello world this is a test").let { it }
            val res = cleaner.clean(CleanupRequest("hello world this is a test", "", k, CleanupOptions(), true))
            println("P2_FK   $k -> |${res.text}|  (default=|${r.text}|)")
        }
        // precedingText variations
        for (p in listOf("", "abc", "abc.", "abc. ", "abc\n", "abc\n  ", "abc?", "abc…", "abc!", "abc。"))
            println("P2_PRE  |$p| -> |${cleaner.clean(CleanupRequest("hello there friend", p, FieldKind.TEXT, CleanupOptions(), true)).text}|")
    }

    @Test
    fun probeWhitespace() {
        val inputs = listOf(
            "hello\tworld", "hello world", "hello   world", "\n\n\nhello",
            "hello\n\n\n\nworld", "hello \n world", "hello", "hello world",
        )
        for (i in inputs) println("P2_WS   ${i.map { it.code }} -> |${c(i).text}| codes=${c(i).text.map { it.code }}")
    }
}
