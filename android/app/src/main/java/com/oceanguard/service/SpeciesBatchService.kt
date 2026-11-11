package com.oceanguard.ai.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.screens.SpeciesBatchItemState
import com.oceanguard.ai.ui.screens.SpeciesBatchUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a species batch alive while the app is in the background and shows its progress in the notification shade.
 * The batch itself runs in the app's [com.oceanguard.ai.ui.screens.SpeciesBatchHost]; this service only mirrors its
 * state, holds a partial wake lock (screen off) and stops when the batch ends, leaving a "finished" notification.
 */
class SpeciesBatchService : LifecycleService() {

    companion object {
        private const val TAG = "SpeciesBatchService"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_CANCEL = "com.oceanguard.ai.CANCEL_SPECIES_BATCH"
        const val ROUTE = "species_batch_result"
        private const val WAKE_LOCK_MS = 60L * 60 * 1000

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, SpeciesBatchService::class.java))
            } catch (e: IllegalStateException) {
                // Only allowed from the foreground; the batch still runs while the app is open.
                Log.w(TAG, "Foreground service not started: ${e.message}")
            }
        }
    }

    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val app = application as OceanGuardApp
        if (intent?.action == ACTION_CANCEL) {
            app.speciesBatchHost.cancel()
            return START_NOT_STICKY
        }
        BatchNotifications.ensureChannel(this)
        BatchNotifications.clearFinished(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, progressNotification(app.speciesBatchHost.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OceanGuard:SpeciesBatch")
                .also { it.acquire(WAKE_LOCK_MS) }
        }
        if (watcher == null) watcher = lifecycleScope.launch { follow(app) }
        return START_NOT_STICKY
    }

    private suspend fun follow(app: OceanGuardApp) {
        app.speciesBatchHost.state.collectLatest { state ->
            when (state) {
                is SpeciesBatchUiState.Running -> notify(progressNotification(state))
                is SpeciesBatchUiState.Complete -> finish(state)
                is SpeciesBatchUiState.Error, SpeciesBatchUiState.Idle -> stop()
            }
        }
    }

    private fun progressNotification(state: SpeciesBatchUiState) = BatchNotifications.progress(
        context = this,
        title = getString(R.string.notif_batch_title_species),
        done = (state as? SpeciesBatchUiState.Running)?.items?.count { it.isFinished() } ?: 0,
        total = (state as? SpeciesBatchUiState.Running)?.items?.size ?: 0,
        startMs = (state as? SpeciesBatchUiState.Running)?.startTimeMs ?: System.currentTimeMillis(),
        open = BatchNotifications.openIntent(this, ROUTE, NOTIFICATION_ID),
        cancel = android.app.PendingIntent.getService(
            this, NOTIFICATION_ID, Intent(this, SpeciesBatchService::class.java).setAction(ACTION_CANCEL),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        ),
    )

    private fun notify(notification: android.app.Notification) {
        try {
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied: the batch goes on without a visible progress bar.
        }
    }

    private fun finish(state: SpeciesBatchUiState.Complete) {
        val ok = state.items.count { it.state is SpeciesBatchItemState.Done }
        BatchNotifications.postFinished(
            this, getString(R.string.notif_batch_done_title_species), ok, state.items.size,
            BatchNotifications.openIntent(this, ROUTE, NOTIFICATION_ID + 100),
        )
        stop()
    }

    private fun stop() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}

private fun com.oceanguard.ai.ui.screens.SpeciesBatchItem.isFinished(): Boolean =
    state is SpeciesBatchItemState.Done || state is SpeciesBatchItemState.Failed
