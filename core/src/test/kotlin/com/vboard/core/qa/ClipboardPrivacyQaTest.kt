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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Clipboard classification is a **privacy boundary**, not a convenience feature:
 * `SESSION_ONLY` means "never written to disk", and `NORMAL` means "persisted for
 * an hour in a file". Getting the class wrong for a one-time code or a payment
 * card writes a secret to storage.
 *
 * The classifier's two secret-detecting rules are regular expressions built on
 * `\d` (`ClipClassifier.kt:26` and `:36`). In Java regex `\d` is ASCII-only
 * unless `UNICODE_CHARACTER_CLASS` is set, and it is not set. Every other digit
 * test in this codebase — `Char.isDigit()`, used in `luhnValid` and in
 * `TranscriptCleaner.isNumberLike` — *is* Unicode-aware. The two disagree, and
 * the disagreement falls on the unsafe side.
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

    // ----------------- VB-QA-24: the secret patterns are ASCII-only, isDigit is not

    @Test
    fun `a one-time code or card in non-ASCII digits is classified NORMAL (pinned)`() {
        // Arabic-Indic — what an SMS from an Arabic-locale bank actually contains.
        assertEquals(ClipDecision.Keep(ClipClass.NORMAL), ClipClassifier.classify("١٢٣٤٥٦"))
        // Extended Arabic-Indic (Persian/Urdu).
        assertEquals(ClipDecision.Keep(ClipClass.NORMAL), ClipClassifier.classify("۱۲۳۴۵۶"))
        // Devanagari.
        assertEquals(ClipDecision.Keep(ClipClass.NORMAL), ClipClassifier.classify("१२३४५६"))
        // Full-width — what a copy out of a CJK web form frequently produces.
        assertEquals(ClipDecision.Keep(ClipClass.NORMAL), ClipClassifier.classify("１２３４５６"))
        // A full card number in Arabic-Indic digits, likewise persisted.
        assertEquals(ClipDecision.Keep(ClipClass.NORMAL), ClipClassifier.classify("٤١١١١١١١١١١١١١١١"))

        // Meanwhile Char.isDigit() — the test used everywhere else — accepts them,
        // which is what makes the inconsistency a bug rather than a scope decision.
        assertTrue("١٢٣٤٥٦".all { it.isDigit() })
        assertTrue("１２３４５６".all { it.isDigit() })
    }

    @Test
    @Disabled("VB-QA-24: OTP_PATTERN and DIGIT_RUN_PATTERN (ClipClassifier.kt:26,36) use ASCII-only \\d, so a one-time code or payment card written in Arabic-Indic, Devanagari or full-width digits is persisted to disk instead of being held session-only")
    fun `a one-time code in any Unicode decimal digit should be session-only`() {
        for (code in listOf("١٢٣٤٥٦", "۱۲۳۴۵۶", "१२३४५६", "１２３４５６")) {
            assertEquals(ClipDecision.Keep(ClipClass.SESSION_ONLY), ClipClassifier.classify(code), "for <$code>")
        }
        assertEquals(ClipDecision.Keep(ClipClass.SESSION_ONLY), ClipClassifier.classify("٤١١١١١١١١١١١١١١١"))
    }

    // --------------------------------- VB-QA-25: zero-width text is not blank

    @Test
    fun `text made only of zero-width characters is stored (pinned)`() {
        // String.trim() removes characters <= U+0020 plus Unicode whitespace, but
        // ZWSP/ZWNJ/BOM are format characters, not whitespace. A clip that renders
        // as nothing occupies a history slot and shows as an empty chip.
        assertEquals(ClipDecision.Keep(ClipClass.NORMAL), ClipClassifier.classify("​"))
        assertEquals(ClipDecision.Keep(ClipClass.NORMAL), ClipClassifier.classify("​‌﻿"))
    }

    @Test
    @Disabled("VB-QA-25: ClipClassifier.classify trims with String.trim(), which does not remove zero-width format characters, so a visually empty clip is stored as NORMAL")
    fun `a visually empty clip should be discarded as blank`() {
        assertEquals(ClipDecision.Discard(DiscardReason.BLANK), ClipClassifier.classify("​"))
        assertEquals(ClipDecision.Discard(DiscardReason.BLANK), ClipClassifier.classify("​‌﻿"))
    }

    // ----------------------------- VB-QA-26: luhnValid disagrees with itself

    @Test
    fun `luhnValid accepts non-ASCII digits and computes nonsense for them (pinned)`() {
        // The guard is `digits.any { !it.isDigit() }` (Unicode-aware) but the
        // arithmetic is `digits[i] - '0'` (ASCII-only). Arabic-Indic digits pass
        // the guard and are then valued at ~1626 each.
        assertTrue(ClipClassifier.luhnValid("١٢٣٤"))
        assertTrue(!ClipClassifier.luhnValid("１２３４"))
        // The ASCII answers are unaffected and must stay right.
        assertTrue(ClipClassifier.luhnValid("4111111111111111"))
        assertTrue(!ClipClassifier.luhnValid("4111111111111112"))
        assertTrue(!ClipClassifier.luhnValid(""))
        assertTrue(!ClipClassifier.luhnValid("abc"))
    }

    @Test
    @Disabled("VB-QA-26: luhnValid (ClipClassifier.kt:72) validates with Char.isDigit() but evaluates with `digits[i] - '0'`, so any non-ASCII decimal digit produces a meaningless checksum")
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
