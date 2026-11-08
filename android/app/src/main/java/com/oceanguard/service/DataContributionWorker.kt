package com.oceanguard.ai.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.oceanguard.ai.R
import com.oceanguard.ai.data.contribution.ContributionUploadRunner
import com.oceanguard.ai.data.contribution.RunOutcome
import com.oceanguard.ai.data.contribution.RunReport

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

    private fun buildForegroundInfo(current: Int, total: Int): ForegroundInfo {
        val text = applicationContext.getString(
            R.string.contribute_upload_progress, (current + 1).coerceAtMost(total), total,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.contribute_upload_title))
            .setContentText(text)
            .setProgress(total, current, false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun ensureNotificationChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.contribute_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = applicationContext.getString(R.string.contribute_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private companion object {
        const val TAG = "ContribWorker"
        const val CHANNEL_ID = "oceanguard_contribution"
        const val NOTIFICATION_ID = 9001
    }
}

/** Result emitted after a contribution upload cycle. Consumed once by the UI. */
data class ContributionUploadResult(val uploadedCount: Int, val hadFailures: Boolean)
