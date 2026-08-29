package com.vboard.app.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.vboard.core.model.NetworkState

/**
 * Reports the active connection as a [NetworkState] for [com.vboard.core.model.DownloadPolicy].
 *
 * This is the whole Android half of the metered-data rule: read the state, hand it to the
 * pure policy, act on the decision. `ACCESS_NETWORK_STATE` had been declared in the manifest
 * since the first commit with no `ConnectivityManager` anywhere in the tree, so every
 * download — up to 610 MB of it — ran happily over cellular.
 */
object Connectivity {

    /**
     * Conservative by construction: anything we cannot positively identify as unmetered is
     * treated as metered or offline, never as free.
     */
    fun current(context: Context): NetworkState {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkState.OFFLINE
        val network = manager.activeNetwork ?: return NetworkState.OFFLINE
        val caps = manager.getNetworkCapabilities(network) ?: return NetworkState.OFFLINE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkState.OFFLINE
        }
        // NET_CAPABILITY_VALIDATED is deliberately not required: a captive portal still lets
        // WorkManager queue, and demanding it would report "offline" on networks that work.
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            NetworkState.UNMETERED
        } else {
            NetworkState.METERED
        }
    }
}
