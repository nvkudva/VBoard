package com.vboard.core.correct

import com.vboard.core.text.FieldKind
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The deliberate-content guarantee (VB-231): a fix corrects prose and touches
 * nothing else.
 *
 * The Tier-1 tokenizer was written for ASR output and deletes every symbol it
 * does not recognize, so without [ContentGuard] a single tap would quietly strip
 * the slashes out of a link. Each case here asserts the payload survives
 * character for character.
 */
class ContentPreservationTest {

    private val fixer = TextFixer()

    private fun fix(text: String) = fixer.rulesOnly(text, FieldKind.TEXT)

    data class Case(val name: String, val input: String, val payload: String)

    @TestFactory
    fun `payloads survive a fix`(): List<DynamicTest> = CASES.map { case ->
        DynamicTest.dynamicTest(case.name) {
            val out = fix(case.input)
            assertTrue(
                case.payload in out,
                "payload did not survive\n  in : <${case.input}>\n  out: <$out>",
            )
        }
    }

    @Test
    fun `a url is not given a trailing period`() {
        assertEquals("See you at https://example.com", fix("see you at https://example.com"))
    }

    @Test
    fun `a sentence that merely mentions a url still gets its period`() {
        assertEquals(
            "Check https://example.com for details.",
            fix("check https://example.com for details"),
        )
    }

    @Test
    fun `shielding is fully reversible`() {
        for (case in CASES) {
            val shield = ContentGuard.shield(case.input)
            assertEquals(case.input, shield.restore(shield.masked), "input: <${case.input}>")
        }
    }

    @Test
    fun `a placeholder the user typed themselves is not clobbered`() {
        // freshPrefix has to move out of the way of literal "vbkeep0x" text.
        val input = "vbkeep0x costs $5 today"
        val out = fix(input)
        assertTrue("vbkeep0x" in out, "out: <$out>")
        assertTrue("\$5" in out, "out: <$out>")
    }

    @Test
    fun `deliberate capitalization is never flattened`() {
        assertEquals("The iPhone and the eBay listing.", fix("the iPhone and the eBay listing"))
        assertEquals("Send it ASAP.", fix("send it ASAP"))
    }

    @Test
    fun `repeated numbers are never collapsed as stutters`() {
        // A phone number is not a doubled word.
        assertTrue("555 555 1212" in fix("call me on 555 555 1212 today"))
    }

    companion object {
        private val CASES = listOf(
            Case("http url", "please read https://example.com/docs?a=1&b=2 today", "https://example.com/docs?a=1&b=2"),
            Case("bare www url", "go to www.example.co.uk now for it", "www.example.co.uk"),
            Case("email address", "mail jane.doe+tag@example.co.uk about it", "jane.doe+tag@example.co.uk"),
            Case("decimal number", "the rate is 3.14 percent today", "3.14"),
            Case("currency amount", "it costs $1,234.56 in total", "$1,234.56"),
            Case("clock time", "we meet at 10:30 tomorrow morning", "10:30"),
            Case("iso date", "due on 2026-08-29 without fail", "2026-08-29"),
            Case("code call", "run fn(x) twice for me", "fn(x)"),
            Case("code braces", "call fn(x) { return x*2 } and stop", "{ return x*2 }"),
            Case("snake case token", "the flag is max_retry_count today", "max_retry_count"),
            Case("file path", "open /var/log/system.log and read it", "/var/log/system.log"),
            Case("emoji", "great work 🎉 thanks a lot", "🎉"),
            Case("emoji sequence", "nice 👍🏽 well done everyone", "👍🏽"),
            Case("hashtag", "posting about #onDeviceAI later today", "#onDeviceAI"),
            Case("handle", "ask @jane_doe about the release", "@jane_doe"),
            Case("version string", "we shipped v2.10.3 last week", "v2.10.3"),
            Case("abbreviation with dots", "bring water e.g. a bottle please", "e.g."),
        )
    }
}
