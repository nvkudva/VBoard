package com.vboard.core.clipboard

/** What [ClipboardHistory.offer] did with an incoming clip. */
sealed interface OfferResult {
    /** Stored (or bumped) as a normal, persistable clip. */
    data class Stored(val entry: ClipEntry, val deduplicated: Boolean) : OfferResult

    /** Held in memory only: a one-time code, card number, or key block. */
    data class SessionOnly(val entry: ClipEntry) : OfferResult

    /** Refused; nothing changed. */
    data class Discarded(val reason: DiscardReason) : OfferResult
}

/** Outcome of a pin request. */
enum class PinResult {
    PINNED,
    ALREADY_PINNED,

    /** [ClipLimits.maxPinned] reached; the user must unpin something first. */
    LIMIT_REACHED,

    /** No such entry (expired, evicted, or session-only). */
    NOT_FOUND,
}

/**
 * The clipboard history: capture rules, retention, limits, and the strip chip,
 * with no Android in sight so all of it is unit-testable.
 *
 * Entries are keyed by their text. De-duplication guarantees the text of a
 * stored entry is unique, so the app layer can address an entry by its content
 * without inventing ids that would then have to be persisted.
 *
 * Retention is enforced on read *and* on write, so an expired clip can never be
 * rendered even if nothing has written since it went stale.
 *
 * Not thread-safe: the app layer confines it to a single writer.
 */
class ClipboardHistory(
    private val clock: Clock,
    val limits: ClipLimits = ClipLimits(),
) {

    /** Newest first. Pinned and unpinned live in one list, ordered by capture time. */
    private val entries = mutableListOf<ClipEntry>()

    /**
     * The most recent session-only clip (OTP / card / key block). Never written
     * to disk, never pinnable, and dropped by [clearSessionOnly] on IME destroy.
     */
    private var sessionOnly: ClipEntry? = null

    /** Set when the user dismissed the chip; cleared by the next capture. */
    private var chipDismissedAt = Long.MIN_VALUE

    // ------------------------------------------------------------------ capture

    /**
     * Runs [ClipClassifier] over an incoming clip and stores it if allowed.
     * A clip equal to one already held bumps that entry to newest rather than
     * inserting a duplicate, preserving its pinned state.
     */
    fun offer(
        text: String,
        context: CaptureContext = CaptureContext(),
        markedSensitive: Boolean = false,
    ): OfferResult {
        val now = clock.nowMillis()
        prune(now)
        return when (val decision = ClipClassifier.classify(text, context, markedSensitive, limits)) {
            is ClipDecision.Discard -> OfferResult.Discarded(decision.reason)
            is ClipDecision.Keep -> when (decision.clipClass) {
                ClipClass.SESSION_ONLY -> {
                    val entry = ClipEntry(text, now, pinned = false)
                    sessionOnly = entry
                    chipDismissedAt = Long.MIN_VALUE
                    OfferResult.SessionOnly(entry)
                }
                ClipClass.NORMAL -> {
                    // A normal clip supersedes any live session-only one: the chip
                    // must show what the user just copied, not the older code.
                    sessionOnly = null
                    chipDismissedAt = Long.MIN_VALUE
                    val existingIndex = entries.indexOfFirst { it.text == text }
                    val bumped = existingIndex >= 0
                    val entry = if (bumped) {
                        entries.removeAt(existingIndex).copy(capturedAtMillis = now)
                    } else {
                        ClipEntry(text, now, pinned = false)
                    }
                    entries.add(0, entry)
                    enforceLimits(now)
                    OfferResult.Stored(entry, deduplicated = bumped)
                }
            }
        }
    }

    // -------------------------------------------------------------------- reads

    /** Pinned entries, newest first. Pinned clips never expire. */
    fun pinned(): List<ClipEntry> {
        prune(clock.nowMillis())
        return entries.filter { it.pinned }
    }

    /** Unpinned, unexpired entries, newest first. */
    fun recent(): List<ClipEntry> {
        prune(clock.nowMillis())
        return entries.filter { !it.pinned }
    }

    /** Everything that belongs on disk, newest first. */
    fun persistable(): List<ClipEntry> {
        prune(clock.nowMillis())
        return entries.toList()
    }

    fun isEmpty(): Boolean {
        prune(clock.nowMillis())
        return entries.isEmpty()
    }

    /**
     * The clip to offer as a suggestion-strip chip, or null. A clip qualifies for
     * [ClipLimits.chipWindowMillis] after capture and stops qualifying as soon as
     * [dismissChip] is called. Session-only clips are offered here — and only
     * here; they never appear in the panel and never reach disk.
     */
    fun chip(): ClipEntry? {
        val now = clock.nowMillis()
        prune(now)
        val candidate = listOfNotNull(sessionOnly, entries.firstOrNull())
            .maxByOrNull { it.capturedAtMillis } ?: return null
        if (candidate.capturedAtMillis <= chipDismissedAt) return null
        if (now - candidate.capturedAtMillis >= limits.chipWindowMillis) return null
        return candidate
    }

    /** Suppresses the chip for the current clip (a keystroke, or a tap on it). */
    fun dismissChip() {
        chipDismissedAt = clock.nowMillis()
    }

    // ------------------------------------------------------------------ mutation

    fun pin(text: String): PinResult {
        val now = clock.nowMillis()
        prune(now)
        val index = entries.indexOfFirst { it.text == text }
        if (index < 0) return PinResult.NOT_FOUND
        if (entries[index].pinned) return PinResult.ALREADY_PINNED
        if (entries.count { it.pinned } >= limits.maxPinned) return PinResult.LIMIT_REACHED
        entries[index] = entries[index].copy(pinned = true)
        return PinResult.PINNED
    }

    fun unpin(text: String) {
        val now = clock.nowMillis()
        prune(now)
        val index = entries.indexOfFirst { it.text == text }
        if (index < 0) return
        // An unpinned clip re-enters retention from its original capture time, so
        // an old pin unpinned after an hour disappears on the next prune. That is
        // the intent: nothing unpinned outlives the hour.
        entries[index] = entries[index].copy(pinned = false)
        prune(now)
        enforceLimits(now)
    }

    /** Deletes one entry. Also drops it as the session-only clip, if it is one. */
    fun delete(text: String) {
        entries.removeAll { it.text == text }
        if (sessionOnly?.text == text) sessionOnly = null
    }

    /** Deletes everything, pinned included. There is no "recently deleted". */
    fun deleteAll() {
        entries.clear()
        sessionOnly = null
        chipDismissedAt = clock.nowMillis()
    }

    /**
     * Deletes everything captured at or before [cutoffMillis], pinned included.
     *
     * This is how a "delete all" issued somewhere else — the settings screen —
     * reaches a history that may not even have finished loading yet. Phrasing it
     * as a cutoff rather than a wipe makes it idempotent and order-independent:
     * replaying an old cutoff removes nothing that was copied after it, so a
     * keyboard starting up cannot undo a clear, and cannot over-apply one either.
     *
     * Returns true when something was actually removed.
     */
    fun deleteCapturedBefore(cutoffMillis: Long): Boolean {
        val before = entries.size
        entries.removeAll { it.capturedAtMillis <= cutoffMillis }
        val sessionDropped = sessionOnly?.let { it.capturedAtMillis <= cutoffMillis } == true
        if (sessionDropped) sessionOnly = null
        return entries.size != before || sessionDropped
    }

    /** Drops the in-memory-only clip. Called when the IME is destroyed. */
    fun clearSessionOnly() {
        sessionOnly = null
    }

    // --------------------------------------------------------------- persistence

    /** The store document for [entries]; session-only clips are never included. */
    fun serialize(): String = ClipboardCodec.encode(persistable())

    /**
     * Replaces the history with [serialized]'s contents.
     *
     * Returns false when the document is unreadable — the caller must then leave
     * the file on disk untouched rather than saving an empty history over it,
     * turning a recoverable read failure into permanent loss.
     */
    fun restore(serialized: String): Boolean {
        val decoded = ClipboardCodec.decode(serialized) ?: return false
        entries.clear()
        decoded.asSequence()
            .filter { it.text.isNotEmpty() && it.text.length <= limits.maxChars }
            .sortedByDescending { it.capturedAtMillis }
            .forEach { entry ->
                // A file hand-edited (or corrupted in place) into duplicates must
                // not violate the uniqueness the rest of the class relies on.
                if (entries.none { it.text == entry.text }) entries.add(entry)
            }
        val now = clock.nowMillis()
        prune(now)
        enforceLimits(now)
        return true
    }

    // ------------------------------------------------------------------ internals

    /** Drops unpinned entries past [ClipLimits.retentionMillis]. Pinned never expire. */
    private fun prune(now: Long) {
        entries.removeAll { !it.pinned && now - it.capturedAtMillis >= limits.retentionMillis }
        sessionOnly?.let {
            if (now - it.capturedAtMillis >= limits.chipWindowMillis) sessionOnly = null
        }
    }

    private fun enforceLimits(now: Long) {
        // FIFO on unpinned entries.
        var unpinned = entries.count { !it.pinned }
        while (unpinned > limits.maxUnpinned) {
            val oldest = entries.indexOfLast { !it.pinned }
            if (oldest < 0) break
            entries.removeAt(oldest)
            unpinned--
        }
        // Total file budget: drop oldest unpinned first rather than fail the write.
        // The cheap upper bound short-circuits the common case, so the exact
        // (and comparatively expensive) encode only runs when it might matter.
        while (mayExceedBudget() && encodedBytes() > limits.maxFileBytes) {
            val oldest = entries.indexOfLast { !it.pinned }
            if (oldest < 0) break // Pinned entries alone exceed it; keep them.
            entries.removeAt(oldest)
        }
        prune(now)
    }

    /**
     * Upper bound on the encoded size: worst case a character costs six bytes
     * (a control character escaped as `\uXXXX`), plus per-entry framing.
     */
    private fun mayExceedBudget(): Boolean =
        entries.sumOf { it.text.length * 6 + ENTRY_OVERHEAD_BYTES } + STORE_OVERHEAD_BYTES >
            limits.maxFileBytes

    private fun encodedBytes(): Int =
        ClipboardCodec.encode(entries).toByteArray(Charsets.UTF_8).size

    private companion object {
        /** `{"t":"","at":9223372036854775807,"p":false},` and then some. */
        const val ENTRY_OVERHEAD_BYTES = 64
        const val STORE_OVERHEAD_BYTES = 32
    }
}
