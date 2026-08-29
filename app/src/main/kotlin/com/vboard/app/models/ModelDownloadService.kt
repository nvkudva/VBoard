package com.vboard.app.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.vboard.app.R
import com.vboard.app.VBoardApp
import com.vboard.app.onboarding.OnboardingActivity
import com.vboard.core.model.InstallError
import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.PackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that downloads and installs model packs with a progress
 * notification. UI observes [states] for in-app progress.
 */
class ModelDownloadService : LifecycleService() {

    companion object {
        private const val TAG = "VBoardDownload"
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 41
        private const val MAX_NETWORK_RETRIES = 4
        private const val RETRY_BACKOFF_MS = 3_000L
        private const val WAKE_LOCK_TAG = "VBoard:modelDownload"

        /** Safety valve: the lock is released explicitly when the drain finishes. */
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L
        const val EXTRA_PACK_ID = "pack_id"
        const val ACTION_DOWNLOAD = "com.vboard.app.action.DOWNLOAD"
        const val ACTION_CANCEL = "com.vboard.app.action.CANCEL"

        /** Live install state per pack id, for the onboarding/settings UI. */
        private val _states = MutableStateFlow<Map<String, PackState>>(emptyMap())
        val states: StateFlow<Map<String, PackState>> = _states

        fun start(context: Context, packId: String) {
            val intent = Intent(context, ModelDownloadService::class.java)
                .setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_PACK_ID, packId)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java)
                .setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }

    private val app get() = application as VBoardApp
    private var downloadJob: Job? = null

    /**
     * Pending pack ids. Guarded by [queueLock]: it was a plain ArrayDeque mutated
     * from the main thread (onStartCommand) and an IO thread (the drain loop).
     */
    private val queueLock = Any()
    private val queue = ArrayDeque<String>()

    /** Whether a drain loop is running. Guarded by [queueLock]. */
    private var draining = false

    /** Held while downloading so a screen-off transfer isn't killed mid-read. */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                val packId = intent.getStringExtra(EXTRA_PACK_ID) ?: return START_NOT_STICKY
                enqueue(packId)
            }
            ACTION_CANCEL -> {
                synchronized(queueLock) {
                    queue.clear()
                    draining = false
                }
                downloadJob?.cancel()
                releaseWakeLock()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun enqueue(packId: String) {
        val startDrain = synchronized(queueLock) {
            if (packId !in queue) queue.addLast(packId)
            if (draining) {
                false
            } else {
                draining = true
                true
            }
        }
        if (!startDrain) return
        goForeground()
        acquireWakeLock()
        downloadJob = lifecycleScope.launch(Dispatchers.IO) { drainQueue() }
    }

    private suspend fun drainQueue() {
        try {
            while (true) {
                val packId = synchronized(queueLock) { queue.removeFirstOrNull() }
                if (packId == null) {
                    // Closing the race: enqueue() only starts a drain when none is
                    // running, but this one is still "running" here. A tap landing
                    // in that window used to be queued with nothing to drain it,
                    // and the download simply never started, silently. Publishing
                    // our exit under the same lock that enqueue() takes means the
                    // next tap either finds us still draining, or starts a drain.
                    val done = synchronized(queueLock) {
                        if (queue.isEmpty()) {
                            draining = false
                            true
                        } else {
                            false
                        }
                    }
                    if (done) break else continue
                }
                installPack(packId)
            }
        } finally {
            releaseWakeLock()
        }
        notifyFinished()
        stopSelf()
    }

    private suspend fun installPack(packId: String) {
        val pack = ModelCatalog.byId(packId) ?: return
        var attempt = 0
        var result: PackState = PackState.NotInstalled
        while (true) {
            result = app.packInstaller.install(pack) { state ->
                publish(packId, state)
                if (state is PackState.Downloading) {
                    updateNotification(pack.displayName, state.fraction.toFloat())
                }
            }
            val failure = (result as? PackState.Failed)?.error
            if (failure != InstallError.NETWORK || attempt >= MAX_NETWORK_RETRIES) break
            attempt++
            // Retry is cheap and correct here: the .part files already on disk are
            // resumed, so a blip half a gigabyte into a download costs a backoff
            // rather than the whole transfer.
            Log.w(TAG, "network failure installing $packId; retry $attempt of $MAX_NETWORK_RETRIES")
            delay(RETRY_BACKOFF_MS * attempt)
        }
        publish(packId, result)
        if (result == PackState.Installed) {
            try {
                app.modelStore.ensureExtracted(app.packInstaller, pack)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ensureExtracted has already cleared the installed marker, so the
                // UI will offer this pack for download again rather than claiming
                // it is ready.
                Log.e(TAG, "extraction failed for $packId", e)
                publish(packId, PackState.Failed(InstallError.IO))
            }
        }
    }

    // ------------------------------------------------------------- wake lock

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { Log.w(TAG, "wake lock release failed", it) }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun publish(packId: String, state: PackState) {
        _states.value = _states.value + (packId to state)
    }

    // ---------------------------------------------------------- notifications

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.download_notification_title), 0f),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.download_notification_title), 0f))
        }
    }

    private fun buildNotification(text: String, fraction: Float): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, OnboardingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(text)
            .setProgress(100, (fraction * 100).toInt(), fraction <= 0f)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun updateNotification(packName: String, fraction: Float) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(packName, fraction))
    }

    private fun notifyFinished() {
        val manager = getSystemService(NotificationManager::class.java)
        val allInstalled = ModelCatalog.packs
            .filter { it.required }
            .all { app.packInstaller.stateOf(it) == PackState.Installed }
        val text = if (allInstalled) {
            getString(R.string.download_complete)
        } else {
            getString(R.string.download_failed)
        }
        manager.notify(
            NOTIFICATION_ID + 1,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(text)
                .setAutoCancel(true)
                .build(),
        )
    }
}
