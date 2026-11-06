package com.oceanguard.ai.ui.screens.results

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.service.BatchItemResult
import com.oceanguard.ai.service.InferenceServiceState
import com.oceanguard.ai.ui.MainViewModel

/**
 * Display state of one batch, fed by the analysis service. The service owns the work and the
 * clock, so leaving the screen and coming back shows the same progress and the same total time.
 */
@Stable
internal class BatchRun(uris: List<Uri>) {
    val uris: List<Uri> = uris
    val items = mutableStateListOf(*uris.map { BatchItem(it) }.toTypedArray())
    var currentIndex by mutableIntStateOf(0)
    var running by mutableStateOf(false)
    var startMs by mutableLongStateOf(0L)
    var endMs by mutableLongStateOf(0L)

    val finished: Boolean get() = items.isFinished()
    val completed: Int get() = items.count { it.state is BatchItemState.Done || it.state is BatchItemState.Failed }

    /** Wall-clock the service measured; 0 until both ends are known. */
    val durationMs: Long get() = if (startMs > 0L && endMs > startMs) endMs - startMs else 0L

    fun resetToPending() {
        running = false
        for (i in items.indices) items[i] = items[i].copy(state = BatchItemState.Pending)
    }

    fun onRunning(state: InferenceServiceState.BatchRunning) {
        currentIndex = state.currentIndex
        running = true
        if (state.startTimeMs > 0L && startMs != state.startTimeMs) startMs = state.startTimeMs
        items.applyResults(uris, state.completedItems)
        val current = items.getOrNull(state.currentIndex)
        if (current != null && current.state is BatchItemState.Pending) {
            items[state.currentIndex] = current.copy(state = BatchItemState.Analyzing)
        }
    }

    fun onComplete(state: InferenceServiceState.BatchComplete) {
        running = false
        if (state.startTimeMs > 0L) startMs = state.startTimeMs
        if (state.endTimeMs > 0L) endMs = state.endTimeMs
        items.applyResults(uris, state.results)
    }
}

/**
 * Creates the [BatchRun] for [uris] and keeps it in step with the service. The batch is launched
 * at most once per list: the hash set lives in the view model so that coming back to this route,
 * or the service going idle after it finished, never starts the same batch again.
 */
@Composable
internal fun rememberBatchRun(uris: List<Uri>, viewModel: MainViewModel, app: OceanGuardApp): BatchRun {
    val run = remember(uris) { BatchRun(uris) }
    val serviceState by app.inferenceServiceState.collectAsStateWithLifecycle(InferenceServiceState.Idle)

    LaunchedEffect(uris) { launchOnce(uris, viewModel, app, run) }
    LaunchedEffect(serviceState) {
        when (val state = serviceState) {
            is InferenceServiceState.BatchRunning -> run.onRunning(state)
            is InferenceServiceState.BatchComplete -> {
                run.onComplete(state)
                val ids = state.results.filterIsInstance<BatchItemResult.Done>()
                    .mapNotNull { it.sessionId.takeIf { id -> id > 0 } }
                viewModel.onAnalysisComplete(ids, state.contributionQueuedCount > 0)
            }
            else -> Unit
        }
    }
    return run
}

private fun launchOnce(uris: List<Uri>, viewModel: MainViewModel, app: OceanGuardApp, run: BatchRun) {
    if (uris.isEmpty()) return
    val hash = uris.hashCode()
    if (viewModel.batchStartedHashes.value.contains(hash)) return
    when (val current = app.inferenceServiceState.value) {
        // Already running or finished for this list: remember it and let the sync below render it.
        is InferenceServiceState.BatchRunning -> viewModel.markBatchStarted(hash)
        is InferenceServiceState.BatchComplete ->
            if (current.allUris == uris) viewModel.markBatchStarted(hash)
        is InferenceServiceState.Idle -> {
            viewModel.markBatchStarted(hash)
            viewModel.startBatchInference(uris)
            run.running = true
        }
        // Another job is in flight: do not pre-empt it; the next visit re-evaluates.
        else -> Unit
    }
}
