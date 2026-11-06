package com.oceanguard.ai.ui.screens.results

import android.net.Uri
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.oceanguard.ai.inference.AnalysisResult
import com.oceanguard.ai.service.BatchItemResult

/** Processing state of one photo of a batch: pending, analysing, then done or failed. */
internal sealed class BatchItemState {
    data object Pending : BatchItemState()

    data object Analyzing : BatchItemState()

    /** [sessionId] opens the saved detail; [annotatedUri] is the photo with the boxes drawn in. */
    data class Done(
        val result: AnalysisResult,
        val sessionId: Long = 0,
        val annotatedUri: String? = null,
    ) : BatchItemState()

    data class Failed(val message: String) : BatchItemState()
}

internal data class BatchItem(
    val uri: Uri,
    val state: BatchItemState = BatchItemState.Pending,
)

internal fun BatchItemResult.toState(): BatchItemState = when (this) {
    is BatchItemResult.Done -> BatchItemState.Done(result, sessionId, annotatedUri)
    is BatchItemResult.Failed -> BatchItemState.Failed(message)
}

/** Writes each finished [results] entry into the item that has the same photo. */
internal fun SnapshotStateList<BatchItem>.applyResults(uris: List<Uri>, results: List<BatchItemResult>) {
    for (item in results) {
        val index = uris.indexOfFirst { it.toString() == item.uri.toString() }
        if (index >= 0) this[index] = this[index].copy(state = item.toState())
    }
}

/** What the summary needs from one item; null for photos that failed or have not finished. */
internal fun BatchItem.toOutcome(): PhotoOutcome? {
    val done = state as? BatchItemState.Done ?: return null
    val result = done.result
    return PhotoOutcome(
        healthScore = result.healthScore,
        totalCount = result.totalDebrisCount,
        typeNames = result.vlmAnalysis.debrisList.map { it.type.name },
        modelName = result.modelName,
    )
}

internal fun List<BatchItem>.isFinished(): Boolean =
    isNotEmpty() && all { it.state is BatchItemState.Done || it.state is BatchItemState.Failed }
