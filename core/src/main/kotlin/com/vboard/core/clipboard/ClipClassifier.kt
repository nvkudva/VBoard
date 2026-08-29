package com.vboard.core.clipboard

/**
 * Decides whether an incoming clip may be remembered, and if so how.
 *
 * The rules are ordered and first-match-wins; the order is the whole point, so
 * do not reorder without a test. A six-digit code that is *also* flagged
 * sensitive is discarded outright — it never becomes a session-only OTP.
 *
 *  1. Source app flagged the clip sensitive       -> discard, never stored, never shown.
 *  2. Focused field is a password field, or the
 *     editor opted out of personalized learning   -> discard while that field has focus.
 *  3. Longer than [ClipLimits.maxChars]           -> discard (never truncate).
 *  4. Blank                                       -> discard.
 *  5. `^\d{4,8}$` after trimming                   -> session-only (one-time code).
 *  6. Luhn-valid 13-19 digit run (payment card),
 *     or a `-----BEGIN ...-----` block            -> session-only.
 *  7. Otherwise                                   -> normal.
 *
 * Rule 4 is not in the product spec; a blank clip carries nothing to offer and
 * discarding it cannot change the outcome of any specified rule, because every
 * rule ahead of it also discards.
 */
object ClipClassifier {

    private val OTP_PATTERN = Regex("""^\d{4,8}$""")

    /** PEM-style armour: private keys, certificates, PGP blocks. */
    private val PEM_PATTERN = Regex("""-----BEGIN [^-\r\n]{0,64}-----""")

    /**
     * A run of digits optionally grouped by single spaces or hyphens, as card
     * numbers are almost always pasted ("4111 1111 1111 1111"). Bounded by
     * non-digits so a longer number is not sliced into a false positive.
     */
    private val DIGIT_RUN_PATTERN = Regex("""(?<!\d)\d(?:[ -]?\d){12,18}(?!\d)""")

    fun classify(
        text: String,
        context: CaptureContext = CaptureContext(),
        markedSensitive: Boolean = false,
        limits: ClipLimits = ClipLimits(),
    ): ClipDecision {
        if (markedSensitive) return ClipDecision.Discard(DiscardReason.MARKED_SENSITIVE)
        if (context.fieldIsPassword || context.noPersonalizedLearning) {
            return ClipDecision.Discard(DiscardReason.FIELD_NOT_CAPTURABLE)
        }
        if (text.length > limits.maxChars) return ClipDecision.Discard(DiscardReason.TOO_LONG)

        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ClipDecision.Discard(DiscardReason.BLANK)

        if (OTP_PATTERN.matches(trimmed)) return ClipDecision.Keep(ClipClass.SESSION_ONLY)
        if (containsPaymentCard(text) || PEM_PATTERN.containsMatchIn(text)) {
            return ClipDecision.Keep(ClipClass.SESSION_ONLY)
        }
        return ClipDecision.Keep(ClipClass.NORMAL)
    }

    /**
     * True when [text] contains a 13-19 digit number that passes the Luhn
     * checksum. Scans rather than matching the whole string, so a card number
     * pasted inside a sentence is still caught.
     */
    fun containsPaymentCard(text: String): Boolean =
        DIGIT_RUN_PATTERN.findAll(text).any { match ->
            val digits = match.value.filter { it.isDigit() }
            digits.length in 13..19 && luhnValid(digits)
        }

    /** Standard Luhn (mod-10) checksum over a digits-only string. */
    fun luhnValid(digits: String): Boolean {
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return false
        var sum = 0
        var double = false
        for (i in digits.lastIndex downTo 0) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }
}
