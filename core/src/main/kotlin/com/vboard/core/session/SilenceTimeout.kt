package com.vboard.core.session

/**
 * How long the mic may stay hot with nothing being said before dictation stops
 * on its own.
 *
 * The two specs disagree — PRODUCT_SPEC VB-108 says 30s with "tap mic to
 * resume", DESIGN_SPEC §V2 says 8s and lists an Off/5/8/15s setting — and 30s
 * is the wrong side of that disagreement to guess on for a privacy-first
 * keyboard: a mic that stays open four times longer than the documentation says
 * is a trust problem, not a tuning one. [S8] is the default so the shipped
 * behaviour matches the tighter spec; whichever way the ruling lands, it is a
 * one-line change to [DEFAULT].
 */
enum class SilenceTimeout(val millis: Long?) {
    /** No automatic stop. The mic still dies with the editor and the session. */
    OFF(null),
    S5(5_000L),
    S8(8_000L),
    S15(15_000L),
    ;

    companion object {
        val DEFAULT = S8
    }
}
