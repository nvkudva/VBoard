package com.vboard.core.qa

import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.FieldKind
import com.vboard.core.text.TextDiff
import com.vboard.core.text.TranscriptCleaner
import org.junit.jupiter.api.Test

class ZzProbeTest {
    private val cleaner = TranscriptCleaner()

    private fun c(s: String, pre: String = "", terminal: Boolean = true, opts: CleanupOptions = CleanupOptions()) =
        cleaner.clean(CleanupRequest(s, pre, FieldKind.TEXT, opts, terminal))

    @Test
    fun probeArtifacts() {
        val inputs = listOf(
            "see [see attached] for details", "the [box] is here", "meet me at [the park] later",
            "<unk> hello", "(noise) hello there", "[music] plays now",
            "the [BOX] is here", "a [b1] here", "read [chapter one] now",
        )
        for (i in inputs) println("P3_ART  |$i| -> |${c(i).text}|")
    }

    @Test
    fun probePreceding() {
        val pres = listOf("abc.", "abc.\"", "abc.'", "abc.)", "abc…", "abc。", "abc！", "abc？", "abc؟", "abc।", "abc.  ", "")
        for (p in pres) println("P3_PRE  |$p| -> |${c("hello there friend", pre = p).text}|")
    }

    @Test
    fun probeDiff2() {
        val cases = listOf(
            "a👍" to "a👍🏽",
            "aé" to "aé",
            "a☺" to "a☺️",
            "a👨‍👩" to "a👨",
            "1️⃣" to "2️⃣",
            "🇺🇸" to "🇺🇦",
        )
        for ((a, b) in cases) {
            val r = TextDiff.replacement(a, b)
            println("P3_DIFF |$a|(${a.length})->|$b|(${b.length}) keep=${r.keepPrefixLength} del=${r.deleteCount} ins=${r.insertText.length}ch")
        }
    }

    @Test
    fun probeSymbols2() {
        val inputs = listOf(
            "it costs \$75 dollars", "the cost is €40", "£20 please now",
            "a + b = c", "half is 1/2 cup", "under_score name here",
            "C++ code here", "x^2 plus y", "5 * 3 equals", "a|b|c here",
            "less < more > than", "star *bold* text", "back`tick here",
            "path\\to\\file here", "R&D team meeting", "50% off today",
            "email me at a_b@c.com", "temp is 20° today", "the ± range",
            "he said “hi” loudly", "it's 3⁄4 done",
        )
        for (i in inputs) println("P3_SYM  |$i| -> |${c(i).text}|")
    }

    @Test
    fun probeUni2() {
        val nfd = "café is open now"
        val nfc = "café is open now"
        println("P3_U    NFD |$nfd| -> |${c(nfd).text}|")
        println("P3_U    NFC |$nfc| -> |${c(nfc).text}|")
        val inputs = listOf(
            "hello 👋 world here",
            "family 👨‍👩‍👧 today",
            "flag 🇺🇸 here now",
            "thumbs 👍🏽 up now",
            "keycap 1️⃣ here now",
            "check ✔️ mark now",
            "math 𝐀𝐁 bold here",
            "han 𠮷 char here",
            "viet Việt Nam tiếng",
            "viet Việt Nam tié̂ng",
            "hindi नमस्ते दुनिया आज",
            "thai สวัสดี โลก นี้",
        )
        for (i in inputs) {
            val o = c(i).text
            println("P3_U    in=${i.length} -> out=${o.length} |$o|")
        }
        for (i in listOf("hello 👋", "café", "\$75", "a‍b"))
            println("P3_RAW  |$i| -> |${c(i, opts = CleanupOptions.RAW).text}|")
    }
}
