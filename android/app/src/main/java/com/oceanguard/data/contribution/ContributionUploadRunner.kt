package com.oceanguard.ai.data.contribution

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.util.UUID

/** How an upload cycle ended, mapped by the worker to a WorkManager result. */
enum class RunOutcome { DONE, RETRY, NOT_CONFIGURED }

/** [pending] = photos still waiting for a retry when the cycle ended. */
data class RunReport(val uploaded: Int, val failed: Int, val outcome: RunOutcome, val pending: Int = 0)

/**
 * One pass over the contribution queue, one photo at a time (no OOM on long queues).
 *
 * - No destination ([client] null): records NOT_CONFIGURED and stops. Nothing is left waiting
 *   in silence: Settings shows the state and the queue is emptied at start-up (see
 *   [ContributionRepository.reconcile]).
 * - Accepted (201 or duplicate 200): DONE.
 * - Refused for content (413/415/422), photo missing or location impossible to remove: FAILED.
 * - Each photo is sealed for the NAS ([ContributionEnvelope]) before it leaves the phone.
 * - Anything else: the item keeps PENDING, its attempt counter grows (except on 429/quota) and the
 *   pass stops so WorkManager retries with backoff. After [MAX_RETRIES] attempts the item
 *   becomes FAILED, so a broken server or a revoked key cannot keep items PENDING forever.
 */
class ContributionUploadRunner(
    private val dao: ContributionQueueDao,
    private val client: ContributionApiClient?,
    private val prefs: ContributionPrefs,
    private val appVersion: String,
    private val prepare: (photo: File, includeLocation: Boolean) -> ByteArray,
    private val seal: (contributionId: String, metaJson: String, image: ByteArray) -> ByteArray,
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
                Step.Stop -> return RunReport(uploaded, failed, RunOutcome.RETRY, dao.getPendingCount())
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
        val contributionId = contributionIdOf(client.clientId, item)
        val envelope = try {
            seal(contributionId, metaJson(contributionId, item.annotationsJson), bytes)
        } catch (e: RuntimeException) { // bad annotations JSON or recipient key: never sent
            Log.w(TAG, "Photo ${item.id} not sealable: ${e.javaClass.simpleName}")
            dao.updateStatus(item.id, ContributionStatus.FAILED.name)
            return Step.Failed
        }
        val outcome = client.upload(envelope, contributionId)
        return when (outcome) {
            is ApiOutcome.Stored -> {
                dao.updateStatus(item.id, ContributionStatus.DONE.name)
                prefs.recordSync(ContributionSyncStatus.OK)
                prefs.rememberClientId(client.clientId)
                if (pauseBetweenMs > 0) delay(pauseBetweenMs)
                Step.Uploaded
            }
            is ApiOutcome.Rejected -> {
                dao.updateStatus(item.id, ContributionStatus.FAILED.name)
                prefs.recordSync(ContributionSyncStatus.REJECTED)
                Step.Failed
            }
            is ApiOutcome.Failed -> {
                val waitOnly = outcome.status == ContributionSyncStatus.RATE_LIMITED ||
                    outcome.status == ContributionSyncStatus.QUOTA
                if (!waitOnly) dao.incrementRetry(item.id)
                prefs.recordSync(outcome.status)
                Log.w(TAG, "Upload of ${item.id} failed: ${outcome.status} (${outcome.httpCode})")
                Step.Stop
            }
            is ApiOutcome.Withdrawn -> Step.Stop // not an upload answer
        }
    }

    /** Envelope metadata: what the NAS needs besides the photo (no location, no device data). */
    private fun metaJson(contributionId: String, annotationsJson: String): String = JsonObject().apply {
        addProperty("v", 1)
        addProperty("contribution_id", contributionId)
        addProperty("app_version", appVersion)
        add("annotations", JsonParser.parseString(annotationsJson))
    }.toString()

    companion object {
        private const val TAG = "ContribRunner"

        /**
         * Stable per queue item and install, so a retry after a lost answer is recognised as a
         * duplicate (the envelope itself differs on every attempt: fresh ephemeral key).
         */
        fun contributionIdOf(clientId: String, item: ContributionQueueItem): String =
            UUID.nameUUIDFromBytes("$clientId:${item.id}:${item.createdAt}".toByteArray()).toString()
        const val MAX_RETRIES = 5
        private const val PURGE_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
