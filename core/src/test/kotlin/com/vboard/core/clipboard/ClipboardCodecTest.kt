package com.vboard.core.clipboard

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The store document. A damaged file must produce an empty history without
 * throwing — and, crucially, without the caller being told anything that would
 * justify overwriting it, since that would turn a recoverable read failure into
 * permanent loss (the same trap the learned-word store already fell into once).
 */
class ClipboardCodecTest {

    private fun entry(text: String, at: Long = 1_700_000_000_000L, pinned: Boolean = false) =
        ClipEntry(text, at, pinned)

    @Test
    fun `an empty store round-trips`() {
        assertEquals(
            emptyList<ClipEntry>(),
            ClipboardCodec.decode(ClipboardCodec.encode(emptyList())),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "plain text",
            "with \"double quotes\"",
            "with a backslash \\ and \\\" together",
            "line one\nline two\r\nline three",
            "tabs\tand\tmore\ttabs",
            "emoji 🙂🎉 and astral 𝄞",
            "accented ünïcödé and 中文 and العربية",
            "json-looking {\"t\":\"gotcha\",\"at\":0,\"p\":true}",
            "]}, trailing punctuation [{",
            " leading and trailing spaces ",
        ],
    )
    fun `awkward text round-trips exactly`(text: String) {
        val decoded = ClipboardCodec.decode(ClipboardCodec.encode(listOf(entry(text))))
        assertEquals(listOf(entry(text)), decoded)
    }

    @Test
    fun `control characters round-trip through escapes`() {
        val text = "bell\u0007 vertical\u000B formfeed\u000C sep\u2028para\u2029tors"
        val encoded = ClipboardCodec.encode(listOf(entry(text)))
        assertEquals(listOf(entry(text)), ClipboardCodec.decode(encoded))
    }

    @Test
    fun `pins and timestamps round-trip`() {
        val entries = listOf(
            entry("pinned", at = 42L, pinned = true),
            entry("loose", at = 7L, pinned = false),
        )
        assertEquals(entries, ClipboardCodec.decode(ClipboardCodec.encode(entries)))
    }

    @Test
    fun `the encoded shape is the documented one`() {
        assertEquals(
            "{\"v\":1,\"clips\":[{\"t\":\"hi\",\"at\":5,\"p\":true}]}",
            ClipboardCodec.encode(listOf(entry("hi", at = 5L, pinned = true))),
        )
    }

    @Test
    fun `whitespace between tokens is tolerated`() {
        val json = """
            { "v" : 1 , "clips" : [
                { "t" : "spaced out" , "at" : 9 , "p" : false }
            ] }
        """.trimIndent()
        assertEquals(listOf(entry("spaced out", at = 9L)), ClipboardCodec.decode(json))
    }

    @Test
    fun `unknown members are ignored so a newer file still reads`() {
        val json = "{\"v\":1,\"extra\":{\"nested\":[1,true,null,\"x\"]}," +
            "\"clips\":[{\"t\":\"hi\",\"at\":5,\"p\":false,\"future\":[1,2]}]}"
        assertEquals(listOf(entry("hi", at = 5L)), ClipboardCodec.decode(json))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "   ",
            "not json at all",
            "{",
            "{\"v\":1,\"clips\":[",
            "{\"v\":1,\"clips\":[{\"t\":\"trunca",
            "{\"v\":1,\"clips\":[{\"t\":\"hi\",\"at\":5,\"p\":false}",
            "{\"v\":1,\"clips\":[{\"t\":\"hi\",\"at\":5}]}", // missing "p"
            "{\"v\":1,\"clips\":[{\"at\":5,\"p\":false}]}", // missing "t"
            "{\"v\":1,\"clips\":[{\"t\":\"hi\",\"at\":\"five\",\"p\":false}]}",
            "{\"v\":2,\"clips\":[]}", // a version this build does not understand
            "{\"clips\":[]}", // no version at all
            "{\"v\":1}", // no clips
            "{\"v\":1,\"clips\":{}}",
            "{\"v\":1,\"clips\":[]} trailing garbage",
        ],
    )
    fun `a damaged document decodes to null instead of throwing`(json: String) {
        assertNull(ClipboardCodec.decode(json))
    }

    @Test
    fun `a byte-truncated document at every length decodes to null or a valid store`() {
        val full = ClipboardCodec.encode(
            listOf(entry("first", at = 1L), entry("second", at = 2L, pinned = true)),
        )
        for (length in 0 until full.length) {
            // Never throws; a prefix is either unreadable or, in no case here,
            // coincidentally valid.
            assertNull(ClipboardCodec.decode(full.substring(0, length)), "prefix of $length chars")
        }
        assertNotNull(ClipboardCodec.decode(full))
    }
}
