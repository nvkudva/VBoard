package com.vboard.core.qa

import com.vboard.core.text.CleanupOptions
import com.vboard.core.text.CleanupRequest
import com.vboard.core.text.FieldKind
import com.vboard.core.text.TranscriptCleaner
import org.junit.jupiter.api.Test

class ZzProbeTest {
    private val cleaner = TranscriptCleaner()

    @Test
    fun probeCapGate() {
        for (k in FieldKind.entries) {
            for (input in listOf("hello. world here", "hello new line world here", "hello world here", "i am here now")) {
                val r = cleaner.clean(CleanupRequest(input, "", k, CleanupOptions(), true))
                println("P4_CAP  $k |$input| -> |${r.text.replace("\n", "\\n")}|")
            }
        }
    }

    @Test
    fun probeBreaks() {
        for (input in listOf(
            "hello new line new line world", "hello new paragraph new line world",
            "hello new line new line new line world", "a\n\n\nb", "a\n \n \n b",
        )) {
            val out = cleaner.clean(CleanupRequest(input, "", FieldKind.TEXT, CleanupOptions(), true)).text
            val again = cleaner.clean(CleanupRequest(out, "", FieldKind.TEXT, CleanupOptions(), true)).text
            println("P4_BRK  |${input.replace("\n", "\\n")}| -> |${out.replace("\n", "\\n")}| -> |${again.replace("\n", "\\n")}|")
        }
    }

    @Test
    fun probeArtifactGate() {
        for (o in listOf(CleanupOptions(), CleanupOptions.RAW))
            println("P4_ART  raw=${o.rawMode} |[music] hello| -> |${cleaner.clean(CleanupRequest("[music] hello there", "", FieldKind.TEXT, o, true)).text}|")
    }
}
