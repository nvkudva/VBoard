package com.vboard.core.model

/**
 * What the device's active connection looks like, as far as spending the user's money goes.
 *
 * Deliberately three states and not a boolean: "not unmetered" and "no connection at all"
 * lead to different UI, and collapsing them would either nag an offline user about data
 * charges or silently drop a download request.
 */
enum class NetworkState {
    /** Wi-Fi or another link the system reports as not metered. */
    UNMETERED,

    /** Cellular, or a hotspot/Wi-Fi the user has flagged as metered. */
    METERED,

    /** No usable connection right now. */
    OFFLINE,
}

/** What to do about one download request. */
sealed interface DownloadDecision {

    /**
     * Enqueue it.
     *
     * @property allowMetered false means the work is held until an unmetered link exists —
     *   the scheduler enforces this, so a download can never start on cellular by accident.
     * @property startsImmediately false means it is queued but waiting (offline, or waiting
     *   for Wi-Fi). The UI must say so; showing a stalled progress bar is what made the old
     *   flow feel broken.
     */
    data class Enqueue(
        val allowMetered: Boolean,
        val startsImmediately: Boolean,
    ) : DownloadDecision

    /**
     * The link is metered and the user has not agreed to spend data on it. Ask again,
     * stating [bytes] — an explicit second confirmation that names the real size.
     */
    data class ConfirmMetered(val bytes: Long) : DownloadDecision {
        val sizeText: String get() = ByteSize.format(bytes)
    }
}

/**
 * Decides whether a model download may start, and under what constraint.
 *
 * Pure by design: the Android layer only reports [NetworkState] and stores the user's
 * consent, so every combination is unit-testable and the "never spend cellular data by
 * default" rule cannot be lost inside a Compose callback.
 */
object DownloadPolicy {

    /**
     * @param network the connection as observed right now.
     * @param meteredConsent the user has explicitly agreed to use mobile data for this
     *   download (or turned the preference on). Never inferred, never sticky by default.
     * @param bytes the real size of what is being requested, for the confirmation copy.
     */
    fun decide(
        network: NetworkState,
        meteredConsent: Boolean,
        bytes: Long,
    ): DownloadDecision = when (network) {
        // Free either way. The constraint still tracks consent so that a link which turns
        // metered mid-download (Wi-Fi drops to cellular) does not quietly keep spending.
        NetworkState.UNMETERED -> DownloadDecision.Enqueue(
            allowMetered = meteredConsent,
            startsImmediately = true,
        )

        NetworkState.METERED ->
            if (meteredConsent) {
                DownloadDecision.Enqueue(allowMetered = true, startsImmediately = true)
            } else {
                DownloadDecision.ConfirmMetered(bytes)
            }

        // Offline: we cannot know what the next link will cost, so queue for Wi-Fi unless the
        // user has already said data is fine. Refusing outright would just lose the request.
        NetworkState.OFFLINE -> DownloadDecision.Enqueue(
            allowMetered = meteredConsent,
            startsImmediately = false,
        )
    }
}
