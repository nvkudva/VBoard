package com.vboard.app.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.vboard.app.R
import com.vboard.app.VBoardApp
import com.vboard.app.onboarding.OnboardingActivity
import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.PackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that downloads and installs model packs with a progress
 * notification. UI observes [states] for in-app progress.
 */
class ModelDownloadService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 41
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
    private val queue = ArrayDeque<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                val packId = intent.getStringExtra(EXTRA_PACK_ID) ?: return START_NOT_STICKY
                enqueue(packId)
            }
            ACTION_CANCEL -> {
                downloadJob?.cancel()
                queue.clear()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun enqueue(packId: String) {
        if (packId !in queue) queue.addLast(packId)
        if (downloadJob?.isActive != true) {
            goForeground()
            downloadJob = lifecycleScope.launch(Dispatchers.IO) { drainQueue() }
        }
    }

    private suspend fun drainQueue() {
        while (true) {
            val packId = queue.removeFirstOrNull() ?: break
            val pack = ModelCatalog.byId(packId) ?: continue
            val result = app.packInstaller.install(pack) { state ->
                publish(packId, state)
                if (state is PackState.Downloading) {
                    updateNotification(pack.displayName, state.fraction)
                }
            }
            publish(packId, result)
            if (result == PackState.Installed) {
                runCatching { app.modelStore.ensureExtracted(app.packInstaller, pack) }
                    .onFailure { publish(packId, PackState.Failed(com.vboard.core.model.InstallError.IO)) }
            }
        }
        notifyFinished()
        stopSelf()
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
