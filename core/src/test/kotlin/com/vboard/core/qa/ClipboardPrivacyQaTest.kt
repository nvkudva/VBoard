package com.vboard.core.qa

import com.vboard.core.clipboard.CaptureContext
import com.vboard.core.clipboard.ClipClass
import com.vboard.core.clipboard.ClipClassifier
import com.vboard.core.clipboard.ClipDecision
import com.vboard.core.clipboard.ClipLimits
import com.vboard.core.clipboard.ClipboardHistory
import com.vboard.core.clipboard.Clock
import com.vboard.core.clipboard.DiscardReason
import com.vboard.core.clipboard.OfferResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Clipboard classification is a **privacy boundary**, not a convenience feature:
 * `SESSION_ONLY` means "never written to disk", and `NORMAL` means "persisted for
 * an hour in a file". Getting the class wrong for a one-time code or a payment
 * card writes a secret to storage.
 *
 * The classifier's two secret-detecting rules are regular expressions, and they
 * used to be built on `\d`, which in Java regex is ASCII-only unless
 * `UNICODE_CHARACTER_CLASS` is set. Every other digit test in this codebase —
 * `Char.isDigit()`, used in `luhnValid` and in `TranscriptCleaner.isNumberLike` —
 * is Unicode-aware, so the two disagreed, and the disagreement fell on the unsafe
 * side. They all mean general category Nd now, and these tests hold them there.
 *
 * This file also covers the classification-to-retention seam, which nothing else
 * exercised end to end: a decision is only worth as much as what the history does
 * with it.
 */
class ClipboardPrivacyQaTest {

    private class FakeClock(var now: Long = 1_000_000L) : Clock {
        override fun nowMillis(): Long = now
    }

    // ------------------------------------------------------ the rules that work

    @Test
    fun `ASCII one-time codes and payment cards are session-only`() {
        for (code in listOf("1234", "12345", "123456", "1234567", "12345678", " 123456 ", "\n123456\n")) {
            assertEquals(ClipDecision.Keep(ClipClass.SESSION_ONLY), ClipClassifier.classify(code), "for <$code>")
        }
        for (card in listOf(
            "4111111111111111", "4111 1111 1111 1111", "4111-1111-1111-1111",
            "your card is 4111 1111 1111 1111 thanks", "5500005555555559",
        )) {
            assertEquals(ClipDecision.Keep(ClipClass.SESSION_ONLY), ClipClassifier.classify(card), "for <$card>")
        }
        assertEquals(
            ClipDecision.Keep(ClipClass.SESSION_ONLY),
            ClipClassifier.classify("-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----"),
        )
    }

    @Test
    fun `ordinary text and near-miss numbers stay normal`() {
        for (text in listOf(
            "hello world", "123", "123456789", "12345678901234567890",
            "4111111111111112", "12 34 56", "2026-08-29",
        )) {
            assertEquals(ClipDecision.Keep(ClipClass.NORMAL), ClipClassifier.classify(text), "for <$text>")
        }
    }

    @Test
    fun `the discard rules are ordered as documented`() {
        val limits = ClipLimits(maxChars = 10)
        assertEquals(
            ClipDecision.Discard(DiscardReason.MARKED_SENSITIVE),
            ClipClassifier.classify("123456", markedSensitive = true),
        )
        assertEquals(
            ClipDecision.Discard(DiscardReason.FIELD_NOT_CAPTURABLE),
            ClipClassifier.classify("123456", CaptureContext(fieldIsPassword = true)),
        )
        assertEquals(
            ClipDecision.Discard(DiscardReason.FIELD_NOT_CAPTURABLE),
            ClipClassifier.classify("123456", CaptureContext(noPersonalizedLearning = true)),
        )
        // Length is checked before blankness, so an over-long run of spaces is
        // TOO_LONG rather than BLANK. Both discard, so the order is harmless.
        assertEquals(
            ClipDecision.Discard(DiscardReason.TOO_LONG),
            ClipClassifier.classify(" ".repeat(11), limits = limits),
        )
        assertEquals(ClipDecision.Discard(DiscardReason.BLANK), ClipClassifier.classify("   \n\t "))
    }

    // ----------------- VB-QA-24: the secret patterns mean the same digit isDigit does

    @Test
    fun `a one-time code or card in non-ASCII digits is session-only (pinned)`() {
        // Arabic-Indic — what an SMS from an Arabic-locale bank actually contains.
        assertEquals(ClipDecision.Keep(ClipClass.SESSION_ONLY), ClipClassifier.classify("١٢٣٤٥٦"))
        // Extended Arabic-Indic (Persian/Urdu).
        assertEquals(ClipDecision.Keep(ClipClass.SESSION_ONLY), ClipClassifier.classify("۱۲۳۴۵۶"))
        // Devanagari.
        assertEquals(ClipDecision.Keep(ClipClass.SESSION_ONLY), ClipClassifier.classify("१२३४५६"))
        // Full-width — what a copy out of a CJK web form frequently produces.
        assertEquals(ClipDecision.Keep(ClipClass.SESSION_ONLY), ClipClassifier.classify("１２３４５６"))
        // A full card number in Arabic-Indic digits, likewise held session-only.
        assertEquals(ClipDecision.Keep(ClipClass.SESSION_ONLY), ClipClassifier.classify("٤١١١١١١١١١١١١١١١"))

        // The patterns now mean what Char.isDigit() — the test used everywhere
        // else in this codebase — means. That agreement is what this pins.
        assertTrue("١٢٣٤٥٦".all { it.isDigit() })
        assertTrue("１２３４５６".all { it.isDigit() })
    }

    @Test
    fun `a one-time code in any Unicode decimal digit should be session-only`() {
        for (code in listOf("١٢٣٤٥٦", "۱۲۳۴۵۶", "१२३४५६", "１２３４５６")) {
            assertEquals(ClipDecision.Keep(ClipClass.SESSION_ONLY), ClipClassifier.classify(code), "for <$code>")
        }
        assertEquals(ClipDecision.Keep(ClipClass.SESSION_ONLY), ClipClassifier.classify("٤١١١١١١١١١١١١١١١"))
    }

    // --------------------------------- VB-QA-25: zero-width text counts as blank

    @Test
    fun `text made only of zero-width characters is discarded (pinned)`() {
        // String.trim() removes characters <= U+0020 plus Unicode whitespace, but
        // ZWSP/ZWNJ/BOM are format characters, not whitespace, so blankness is
        // decided by category instead. A clip that renders as nothing must not
        // occupy a history slot and show as an empty chip.
        assertEquals(ClipDecision.Discard(DiscardReason.BLANK), ClipClassifier.classify("​"))
        assertEquals(ClipDecision.Discard(DiscardReason.BLANK), ClipClassifier.classify("​‌﻿"))
    }

    @Test
    fun `a one-time code wearing an invisible character is still session-only`() {
        // The follow-up to VB-QA-24/-25 the privacy audit found: blankness became
        // code-point aware, but the OTP rule still used String.trim(), which never
        // removes format characters. A code carrying a ZWSP or a BOM — routine on
        // text copied from web pages and chat apps — was neither blank nor
        // OTP-shaped, so it fell through to NORMAL and was written to the history
        // file on disk. Invisible characters must not hide a code's shape.
        for (code in listOf("\u200B123456", "123456\uFEFF", "12\u00AD3456", "\u2060123456\u200C")) {
            assertEquals(ClipDecision.Keep(ClipClass.SESSION_ONLY), ClipClassifier.classify(code), "for <$code>")
        }
        // Non-ASCII digits go the same way; the digit rule and the strip are independent.
        assertEquals(ClipDecision.Keep(ClipClass.SESSION_ONLY), ClipClassifier.classify("\u0661\u0662\u0663\u0664\u0665\u0666\u200B"))
        // Spaces are visible separators, not invisible: this stays ordinary text.
        assertEquals(ClipDecision.Keep(ClipClass.NORMAL), ClipClassifier.classify("12 34 56"))
    }

    @Test
    fun `a payment card wearing an invisible character is still session-only`() {
        // The same evasion as the one-time code's, one rule over: the OTP test was
        // given the invisible-stripped text and the card test was handed the raw
        // clip, so a ZWSP pasted inside a card number broke the digit run, failed
        // Luhn, and wrote the number to the history file on disk.
        for (card in listOf(
            "4111\u200B111111111111",
            "4111111111\u2060111111",
            "4111\u00AD1111\u00AD1111\u00AD1111",
            "your card is 4111 1111 1111\uFEFF 1111 thanks",
        )) {
            assertEquals(ClipDecision.Keep(ClipClass.SESSION_ONLY), ClipClassifier.classify(card), "for <$card>")
        }
    }

    @Test
    fun `a payment card grouped by a non-ASCII separator is still session-only`() {
        // Cards copied out of web pages and banking apps arrive grouped with an
        // NBSP or an en dash. The digit class was internationalized and the
        // separator class was left ASCII, so those groupings evaded Luhn.
        for (card in listOf(
            "4111\u00A01111\u00A01111\u00A01111", // no-break space
            "4111\u202F1111\u202F1111\u202F1111", // narrow no-break space
            "4111\u20071111\u20071111\u20071111", // figure space
            "4111\u20101111\u20101111\u20101111", // hyphen (not hyphen-minus)
            "4111\u20131111\u20131111\u20131111", // en dash
            "4111\u20141111\u20141111\u20141111", // em dash
        )) {
            assertEquals(ClipDecision.Keep(ClipClass.SESSION_ONLY), ClipClassifier.classify(card), "for <$card>")
        }
    }

    @Test
    fun `the widened card rule still leaves ordinary numbers normal`() {
        // The other half of the boundary: a classifier that grows too eager starts
        // discarding clips a user wanted kept.
        for (text in listOf(
            "4111\u00A01111\u00A01111\u00A01112", // one digit off, so not a card
            "+1\u00A0415\u00A0555\u00A00132", // a phone number
            "2026\u201308\u201329",
            "1234\u20135678\u20139012",
            "4111\u200B1111\u200B1111\u200B1112",
        )) {
            assertEquals(ClipDecision.Keep(ClipClass.NORMAL), ClipClassifier.classify(text), "for <$text>")
        }
    }

    @Test
    fun `a visually empty clip should be discarded as blank`() {
        assertEquals(ClipDecision.Discard(DiscardReason.BLANK), ClipClassifier.classify("​"))
        assertEquals(ClipDecision.Discard(DiscardReason.BLANK), ClipClassifier.classify("​‌﻿"))
    }

    // ----------------------------- VB-QA-26: luhnValid agrees with itself

    @Test
    fun `luhnValid values a non-ASCII digit by the rule that admitted it (pinned)`() {
        // Guard and arithmetic are the same Character.digit(cp, 10) now, so an
        // Arabic-Indic digit is worth its face value: 1,2,3,4 -> 2+2+6+4 = 14.
        assertTrue(!ClipClassifier.luhnValid("١٢٣٤"))
        assertTrue(!ClipClassifier.luhnValid("１２３４"))
        // The ASCII answers are unaffected and must stay right.
        assertTrue(ClipClassifier.luhnValid("4111111111111111"))
        assertTrue(!ClipClassifier.luhnValid("4111111111111112"))
        assertTrue(!ClipClassifier.luhnValid(""))
        assertTrue(!ClipClassifier.luhnValid("abc"))
    }

    @Test
    fun `luhnValid should agree with itself about what a digit is`() {
        // Either reject non-ASCII digits at the guard, or value them correctly.
        // "١٢٣٤" is Luhn-invalid (1,2,3,4 -> 2+2+6+4 = 14), so the answer is false.
        assertTrue(!ClipClassifier.luhnValid("١٢٣٤"))
        assertTrue(ClipClassifier.luhnValid("٤١١١١١١١١١١١١١١١"))
    }

    // ------------------------------------- classification feeding retention

    @Test
    fun `a session-only clip never reaches the persistable set or the panel`() {
        val clock = FakeClock()
        val history = ClipboardHistory(clock)
        assertTrue(history.offer("123456") is OfferResult.SessionOnly)
        assertEquals(emptyList(), history.persistable())
        assertEquals(emptyList(), history.recent())
        assertEquals(emptyList(), history.pinned())
        assertEquals("123456", history.chip()?.text)
        assertTrue(history.isEmpty())
        assertTrue("123456" !in history.serialize())
        // ...and it is not pinnable.
        assertEquals(com.vboard.core.clipboard.PinResult.NOT_FOUND, history.pin("123456"))
    }

    @Test
    fun `a session-only clip expires with the chip window, not the retention window`() {
        val clock = FakeClock()
        val limits = ClipLimits()
        val history = ClipboardHistory(clock, limits)
        history.offer("123456")
        clock.now += limits.chipWindowMillis - 1
        assertEquals("123456", history.chip()?.text)
        clock.now += 1
        assertEquals(null, history.chip())
        assertTrue(history.isEmpty())
    }

    @Test
    fun `a discarded clip changes nothing observable`() {
        val clock = FakeClock()
        val history = ClipboardHistory(clock)
        history.offer("keep me")
        val before = history.serialize()
        for (attempt in listOf<() -> OfferResult>(
            { history.offer("secret", markedSensitive = true) },
            { history.offer("secret", CaptureContext(fieldIsPassword = true)) },
            { history.offer("secret", CaptureContext(noPersonalizedLearning = true)) },
            { history.offer("   ") },
            { history.offer("x".repeat(history.limits.maxChars + 1)) },
        )) {
            assertTrue(attempt() is OfferResult.Discarded)
            assertEquals(before, history.serialize(), "a discarded clip mutated the store")
            assertEquals("keep me", history.chip()?.text, "a discarded clip changed the chip")
        }
    }

    @Test
    fun `restore rejects an unreadable document rather than emptying the history`() {
        val clock = FakeClock()
        val history = ClipboardHistory(clock)
        history.offer("keep me")
        val good = history.serialize()
        for (broken in listOf("", "{", "not json", "[]", "{\"v\":999}")) {
            val accepted = history.restore(broken)
            if (!accepted) {
                assertEquals(good, history.serialize(), "a rejected restore still cleared the history")
            }
        }
    }

    @Test
    fun `no serialized document ever contains a session-only clip, over a random sequence`() {
        // The invariant the whole privacy story rests on, fuzzed over a mixed
        // stream of secrets and ordinary text.
        val clock = FakeClock()
        val history = ClipboardHistory(clock)
        val secrets = listOf("123456", "4111 1111 1111 1111", "9182", "-----BEGIN PGP MESSAGE-----")
        val ordinary = listOf("hello", "meet at noon", "https://example.com", "abc 123", "12345678901234567890")
        val random = kotlin.random.Random(4242)
        repeat(500) {
            val secret = random.nextBoolean()
            val text = if (secret) secrets.random(random) else ordinary.random(random)
            history.offer(text)
            clock.now += random.nextLong(0, 5_000)
            for (entry in history.persistable()) {
                val reclassified = ClipClassifier.classify(entry.text)
                assertEquals(
                    ClipDecision.Keep(ClipClass.NORMAL), reclassified,
                    "a clip that classifies as ${'$'}reclassified is in the persistable set",
                )
                assertTrue(entry.text !in secrets, "session-only clip <${'$'}{entry.text}> was persisted")
            }
        }
    }
}
