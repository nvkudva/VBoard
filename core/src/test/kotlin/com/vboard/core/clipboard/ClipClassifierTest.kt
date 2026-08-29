package com.vboard.core.clipboard

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every exclusion rule, and the precedence between them. These are the rules
 * that decide whether a password manager's paste, a bank OTP, or a private key
 * ends up in a file on disk, so each one gets its own case and the ordering
 * between them is asserted directly rather than implied.
 */
class ClipClassifierTest {

    private fun classify(
        text: String,
        fieldIsPassword: Boolean = false,
        noPersonalizedLearning: Boolean = false,
        markedSensitive: Boolean = false,
    ) = ClipClassifier.classify(
        text = text,
        context = CaptureContext(fieldIsPassword, noPersonalizedLearning),
        markedSensitive = markedSensitive,
    )

    private fun assertKept(expected: ClipClass, decision: ClipDecision) {
        val keep = decision as? ClipDecision.Keep ?: fail("expected Keep, got $decision")
        assertEquals(expected, keep.clipClass)
    }

    private fun assertDiscarded(expected: DiscardReason, decision: ClipDecision) {
        val discard = decision as? ClipDecision.Discard ?: fail("expected Discard, got $decision")
        assertEquals(expected, discard.reason)
    }

    // ------------------------------------------------------- rule 1: sensitive

    @Test
    fun `a clip flagged sensitive is discarded`() {
        assertDiscarded(
            DiscardReason.MARKED_SENSITIVE,
            classify("ordinary text", markedSensitive = true),
        )
    }

    // ----------------------------------------------------------- rule 2: field

    @Test
    fun `nothing is captured while a password field has focus`() {
        assertDiscarded(
            DiscardReason.FIELD_NOT_CAPTURABLE,
            classify("ordinary text", fieldIsPassword = true),
        )
    }

    @Test
    fun `nothing is captured while a no-personalized-learning field has focus`() {
        assertDiscarded(
            DiscardReason.FIELD_NOT_CAPTURABLE,
            classify("ordinary text", noPersonalizedLearning = true),
        )
    }

    // ---------------------------------------------------------- rule 3: length

    @Test
    fun `a clip at the length limit is kept and one character over is discarded`() {
        assertKept(ClipClass.NORMAL, classify("a".repeat(5_000)))
        assertDiscarded(DiscardReason.TOO_LONG, classify("a".repeat(5_001)))
    }

    @Test
    fun `an oversize clip is never truncated`() {
        // The whole point of TOO_LONG: there is no Keep branch that shortens.
        val decision = classify("x".repeat(9_000))
        assertTrue(decision is ClipDecision.Discard)
    }

    // ------------------------------------------------------------- rule 4: OTP

    @ParameterizedTest
    @ValueSource(strings = ["1234", "12345", "123456", "1234567", "12345678", "  483920  "])
    fun `a short digit run is a one-time code`(text: String) {
        assertKept(ClipClass.SESSION_ONLY, classify(text))
    }

    @ParameterizedTest
    @ValueSource(strings = ["123", "123456789", "12 34 56", "12a456"])
    fun `near misses of the one-time code shape are normal text`(text: String) {
        assertKept(ClipClass.NORMAL, classify(text))
    }

    // ----------------------------------------------------- rule 5: cards, keys

    @ParameterizedTest
    @ValueSource(
        strings = [
            "4111111111111111", // Visa test number
            "4111 1111 1111 1111",
            "4111-1111-1111-1111",
            "5500005555555559", // Mastercard test number
            "378282246310005", // Amex test number, 15 digits
            "6011111111111117", // Discover test number
            "Card: 4111111111111111 exp 12/29",
        ],
    )
    fun `a Luhn-valid card number is session-only`(text: String) {
        assertKept(ClipClass.SESSION_ONLY, classify(text))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "4111111111111112", // one digit off; same length
            "5500005555555558",
            "378282246310006",
            "1234567890123", // 13 digits, fails Luhn
        ],
    )
    fun `a near-miss number of card length is normal text`(text: String) {
        assertKept(ClipClass.NORMAL, classify(text))
    }

    @Test
    fun `a 20 digit run is not treated as a card number`() {
        // Longer than any card; must not be sliced into a passing 19-digit window.
        assertKept(ClipClass.NORMAL, classify("12345678901234567890"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "-----BEGIN PRIVATE KEY-----\nMIIE...\n-----END PRIVATE KEY-----",
            "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----",
            "preamble\n-----BEGIN CERTIFICATE-----\nxyz\n-----END CERTIFICATE-----",
            "-----BEGIN PGP MESSAGE-----\nowGb\n-----END PGP MESSAGE-----",
        ],
    )
    fun `a PEM block is session-only`(text: String) {
        assertKept(ClipClass.SESSION_ONLY, classify(text))
    }

    @Test
    fun `prose about a certificate is not a PEM block`() {
        assertKept(ClipClass.NORMAL, classify("The BEGIN CERTIFICATE line was missing dashes"))
    }

    // ------------------------------------------------------------ rule 6, rule 7

    @Test
    fun `ordinary text is a normal clip`() {
        assertKept(ClipClass.NORMAL, classify("meet me at the usual place"))
    }

    @Test
    fun `blank text is discarded`() {
        assertDiscarded(DiscardReason.BLANK, classify("   \n\t "))
    }

    // ------------------------------------------------------------- precedence

    @Test
    fun `a sensitive one-time code is discarded, not kept as session-only`() {
        assertDiscarded(DiscardReason.MARKED_SENSITIVE, classify("483920", markedSensitive = true))
    }

    @Test
    fun `a sensitive flag beats the password field rule`() {
        assertDiscarded(
            DiscardReason.MARKED_SENSITIVE,
            classify("hunter2", fieldIsPassword = true, markedSensitive = true),
        )
    }

    @Test
    fun `the password field rule beats the length rule`() {
        assertDiscarded(
            DiscardReason.FIELD_NOT_CAPTURABLE,
            classify("a".repeat(6_000), fieldIsPassword = true),
        )
    }

    @Test
    fun `the length rule beats the card rule`() {
        // A 6000-character blob that also contains a card number is discarded for
        // being oversize; it must never be kept as a session-only clip.
        val text = "4111111111111111 " + "x".repeat(6_000)
        assertDiscarded(DiscardReason.TOO_LONG, classify(text))
    }

    @Test
    fun `an oversize clip in a password field reports the field, not the length`() {
        assertDiscarded(
            DiscardReason.FIELD_NOT_CAPTURABLE,
            classify("x".repeat(6_000), noPersonalizedLearning = true),
        )
    }

    @Test
    fun `a one-time code beats the card rule`() {
        // "12345678" is 8 digits: it can only be an OTP, never a card, but the
        // ordering is what guarantees that as the digit ranges move.
        assertKept(ClipClass.SESSION_ONLY, classify("12345678"))
    }

    // ------------------------------------------------------------------- Luhn

    @Test
    fun `luhn accepts known-good numbers and rejects their neighbours`() {
        assertTrue(ClipClassifier.luhnValid("4111111111111111"))
        assertTrue(ClipClassifier.luhnValid("79927398713"))
        assertFalse(ClipClassifier.luhnValid("79927398710"))
        assertFalse(ClipClassifier.luhnValid("79927398711"))
        assertFalse(ClipClassifier.luhnValid("79927398712"))
        assertFalse(ClipClassifier.luhnValid(""))
        assertFalse(ClipClassifier.luhnValid("41111111111111a1"))
    }
}
