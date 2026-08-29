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
 *  4. Visually empty                              -> discard.
 *  5. `^\p{Nd}{4,8}$` once invisible code points
 *     are dropped and the rest trimmed             -> session-only (one-time code).
 *  6. Luhn-valid 13-19 digit run in that same
 *     invisible-stripped text (payment card),
 *     or a `-----BEGIN ...-----` block            -> session-only.
 *  7. Otherwise                                   -> normal.
 *
 * Rule 4 is not in the product spec; a blank clip carries nothing to offer and
 * discarding it cannot change the outcome of any specified rule, because every
 * rule ahead of it also discards.
 *
 * Every digit test here means general category Nd — the same thing
 * `Character.isDigit(cp)` means — so the patterns and the Luhn arithmetic cannot
 * drift apart and leave a secret written in Arabic-Indic digits on disk.
 */
object ClipClassifier {

    private val OTP_PATTERN = Regex("""^\p{Nd}{4,8}$""")

    /** PEM-style armour: private keys, certificates, PGP blocks. */
    private val PEM_PATTERN = Regex("""-----BEGIN [^-\r\n]{0,64}-----""")

    /**
     * A run of digits optionally grouped by a single separator, as card numbers
     * are almost always pasted ("4111 1111 1111 1111"). Bounded by non-digits so
     * a longer number is not sliced into a false positive.
     *
     * The separator is any space separator (Zs) or dash (Pd), not just ASCII
     * space and hyphen-minus: a number copied out of a web page or a banking app
     * routinely arrives grouped with U+00A0, U+202F or an en dash, and an
     * ASCII-only separator class let every one of those evade Luhn.
     */
    private val DIGIT_RUN_PATTERN =
        Regex("""(?<!\p{Nd})\p{Nd}(?:[\p{Zs}\p{Pd}]?\p{Nd}){12,18}(?!\p{Nd})""")

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

        if (visuallyEmpty(text)) return ClipDecision.Discard(DiscardReason.BLANK)

        // Invisible characters ride along on copies from web pages and chat apps,
        // and `String.trim` never removes them: a code carrying a ZWSP would miss
        // the OTP shape and be persisted to disk as NORMAL. Strip them first, and
        // hand the card rule the same text — the two rules must not disagree about
        // what the user can see, or a ZWSP dropped into a card number breaks the
        // digit run, fails Luhn, and lands the number on disk.
        val visible = stripInvisible(text)
        if (OTP_PATTERN.matches(visible.trim())) {
            return ClipDecision.Keep(ClipClass.SESSION_ONLY)
        }
        if (containsPaymentCard(visible) || PEM_PATTERN.containsMatchIn(text)) {
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
            val digits = digitsOf(match.value)
            // Code points, not chars: an astral digit (U+1D7CE and friends) is two
            // chars, so a length check would mis-measure the run in both directions.
            digits.codePointCount(0, digits.length) in 13..19 && luhnValid(digits)
        }

    /** The Nd code points of [value], in order, with the group separators dropped. */
    private fun digitsOf(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            if (Character.isDigit(cp)) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /**
     * Standard Luhn (mod-10) checksum over a digits-only string.
     *
     * Walks code points so an astral digit is one value rather than two lone
     * surrogates, and reads each one with [Character.digit] so the value a digit
     * carries is decided by the same rule that admitted it.
     */
    fun luhnValid(digits: String): Boolean {
        if (digits.isEmpty()) return false
        var sum = 0
        var double = false
        var i = digits.length
        while (i > 0) {
            val cp = digits.codePointBefore(i)
            i -= Character.charCount(cp)
            var d = Character.digit(cp, 10)
            if (d < 0) return false
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }

    /**
     * True when [text] has nothing a user could see: whitespace of any kind,
     * format characters (ZWSP, ZWNJ, ZWJ, BOM, word joiner, soft hyphen) and
     * controls.
     *
     * A braille blank (U+2800) and a lone combining mark are deliberately *not*
     * blank — both mark a real glyph position.
     */
    private fun visuallyEmpty(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!isBlank(cp)) return false
            i += Character.charCount(cp)
        }
        return true
    }

    /**
     * [text] without its invisible code points.
     *
     * Whitespace survives, including the space characters `String.trim` leaves
     * behind (U+00A0, U+2007, U+202F — `Char.isWhitespace` covers `isSpaceChar`,
     * so `trim` does reach those): a space is a separator a user can act on, which
     * is why "12 34 56" is not a one-time code. A zero-width character separates
     * nothing, so it must not be able to hide a code's shape.
     */
    private fun stripInvisible(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!isInvisible(cp) || Character.isWhitespace(cp)) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /**
     * The one list of invisible categories. [visuallyEmpty] and [stripInvisible]
     * both read it here so they cannot drift into disagreeing about what a user
     * can see — that drift once let a code with a ZWSP through to disk.
     */
    private fun isInvisible(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.FORMAT.toInt() || type == Character.CONTROL.toInt()
    }

    private fun isBlank(cp: Int): Boolean =
        Character.isWhitespace(cp) || Character.isSpaceChar(cp) || isInvisible(cp)
}
