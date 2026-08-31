package com.vboard.app.ime

import com.vboard.core.text.CommitPlanner

/** Why a held utterance was or was not replayed. */
internal enum class ReplayVerdict {
    REPLAY,
    EXPIRED,
    OTHER_APP,
    FIELD_REFUSES,
}

/**
 * A finished utterance whose input connection died before the final ASR pass
 * returned (W0.2), plus the rule for which editor is allowed to receive it.
 *
 * Lives in memory only. Speech is never written anywhere, and the rule is
 * deliberately narrow: the point is to rescue the field the user was speaking
 * into, not to follow them into whatever they do next.
 */
internal data class PendingDictation(
    val text: String,
    val packageName: String?,
    val atMs: Long,
) {
    fun verdictFor(editorPackage: String?, fieldAcceptsVoice: Boolean, nowMs: Long): ReplayVerdict = when {
        nowMs - atMs > TTL_MS -> ReplayVerdict.EXPIRED
        // A null hold-side package cannot be proven to match, so it never does.
        packageName == null || packageName != editorPackage -> ReplayVerdict.OTHER_APP
        !fieldAcceptsVoice -> ReplayVerdict.FIELD_REFUSES
        else -> ReplayVerdict.REPLAY
    }

    companion object {
        /**
         * How long a held utterance stays replayable. Long enough to cover an
         * editor teardown and the app rebuilding its field, short enough that it
         * cannot surface in whatever the user is doing minutes later.
         */
        const val TTL_MS = 30_000L

        /**
         * Adds one dropped utterance to whatever is already held. Successive
         * drops in a burst accumulate in speaking order rather than the last one
         * replacing the rest; the timer restarts on each, since the user is
         * demonstrably still in the same session.
         */
        fun hold(
            existing: PendingDictation?,
            text: String,
            packageName: String?,
            nowMs: Long,
        ): PendingDictation? {
            if (text.isBlank()) return existing
            val merged = if (existing == null) {
                text
            } else {
                existing.text + CommitPlanner.joinForInsertion(existing.text, text)
            }
            return PendingDictation(merged, packageName, nowMs)
        }
    }
}
