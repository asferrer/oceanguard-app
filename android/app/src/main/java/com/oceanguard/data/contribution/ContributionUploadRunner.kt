package com.oceanguard.ai.data.contribution

import android.util.Log
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException

/** How an upload cycle ended, mapped by the worker to a WorkManager result. */
enum class RunOutcome { DONE, RETRY, NOT_CONFIGURED }

data class RunReport(val uploaded: Int, val failed: Int, val outcome: RunOutcome)

/**
 * One pass over the contribution queue, one photo at a time (no OOM on long queues).
 *
 * - No destination ([client] null): records NOT_CONFIGURED and stops. Nothing is left waiting
 *   in silence: Settings shows the state and the queue is emptied at start-up (see
 *   [ContributionRepository.reconcile]).
 * - Accepted (201 or duplicate 200): DONE.
 * - Refused for content (413/415/422), photo missing or location impossible to remove: FAILED.
 * - Anything else: the item keeps PENDING, its attempt counter grows (except on 429) and the
 *   pass stops so WorkManager retries with backoff. After [MAX_RETRIES] attempts the item
 *   becomes FAILED, so a broken server or a revoked key cannot keep items PENDING forever.
 */
class ContributionUploadRunner(
    private val dao: ContributionQueueDao,
    private val client: ContributionApiClient?,
    private val prefs: ContributionPrefs,
    private val appVersion: String,
    private val prepare: (photo: File, includeLocation: Boolean) -> ByteArray,
    private val pauseBetweenMs: Long = 300,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): RunReport {
        if (client == null) {
            prefs.recordSync(ContributionSyncStatus.NOT_CONFIGURED)
            return RunReport(0, 0, RunOutcome.NOT_CONFIGURED)
        }
        dao.failExceededRetries(MAX_RETRIES)
        dao.purgeOlderThan(nowMs() - PURGE_AGE_MS)
        val total = dao.getPendingCount()
        var uploaded = 0
        var failed = 0
        while (true) {
            val item = dao.getNextPending() ?: break
            onProgress(uploaded + failed, total)
            when (val step = uploadOne(client, item)) {
                Step.Uploaded -> uploaded++
                Step.Failed -> failed++
                Step.Stop -> return RunReport(uploaded, failed, RunOutcome.RETRY)
            }
        }
        return RunReport(uploaded, failed, RunOutcome.DONE)
    }

    private sealed interface Step {
        data object Uploaded : Step
        data object Failed : Step
        data object Stop : Step
    }

    private suspend fun uploadOne(client: ContributionApiClient, item: ContributionQueueItem): Step {
        val photo = File(item.imageUri.removePrefix("file://"))
        val bytes = try {
            prepare(photo, prefs.includeLocation())
        } catch (e: IOException) {
            Log.w(TAG, "Photo ${item.id} not sendable: ${e.message}")
            dao.updateStatus(item.id, ContributionStatus.FAILED.name)
            return Step.Failed
        }
        val outcome = client.upload(
            image = bytes,
            fileName = photo.name,
            annotationsJson = item.annotationsJson,
            clientId = prefs.clientId(),
            appVersion = appVersion,
            // The capture time travels in the EXIF and in the COCO date_captured.
            capturedAt = null,
        )
        return when (outcome) {
            is ApiOutcome.Stored -> {
                dao.updateStatus(item.id, ContributionStatus.DONE.name)
                prefs.recordSync(ContributionSyncStatus.OK)
                if (pauseBetweenMs > 0) delay(pauseBetweenMs)
                Step.Uploaded
            }
            is ApiOutcome.Rejected -> {
                dao.updateStatus(item.id, ContributionStatus.FAILED.name)
                prefs.recordSync(ContributionSyncStatus.REJECTED)
                Step.Failed
            }
            is ApiOutcome.Failed -> {
                if (outcome.status != ContributionSyncStatus.RATE_LIMITED) dao.incrementRetry(item.id)
                prefs.recordSync(outcome.status)
                Log.w(TAG, "Upload of ${item.id} failed: ${outcome.status} (${outcome.httpCode})")
                Step.Stop
            }
            is ApiOutcome.Withdrawn -> Step.Stop // not an upload answer
        }
    }

    companion object {
        private const val TAG = "ContribRunner"
        const val MAX_RETRIES = 5
        private const val PURGE_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
