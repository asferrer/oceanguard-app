package com.oceanguard.ai.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.oceanguard.ai.R
import com.oceanguard.ai.data.contribution.ContributionUploadRunner
import com.oceanguard.ai.data.contribution.RunOutcome
import com.oceanguard.ai.data.contribution.RunReport
import com.oceanguard.ai.ui.MainActivity
import com.oceanguard.ai.ui.navigation.OceanRoutes

/** Implemented by the Application: gives the worker its runner and receives the cycle result. */
interface ContributionHost {
    fun contributionUploadRunner(): ContributionUploadRunner
    suspend fun onContributionCycleFinished(report: RunReport)
}

/**
 * WorkManager worker that sends the contribution queue to the gateway. The logic lives in
 * [ContributionUploadRunner]; this class adds the progress notification and maps the outcome:
 *
 * - DONE: success.
 * - RETRY: retry with the exponential backoff set at enqueue time (5 min, doubling). Each
 *   retry consumes the attempt budget of the photo that failed, so the queue always ends.
 * - NOT_CONFIGURED: failure, no retry (Settings shows why).
 */
class DataContributionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val host = applicationContext as? ContributionHost ?: return Result.failure()
        val report = host.contributionUploadRunner().run { done, total -> showProgress(done, total) }
        host.onContributionCycleFinished(report)
        postSummary(report)
        return when (report.outcome) {
            RunOutcome.DONE -> Result.success()
            RunOutcome.RETRY -> Result.retry()
            RunOutcome.NOT_CONFIGURED -> Result.failure()
        }
    }

    /** Best effort: Android may refuse a foreground service from the background (API 31+). */
    private suspend fun showProgress(done: Int, total: Int) {
        try {
            ensureNotificationChannel()
            setForeground(buildForegroundInfo(done, total))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Progress notification not shown: ${e.javaClass.simpleName}")
        }
    }

    /** One quiet notification, always the same id and channel, updated in place on each photo. */
    private fun buildForegroundInfo(current: Int, total: Int): ForegroundInfo {
        val position = ContributionNotice.progressPosition(current, total)
        val text = applicationContext.resources.getQuantityString(
            R.plurals.contribute_upload_progress, total, position, total,
        )
        val notification = baseBuilder()
            .setContentTitle(applicationContext.getString(R.string.contribute_upload_title))
            .setContentText(text)
            .setProgress(total, current, false)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /**
     * Closes the progress notification (WorkManager drops it when the worker ends) and leaves a
     * short silent summary that opens Settings. Nothing is posted when nothing was sent or is
     * waiting, or when the user denied POST_NOTIFICATIONS.
     */
    private fun postSummary(report: RunReport) {
        val summary = ContributionNotice.summaryOf(report) ?: return
        val nm = NotificationManagerCompat.from(applicationContext)
        nm.cancel(NOTIFICATION_ID)
        if (!nm.areNotificationsEnabled()) return
        try {
            ensureNotificationChannel()
            val notification = baseBuilder()
                .setContentTitle(applicationContext.getString(R.string.contribute_upload_title))
                .setContentText(summaryText(summary))
                .setContentIntent(settingsIntent())
                .setAutoCancel(true)
                .build()
            nm.notify(SUMMARY_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Summary notification not shown: ${e.javaClass.simpleName}")
        }
    }

    private fun summaryText(summary: ContributionSummary): String {
        val res = applicationContext.resources
        return when (summary) {
            is ContributionSummary.Done ->
                res.getQuantityString(R.plurals.contribute_summary_done, summary.uploaded, summary.uploaded)
            is ContributionSummary.Pending -> res.getString(
                R.string.contribute_summary_pending,
                res.getQuantityString(R.plurals.contribute_count_done, summary.uploaded, summary.uploaded),
                res.getQuantityString(R.plurals.contribute_count_pending, summary.pending, summary.pending),
            )
            is ContributionSummary.Rejected -> res.getString(
                R.string.contribute_summary_rejected,
                res.getQuantityString(R.plurals.contribute_count_done, summary.uploaded, summary.uploaded),
                res.getQuantityString(R.plurals.contribute_count_rejected, summary.rejected, summary.rejected),
            )
        }
    }

    private fun settingsIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(InferenceService.EXTRA_DEEP_LINK_ROUTE, OceanRoutes.SETTINGS)
        }
        return PendingIntent.getActivity(
            applicationContext, SUMMARY_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun baseBuilder() = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setGroup(GROUP_KEY)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOnlyAlertOnce(true)
        .setSilent(true)

    /** Own low-importance channel: no sound, no vibration, no badge. Replaces the old shared one. */
    private fun ensureNotificationChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.contribute_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = applicationContext.getString(R.string.contribute_channel_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
        nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }

    private companion object {
        const val TAG = "ContribWorker"
        const val CHANNEL_ID = "oceanguard_contribution_upload"
        const val LEGACY_CHANNEL_ID = "oceanguard_contribution"
        const val GROUP_KEY = "oceanguard_contribution_group"
        const val NOTIFICATION_ID = 9001
        const val SUMMARY_ID = 9002
    }
}

/** Result emitted after a contribution upload cycle. Consumed once by the UI. */
data class ContributionUploadResult(val uploadedCount: Int, val hadFailures: Boolean)
