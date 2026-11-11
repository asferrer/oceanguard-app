package com.oceanguard.ai.data.contribution

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.service.DataContributionWorker
import com.oceanguard.ai.utils.CocoAnnotationSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Date
import java.util.concurrent.TimeUnit

/** Result of "Withdraw my contributions" in Settings. */
sealed interface WithdrawResult {
    /** Accepted: [pendingDeleted] were still in the buffer; the NAS deletes the rest on its next pass. */
    data class Done(val pendingDeleted: Int) : WithdrawResult
    data object Unavailable : WithdrawResult
    data class Failed(val status: ContributionSyncStatus) : WithdrawResult
}

/**
 * Queue of photos the user chose to contribute, and the scheduling of their upload.
 *
 * [client] is null when this build has no destination; then [isAvailable] is false, nothing
 * is ever enqueued and the UI does not offer the feature.
 */
class ContributionRepository(
    private val dao: ContributionQueueDao,
    private val prefs: ContributionPrefs,
    private val context: Context,
    private val client: ContributionApiClient?,
    private val serializer: (Context, DetectionSession) -> String? = CocoAnnotationSerializer::build,
) {
    val isAvailable: Boolean get() = client != null

    /**
     * Called at start-up. Drops what can never be sent legitimately: consent given to an
     * older wording (v1.1.1 promised no GPS) and anything queued in a build with no destination.
     */
    suspend fun reconcile() {
        if (prefs.hasStaleConsent()) {
            prefs.setConsent(false)
            dao.deletePending()
        }
        if (!isAvailable) {
            if (prefs.consentGiven()) prefs.setConsent(false)
            dao.deletePending()
            prefs.recordSync(ContributionSyncStatus.NOT_CONFIGURED)
        }
    }

    /**
     * Called by InferenceService after saving a session. True if enqueued (consent given and a
     * destination exists); false if the UI may offer the prompt.
     */
    suspend fun maybeEnqueue(session: DetectionSession): Boolean {
        if (!isAvailable || !prefs.consentGiven()) return false
        return enqueueSession(session)
    }

    /**
     * Direct enqueue after the user accepted the prompt or tapped upload on a photo.
     * Duplicate-safe. False if there is no destination or the annotations cannot be built.
     */
    suspend fun enqueueSession(session: DetectionSession): Boolean {
        if (!isAvailable) return false
        val existing = dao.getStatusForUri(session.imageUri)
        if (existing == ContributionStatus.PENDING.name || existing == ContributionStatus.DONE.name) return true
        val json = serializer(context, session) ?: return false
        return insertAndSchedule(
            ContributionQueueItem(
                sessionId = session.id,
                imageUri = session.imageUri,
                annotationsJson = json,
                outputsJson = ContributionOutputs.forDetection(session),
                status = ContributionStatus.PENDING,
            ),
        )
    }

    /**
     * Called after a species identification is saved: queued if consent is given and a
     * destination exists. False if the UI may offer the upload button.
     */
    suspend fun maybeEnqueueSpecies(imageUri: String, items: List<SpeciesOutput>, timestamp: Date = Date()): Boolean {
        if (!isAvailable || !prefs.consentGiven()) return false
        return enqueueSpecies(imageUri, items, timestamp)
    }

    /**
     * Direct enqueue of a species photo (button on the history card). Duplicate-safe. The COCO
     * `annotations` are empty: the answer travels in `outputs.species`.
     */
    suspend fun enqueueSpecies(imageUri: String, items: List<SpeciesOutput>, timestamp: Date = Date()): Boolean {
        if (!isAvailable) return false
        val existing = dao.getStatusForUri(imageUri)
        if (existing == ContributionStatus.PENDING.name || existing == ContributionStatus.DONE.name) return true
        val json = CocoAnnotationSerializer.build(context, imageUri, timestamp, emptyList()) ?: return false
        return insertAndSchedule(
            ContributionQueueItem(
                sessionId = 0,
                imageUri = imageUri,
                annotationsJson = json,
                outputsJson = ContributionOutputs.forSpecies(items),
                status = ContributionStatus.PENDING,
            ),
        )
    }

    private suspend fun insertAndSchedule(item: ContributionQueueItem): Boolean {
        dao.insert(item)
        scheduleWorker()
        return true
    }

    /** Clear all pending items (called when user revokes contribution consent). */
    suspend fun clearPendingQueue() {
        dao.deletePending()
    }

    /** Clear entire queue (called by reset all data). */
    suspend fun clearAll() {
        dao.deleteAll()
    }

    /** Failed items get a fresh attempt budget and an upload is scheduled. */
    suspend fun retryFailed(): Int {
        val count = dao.resetFailed()
        if (count > 0) scheduleWorker()
        return count
    }

    /**
     * Deletes every photo this install contributed, on the server and in the local queue,
     * withdraws consent and starts a new anonymous id.
     */
    suspend fun withdrawAll(): WithdrawResult {
        val api = client ?: return WithdrawResult.Unavailable
        return when (val outcome = api.withdraw()) {
            is ApiOutcome.Withdrawn -> {
                prefs.setConsent(false)
                dao.deleteAll()
                api.resetIdentity()
                prefs.rememberClientId(null)
                WithdrawResult.Done(outcome.pendingDeleted)
            }
            is ApiOutcome.Failed -> WithdrawResult.Failed(outcome.status)
            else -> WithdrawResult.Failed(ContributionSyncStatus.SERVER)
        }
    }

    fun getPendingCountFlow(): Flow<Int> = dao.getPendingCountFlow()
    fun getDoneCountFlow(): Flow<Int> = dao.getDoneCountFlow()
    fun getFailedCountFlow(): Flow<Int> = dao.getFailedCountFlow()

    fun getStatusFlowForUri(uri: String): Flow<String?> = dao.getStatusFlowForUri(uri)

    /** Progress of the upload pass running now, null when none is running. */
    fun uploadProgressFlow(): Flow<UploadProgress?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
            infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.let {
                UploadProgress(
                    done = it.progress.getInt(DataContributionWorker.PROGRESS_DONE, 0),
                    total = it.progress.getInt(DataContributionWorker.PROGRESS_TOTAL, 0),
                )
            }
        }

    /**
     * Upload now on any network. Replaces a pass waiting for Wi-Fi: appending would chain it
     * behind that one and it would wait for Wi-Fi too.
     */
    fun scheduleImmediateUpload() {
        if (!isAvailable) return
        enqueue(request(NetworkType.CONNECTED), ExistingWorkPolicy.REPLACE)
    }

    private suspend fun scheduleWorker() {
        val networkType = if (prefs.wifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED
        enqueue(request(networkType), ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun request(networkType: NetworkType): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DataContributionWorker>()
            .setConstraints(Constraints(requiredNetworkType = networkType))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(WORK_TAG)
            .build()

    private fun enqueue(request: OneTimeWorkRequest, policy: ExistingWorkPolicy) {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    companion object {
        const val WORK_NAME = "data_contribution"
        const val WORK_TAG = "oceanguard_contribution"
        private const val BACKOFF_MINUTES = 5L
    }
}
