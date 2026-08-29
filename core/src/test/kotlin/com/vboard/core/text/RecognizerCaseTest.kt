package com.vboard.core.text

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class RecognizerCaseTest {

    @Test
    fun `an all-caps partial is brought down to lower case`() {
        assertEquals("send him the file", RecognizerCase.normalize("SEND HIM THE FILE"))
    }

    @Test
    fun `mixed case output is left exactly as the model produced it`() {
        assertEquals("Send him the file", RecognizerCase.normalize("Send him the file"))
        // One lowercase letter anywhere is enough: this model knows about case.
        assertEquals("NASA said yes", RecognizerCase.normalize("NASA said yes"))
    }

    @Test
    fun `text without letters is not touched`() {
        assertEquals("", RecognizerCase.normalize(""))
        assertEquals("123 456", RecognizerCase.normalize("123 456"))
    }

    @Test
    fun `punctuation and digits survive the lowering`() {
        assertEquals("call me at 5, ok?", RecognizerCase.normalize("CALL ME AT 5, OK?"))
    }

    @Test
    fun `the cleaner puts back the casing that matters`() {
        // The two halves are meant to be read together: normalize takes the
        // model's shouting away, TranscriptCleaner decides what is a capital.
        val cleaned = TranscriptCleaner().clean(
            CleanupRequest(
                transcript = RecognizerCase.normalize("I TOLD HIM I WOULD CALL"),
                fieldKind = FieldKind.TEXT,
            ),
        )
        assertEquals("I told him I would call", cleaned.text.trimEnd('.'))
    }
}
