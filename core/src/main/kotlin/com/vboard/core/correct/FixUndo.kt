package com.vboard.core.correct

/** What the toolbar's AI-fix action should currently look like. */
enum class FixButtonState {
    /** Ready to run. */
    IDLE,

    /** A fix is in flight; taps are ignored. */
    RUNNING,

    /** A fix landed and can still be taken back. */
    UNDO,

    /** Not available in this field, and shown as such. */
    DISABLED,
}

/**
 * Everything needed to put a field back exactly as it was before a fix.
 *
 * Not a data class, and no length is exposed anywhere: the original text is user
 * content and this type must be safe to print.
 */
class FixUndoSnapshot internal constructor(
    private val original: String,
    /** Absolute selection offsets in the field, captured before the replacement. */
    val selectionStart: Int,
    val selectionEnd: Int,
    private val applied: String,
    /** Identity of the editor session the snapshot belongs to. */
    val fieldToken: Long,
    val expiresAtMs: Long,
) {
    fun originalText(): String = original

    fun appliedText(): String = applied

    /**
     * True when the field still holds exactly what the fix wrote. An undo that
     * cannot prove this must not fire — the user has edited since, and blowing
     * their edit away would be a second surprise on top of the first.
     */
    fun matchesField(currentText: String): Boolean = currentText == applied

    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs

    override fun toString(): String =
        "FixUndoSnapshot(fieldToken=$fieldToken, expiresAtMs=$expiresAtMs)"
}

/**
 * Holds the single outstanding undo.
 *
 * One tap of "AI fix" replaces everything the user has written, so the undo is
 * not a nicety — it is the thing that makes the feature safe to try. The rules
 * here are deliberately strict:
 *
 *  - exactly one snapshot at a time (a fix taken after a fix replaces it);
 *  - it belongs to one editor session, identified by a token, so an undo can
 *    never write one field's text into another even if a caller forgets to
 *    clear it;
 *  - it dies after [windowMs], and on any edit the user makes.
 *
 * Time is passed in rather than read, so the whole model is testable.
 */
class FixUndoStore(val windowMs: Long = DEFAULT_WINDOW_MS) {

    private var snapshot: FixUndoSnapshot? = null

    val isArmed: Boolean get() = snapshot != null

    fun record(
        original: String,
        selectionStart: Int,
        selectionEnd: Int,
        applied: String,
        fieldToken: Long,
        nowMs: Long,
    ): FixUndoSnapshot {
        val recorded = FixUndoSnapshot(
            original = original,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            applied = applied,
            fieldToken = fieldToken,
            expiresAtMs = nowMs + windowMs,
        )
        snapshot = recorded
        return recorded
    }

    /** The live snapshot for [fieldToken], or null. Drops stale/foreign state. */
    fun peek(nowMs: Long, fieldToken: Long): FixUndoSnapshot? {
        val held = snapshot ?: return null
        if (held.fieldToken != fieldToken || held.isExpired(nowMs)) {
            snapshot = null
            return null
        }
        return held
    }

    /**
     * Points the outstanding undo at [applied] instead, keeping the original text
     * and the existing deadline.
     *
     * This is what keeps whole-field undo alive after a per-change revert: the
     * field has moved, but what the user would want back has not, and expiring
     * the safety net early because they took one word back would be backwards.
     */
    fun reapply(applied: String, nowMs: Long, fieldToken: Long): Boolean {
        val held = peek(nowMs, fieldToken) ?: return false
        snapshot = FixUndoSnapshot(
            original = held.originalText(),
            selectionStart = held.selectionStart,
            selectionEnd = held.selectionEnd,
            applied = applied,
            fieldToken = held.fieldToken,
            expiresAtMs = held.expiresAtMs,
        )
        return true
    }

    /** [peek], and forget it: an undo is only offered once. */
    fun consume(nowMs: Long, fieldToken: Long): FixUndoSnapshot? =
        peek(nowMs, fieldToken)?.also { snapshot = null }

    /** Milliseconds of undo left, 0 when nothing is armed. */
    fun remainingMs(nowMs: Long, fieldToken: Long): Long {
        val held = peek(nowMs, fieldToken) ?: return 0
        return (held.expiresAtMs - nowMs).coerceAtLeast(0)
    }

    fun clear() {
        snapshot = null
    }

    fun buttonState(
        nowMs: Long,
        fieldToken: Long,
        running: Boolean,
        enabled: Boolean,
    ): FixButtonState = when {
        !enabled -> FixButtonState.DISABLED
        running -> FixButtonState.RUNNING
        peek(nowMs, fieldToken) != null -> FixButtonState.UNDO
        else -> FixButtonState.IDLE
    }

    override fun toString(): String = "FixUndoStore(armed=${snapshot != null})"

    companion object {
        /** Undo stays offered this long, or until the user types (VB-232). */
        const val DEFAULT_WINDOW_MS = 15_000L
    }
}
