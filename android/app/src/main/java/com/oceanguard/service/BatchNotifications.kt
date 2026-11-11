package com.oceanguard.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.MainActivity

/**
 * Notifications of a photo batch (debris or species) that keeps running with the app in the background: an ongoing
 * one with a progress bar, "Photo 3 of 12 · about 1 min left" and Cancel, and, when the batch ends, a quiet one that
 * opens the results. Both use the analysis channel of [InferenceService].
 */
object BatchNotifications {
    /** Id of the "batch finished" notification (the ongoing one uses the id of its service). */
    const val FINISHED_ID = 1003

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(InferenceService.CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(InferenceService.CHANNEL_ID, "OceanGuard Analysis", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Shows progress of marine debris analysis"
                    setShowBadge(false)
                },
        )
    }

    /** Opens the app on [route] (the batch results screen). */
    fun openIntent(context: Context, route: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(InferenceService.EXTRA_DEEP_LINK_ROUTE, route)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Ongoing progress: [done] of [total] photos finished, batch started at [startMs]. [title] says what is analysed.
     */
    fun progress(
        context: Context,
        title: String,
        done: Int,
        total: Int,
        startMs: Long,
        open: PendingIntent,
        cancel: PendingIntent,
    ): Notification {
        val current = BatchProgress.currentPhoto(done, total)
        val remaining = BatchProgress.remainingMs(done, total, System.currentTimeMillis() - startMs)
        val text = if (remaining == null) {
            context.getString(R.string.notif_batch_progress, current, total)
        } else {
            context.getString(R.string.notif_batch_progress_eta, current, total, BatchProgress.formatRemaining(remaining))
        }
        return NotificationCompat.Builder(context, InferenceService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(total.coerceAtLeast(1), done.coerceIn(0, total), total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.notif_batch_cancel),
                cancel,
            )
            .build()
            .apply { flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR }
    }

    /** Posts the "batch finished" notification ([ok] of [total] photos analysed); tapping it opens the results. */
    fun postFinished(context: Context, title: String, ok: Int, total: Int, open: PendingIntent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, InferenceService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_batch_done_text, ok, total))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(FINISHED_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied: the results are still in the app.
        }
    }

    /** Removes a previous "batch finished" notification when a new batch starts. */
    fun clearFinished(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(FINISHED_ID)
    }
}

/** Numbers of the batch progress notification, Android-free so they can be unit-tested. */
object BatchProgress {
    /** Photo being analysed (1-based) when [done] are finished; equals [total] at the end. */
    fun currentPhoto(done: Int, total: Int): Int = (done + 1).coerceIn(1, total.coerceAtLeast(1))

    /** Time left from the average time per finished photo; null before the first one or at the end. */
    fun remainingMs(done: Int, total: Int, elapsedMs: Long): Long? =
        if (done <= 0 || done >= total || elapsedMs <= 0) null else elapsedMs / done * (total - done)

    /** "45 s", "3 min" (rounded up; minutes from one minute on). */
    fun formatRemaining(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s < 60) "$s s" else "${(s + 59) / 60} min"
    }
}
