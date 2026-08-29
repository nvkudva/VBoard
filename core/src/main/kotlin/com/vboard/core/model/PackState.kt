package com.vboard.core.model

/** Install lifecycle state of a [ModelPack], derived from disk plus in-flight progress. */
sealed interface PackState {
    data object NotInstalled : PackState

    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : PackState {
        /** Clamped: [bytesTotal] can be an estimate that the real download overshoots. */
        val fraction: Double
            get() = if (bytesTotal <= 0L) 0.0 else (bytesDone.toDouble() / bytesTotal.toDouble()).coerceIn(0.0, 1.0)
    }

    data object Verifying : PackState

    data object Installed : PackState

    data class Failed(val error: InstallError) : PackState
}

enum class InstallError { NETWORK, CHECKSUM_MISMATCH, INSUFFICIENT_STORAGE, CANCELLED, IO }
