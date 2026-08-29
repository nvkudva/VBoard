package com.vboard.core.clipboard

/**
 * One remembered clipboard item.
 *
 * [toString] is overridden to exclude [text] entirely — and to exclude anything
 * derived from it, including its length. Clip content must never reach logcat,
 * a crash report, or any other diagnostic, and a data class's generated
 * `toString` would put it there the first time anybody logged an entry, a list
 * of entries, or an object holding one. ClipRedactionTest guards this.
 */
data class ClipEntry(
    val text: String,
    val capturedAtMillis: Long,
    val pinned: Boolean = false,
) {
    override fun toString(): String =
        "ClipEntry(text=<redacted>, capturedAtMillis=$capturedAtMillis, pinned=$pinned)"
}

/**
 * How a captured clip may be kept.
 *
 * [NORMAL] clips go to the history file. [SESSION_ONLY] clips (one-time codes,
 * payment cards, key blocks) are held in memory for the strip chip's lifetime
 * and never written to disk, never pinned, and dropped when the IME goes away.
 */
enum class ClipClass { NORMAL, SESSION_ONLY }

/** Why an incoming clip was refused. Reasons carry no content. */
enum class DiscardReason {
    /** The source app marked the clip sensitive (`android.content.extra.IS_SENSITIVE`). */
    MARKED_SENSITIVE,

    /** The focused editor is a password field, or opted out of personalized learning. */
    FIELD_NOT_CAPTURABLE,

    /** Over [ClipLimits.maxChars]. Never truncated: a silent partial paste is worse. */
    TOO_LONG,

    /** Empty or whitespace only. */
    BLANK,
}

/** The classifier's verdict for one incoming clip. */
sealed interface ClipDecision {
    data class Keep(val clipClass: ClipClass) : ClipDecision
    data class Discard(val reason: DiscardReason) : ClipDecision
}

/**
 * What the focused editor allows, derived in the app layer from `EditorInfo`.
 * Kept separate from the clip itself: it gates capture, not the content.
 */
data class CaptureContext(
    val fieldIsPassword: Boolean = false,
    val noPersonalizedLearning: Boolean = false,
)

/** Tunables for the history. Overridable so tests can drive the edges cheaply. */
data class ClipLimits(
    val maxChars: Int = 5_000,
    val maxUnpinned: Int = 25,
    val maxPinned: Int = 20,
    val retentionMillis: Long = 60L * 60L * 1000L,
    val maxFileBytes: Int = 512 * 1024,
    /** How long a fresh clip is offered as a suggestion-strip chip. */
    val chipWindowMillis: Long = 60L * 1000L,
)

/** Injected time source: retention must never be tested against wall time. */
fun interface Clock {
    fun nowMillis(): Long
}
