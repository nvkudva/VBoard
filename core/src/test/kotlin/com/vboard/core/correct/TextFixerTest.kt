package com.vboard.core.correct

import com.vboard.core.text.FieldKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * "AI fix" end to end (VB-231, VB-233, VB-234, VB-235, VB-236).
 *
 * The refiner is faked, which is the point: every gate, every length decision,
 * and every model-output rejection is decided in core and testable without a
 * device or a 500 MB model file.
 */
class TextFixerTest {

    private val fixer = TextFixer()

    /** Records what it was asked and answers with whatever the test wants. */
    private class FakeRefiner(
        private val answer: (String) -> SmartOutput,
    ) : SmartRefiner {
        val seen = mutableListOf<String>()
        var calls = 0
        override suspend fun refine(text: String): SmartOutput {
            calls++
            seen.add(text)
            return answer(text)
        }
    }

    private fun echoing(transform: (String) -> String) = FakeRefiner { SmartOutput.of(transform(it)) }

    // ------------------------------------------------------------ field gating

    @Test
    fun `password fields are refused`() = runTest {
        val result = fixer.fix("hunter2 correct horse", FieldKind.PASSWORD, echoing { it.uppercase() })
        assertEquals(FixStatus.REFUSED, result.status)
        assertEquals(FixRefusal.PASSWORD_FIELD, result.refusal)
        assertEquals("hunter2 correct horse", result.correctedText())
    }

    @Test
    fun `a password field never reaches the model`() = runTest {
        val refiner = echoing { it }
        fixer.fix("hunter2 correct horse", FieldKind.PASSWORD, refiner)
        assertEquals(0, refiner.calls, "password content must never leave the gate")
    }

    @Test
    fun `email and uri fields are refused`() = runTest {
        for (kind in listOf(FieldKind.EMAIL, FieldKind.URI)) {
            val result = fixer.fix("jane doe at example", kind, echoing { it })
            assertEquals(FixStatus.REFUSED, result.status, "kind: $kind")
            assertEquals(FixRefusal.ADDRESS_FIELD, result.refusal, "kind: $kind")
        }
    }

    @Test
    fun `number fields are refused`() = runTest {
        val result = fixer.fix("555 1212", FieldKind.NUMBER, echoing { it })
        assertEquals(FixRefusal.NUMERIC_FIELD, result.refusal)
    }

    @Test
    fun `text and search fields are enabled, the rest are not`() {
        assertTrue(fixer.isEnabledFor(FieldKind.TEXT))
        assertTrue(fixer.isEnabledFor(FieldKind.SEARCH))
        assertFalse(fixer.isEnabledFor(FieldKind.PASSWORD))
        assertFalse(fixer.isEnabledFor(FieldKind.EMAIL))
        assertFalse(fixer.isEnabledFor(FieldKind.URI))
        assertFalse(fixer.isEnabledFor(FieldKind.NUMBER))
    }

    @Test
    fun `an empty field is refused`() = runTest {
        val result = fixer.fix("   ", FieldKind.TEXT, echoing { it })
        assertEquals(FixRefusal.EMPTY_FIELD, result.refusal)
    }

    // ------------------------------------------------------------ length policy

    @Test
    fun `text at the smart cap is still refined`() = runTest {
        val text = paragraph(FixChunker.MAX_SMART_CHARS - 40)
        assertTrue(fixer.rulesOnly(text).length <= FixChunker.MAX_SMART_CHARS)
        val refiner = echoing { it }
        val result = fixer.fix(text, FieldKind.TEXT, refiner)
        assertNotEquals(SmartTier.TOO_LONG, result.smart)
        assertTrue(refiner.calls > 1, "expected chunking, got ${refiner.calls} call(s)")
    }

    @Test
    fun `text just over the smart cap declines the smart tier but still applies the rules`() = runTest {
        val text = paragraph(FixChunker.MAX_SMART_CHARS + 400)
        val refiner = echoing { it }
        val result = fixer.fix(text, FieldKind.TEXT, refiner)
        assertEquals(SmartTier.TOO_LONG, result.smart)
        assertEquals(0, refiner.calls, "nothing may be sent once the tier declines")
        assertEquals(fixer.rulesOnly(text), result.correctedText())
    }

    @Test
    fun `text over the hard cap is refused outright`() = runTest {
        val text = paragraph(FixChunker.MAX_FIELD_CHARS + 100)
        val result = fixer.fix(text, FieldKind.TEXT, echoing { it })
        assertEquals(FixStatus.REFUSED, result.status)
        assertEquals(FixRefusal.TOO_LONG, result.refusal)
        assertEquals(text, result.correctedText())
    }

    @Test
    fun `no chunk sent to the model is over the chunk cap`() = runTest {
        val refiner = echoing { it }
        fixer.fix(paragraph(2_500), FieldKind.TEXT, refiner)
        assertTrue(refiner.seen.isNotEmpty())
        refiner.seen.forEach {
            assertTrue(it.length <= FixChunker.MAX_CHUNK_CHARS, "oversized chunk sent: ${it.length}")
        }
    }

    @Test
    fun `chunked output reassembles faithfully`() = runTest {
        val text = paragraph(1_800)
        val rules = fixer.rulesOnly(text)
        // A refiner that changes nothing must produce exactly the rules output.
        val result = fixer.fix(text, FieldKind.TEXT, echoing { it })
        assertEquals(rules, result.correctedText())
    }

    // ------------------------------------------------------ graceful degradation

    @Test
    fun `a missing pack still applies the deterministic pass`() = runTest {
        val text = "i saw the the dog yesterday"
        val result = fixer.fix(text, FieldKind.TEXT, refiner = null)
        assertEquals(SmartTier.NOT_INSTALLED, result.smart)
        assertEquals(FixStatus.APPLIED, result.status)
        assertEquals("I saw the dog yesterday.", result.correctedText())
    }

    @Test
    fun `a refiner failure falls back to rules-only and says so`() = runTest {
        val text = "i saw the the dog yesterday"
        for ((failure, tier) in listOf(
            SmartFailure.NOT_INSTALLED to SmartTier.NOT_INSTALLED,
            SmartFailure.TIMED_OUT to SmartTier.TIMED_OUT,
            SmartFailure.LOAD_FAILED to SmartTier.UNAVAILABLE,
            SmartFailure.ERROR to SmartTier.UNAVAILABLE,
        )) {
            val result = fixer.fix(text, FieldKind.TEXT, FakeRefiner { SmartOutput.failed(failure) })
            assertEquals(tier, result.smart, "failure: $failure")
            assertEquals(fixer.rulesOnly(text), result.correctedText(), "failure: $failure")
        }
    }

    @Test
    fun `every validator rejection falls back to the rules-only text`() = runTest {
        val text = "please read https://example.com/docs before the call tomorrow"
        val rules = fixer.rulesOnly(text)
        val bad = mapOf(
            RejectReason.EMPTY to "",
            RejectReason.DROPPED_ENTITY to "Please read the docs before the call tomorrow.",
            RejectReason.TOO_SHORT to "Read it.",
            RejectReason.TOO_LONG to "Please read https://example.com/docs before the call " +
                "tomorrow, and also review the appendix, the changelog and the migration " +
                "guide which I have attached to the calendar invitation for you.",
        )
        for ((expected, answer) in bad) {
            val result = fixer.fix(text, FieldKind.TEXT, FakeRefiner { SmartOutput.of(answer) })
            assertEquals(rules, result.correctedText(), "reason: $expected")
            assertEquals(SmartTier.REJECTED, result.smart, "reason: $expected")
            assertEquals(listOf(expected), result.rejections, "answer: <$answer>")
        }
    }

    @Test
    fun `a model that answers the text instead of correcting it is rejected`() = runTest {
        val text = "whats the capital of france"
        val result = fixer.fix(
            text,
            FieldKind.TEXT,
            FakeRefiner { SmartOutput.of("The capital of France is Paris.") },
        )
        assertEquals(SmartTier.REJECTED, result.smart)
        assertEquals(listOf(RejectReason.DIVERGED), result.rejections)
        assertEquals(fixer.rulesOnly(text), result.correctedText())
    }

    @Test
    fun `a good model result is applied`() = runTest {
        val result = fixer.fix(
            "i went too the stor yesterday",
            FieldKind.TEXT,
            FakeRefiner { SmartOutput.of("I went to the store yesterday.") },
        )
        assertEquals(SmartTier.APPLIED, result.smart)
        assertEquals(FixStatus.APPLIED, result.status)
        assertEquals("I went to the store yesterday.", result.correctedText())
    }

    @Test
    fun `a partial refinement is reported as partial`() = runTest {
        val text = paragraph(1_500)
        var first = true
        val result = fixer.fix(text, FieldKind.TEXT) {
            if (first) {
                first = false
                SmartOutput.of(it)
            } else {
                SmartOutput.failed(SmartFailure.TIMED_OUT)
            }
        }
        assertEquals(SmartTier.PARTIAL, result.smart)
    }

    @Test
    fun `clean text comes back unchanged`() = runTest {
        val text = "The report is ready for review."
        val result = fixer.fix(text, FieldKind.TEXT, echoing { it })
        assertEquals(FixStatus.UNCHANGED, result.status)
        assertEquals(text, result.correctedText())
    }

    // ------------------------------------------------------------------ privacy

    @Test
    fun `result toString carries no content and no lengths`() = runTest {
        val result = fixer.fix(
            "the acme merger closes on friday",
            FieldKind.TEXT,
            FakeRefiner { SmartOutput.of("The Acme merger closes on Friday.") },
        )
        val printed = result.toString()
        assertFalse("acme" in printed.lowercase(), printed)
        assertFalse("merger" in printed.lowercase(), printed)
        assertFalse("friday" in printed.lowercase(), printed)
        assertFalse("32" in printed, "no content lengths may leak: $printed")
        assertFalse("33" in printed, "no content lengths may leak: $printed")
    }

    @Test
    fun `smart output toString carries no content`() {
        val printed = SmartOutput.of("the acme merger").toString()
        assertFalse("acme" in printed.lowercase(), printed)
        assertTrue("produced=true" in printed, printed)
    }

    /** Prose of roughly [chars] characters, in ordinary sentences. */
    private fun paragraph(chars: Int): String {
        val sb = StringBuilder()
        var i = 1
        while (sb.length < chars) {
            sb.append("this is sentence number $i and it says something ordinary. ")
            i++
        }
        return sb.toString().trim()
    }
}
