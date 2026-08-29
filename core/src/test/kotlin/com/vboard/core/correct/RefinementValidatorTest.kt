package com.vboard.core.correct

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Model output is untrusted input (VB-236).
 *
 * Each case here is something a 0.5B instruct model actually does when asked to
 * correct a sentence, and each one must be caught before it can reach the user's
 * field. The fallback is always the rules-only text, which is why every
 * rejection is safe.
 */
class RefinementValidatorTest {

    private fun reject(original: String, candidate: String?): RejectReason {
        val verdict = RefinementValidator.validate(original, candidate)
        assertFalse(verdict.accepted, "expected a rejection, got: <${verdict.text()}>")
        assertEquals(null, verdict.text(), "a rejected verdict must not carry text")
        return verdict.reason!!
    }

    private fun accept(original: String, candidate: String): String {
        val verdict = RefinementValidator.validate(original, candidate)
        assertTrue(verdict.accepted, "expected acceptance, rejected as ${verdict.reason}")
        return verdict.text()!!
    }

    // ------------------------------------------------------------- acceptance

    @Test
    fun `a plain correction is accepted`() {
        assertEquals(
            "I went to the store yesterday.",
            accept("i went too the stor yesterday", "I went to the store yesterday."),
        )
    }

    @Test
    fun `an unchanged result is accepted, not treated as a failure`() {
        // Nothing to fix is a legitimate answer for a correction pass.
        val text = "The report is ready for review."
        assertEquals(text, accept(text, text))
    }

    @Test
    fun `wrapping quotes and template scaffolding are stripped, not rejected`() {
        assertEquals(
            "I will be there at 5.",
            accept("i will be there at 5", "\"I will be there at 5.\"<|im_end|>"),
        )
    }

    // ------------------------------------------------------------- rejections

    @Test
    fun `an empty result is rejected`() {
        assertEquals(RejectReason.EMPTY, reject("the meeting is tomorrow", null))
        assertEquals(RejectReason.EMPTY, reject("the meeting is tomorrow", "   "))
    }

    @Test
    fun `leaked chat scaffolding is rejected`() {
        // Known end-of-turn markers are trimmed (see the acceptance case above);
        // anything else that still smells of the template is thrown away rather
        // than guessed at.
        assertEquals(
            RejectReason.TEMPLATE_LEAK,
            reject(
                "the meeting is tomorrow morning",
                "The meeting is tomorrow morning.<|assistant|>",
            ),
        )
    }

    @Test
    fun `a dropped url is rejected`() {
        assertEquals(
            RejectReason.DROPPED_ENTITY,
            reject(
                "please read https://example.com/docs before the call",
                "Please read the documentation before the call.",
            ),
        )
    }

    @Test
    fun `a rewritten url is rejected`() {
        assertEquals(
            RejectReason.DROPPED_ENTITY,
            reject(
                "please read https://example.com/docs before the call",
                "Please read https://example.com/documentation before the call.",
            ),
        )
    }

    @Test
    fun `a dropped email address is rejected`() {
        assertEquals(
            RejectReason.DROPPED_ENTITY,
            reject(
                "send the invoice to jane.doe@example.com when ready",
                "Send the invoice to Jane when it is ready please.",
            ),
        )
    }

    @Test
    fun `a changed number is rejected`() {
        assertEquals(
            RejectReason.DROPPED_ENTITY,
            reject(
                "the total came to 1234.56 for everything",
                "The total came to 1234.65 for everything.",
            ),
        )
    }

    @Test
    fun `a result far shorter than the input is rejected`() {
        assertEquals(
            RejectReason.TOO_SHORT,
            reject(
                "i wanted to check whether the delivery is still arriving on friday",
                "Is it Friday?",
            ),
        )
    }

    @Test
    fun `a result far longer than the input is rejected`() {
        assertEquals(
            RejectReason.TOO_LONG,
            reject(
                "the meeting is tomorrow",
                "The meeting is tomorrow, and I have also booked the large room on " +
                    "the third floor, invited the whole team, and ordered lunch for " +
                    "everyone who is planning to attend the session.",
            ),
        )
    }

    @Test
    fun `answering the text instead of correcting it is rejected`() {
        assertEquals(
            RejectReason.DIVERGED,
            reject("whats the capital of france", "The capital of France is Paris."),
        )
    }

    @Test
    fun `translating the text is rejected`() {
        assertEquals(
            RejectReason.DIVERGED,
            reject("the meeting is tomorrow morning", "La reunion es mañana por la mañana."),
        )
    }

    @Test
    fun `chat commentary is rejected`() {
        assertEquals(
            RejectReason.COMMENTARY,
            reject(
                "i will send it over later",
                "Sure! I will send it over later.",
            ),
        )
        assertEquals(
            RejectReason.COMMENTARY,
            reject(
                "i will send it over later",
                "Here's the corrected text: I will send it over later.",
            ),
        )
    }

    @Test
    fun `commentary detection does not fire when the user wrote it`() {
        // "Here's the plan" is a perfectly ordinary thing to type.
        assertEquals(
            "Here's the plan for tomorrow.",
            accept("heres the plan for tomorrow", "Here's the plan for tomorrow."),
        )
    }

    @Test
    fun `short inputs skip the similarity rule`() {
        // Fixing two letters in a four-letter word is a huge relative edit.
        assertEquals("The cat.", accept("teh cat", "The cat."))
    }

    // -------------------------------------------------------------- internals

    @Test
    fun `entities finds urls emails and numbers`() {
        val found = RefinementValidator.entities(
            "see https://example.com/a, mail jane@x.co about 3.14 at 10:30.",
        )
        assertTrue("https://example.com/a" in found, "found: $found")
        assertTrue("jane@x.co" in found, "found: $found")
        assertTrue("3.14" in found, "found: $found")
        assertTrue("10:30" in found, "found: $found")
    }

    @Test
    fun `verdict toString carries no content`() {
        val accepted = RefinementValidator.validate("the merger with acme", "The merger with Acme.")
        assertFalse("acme" in accepted.toString().lowercase(), accepted.toString())
        val rejected = RefinementValidator.validate("the merger with acme", "")
        assertFalse("acme" in rejected.toString().lowercase(), rejected.toString())
    }
}
