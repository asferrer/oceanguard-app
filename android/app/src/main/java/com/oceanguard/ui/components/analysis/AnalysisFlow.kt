package com.oceanguard.ai.ui.components.analysis

import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.inference.DetectionResult
import com.oceanguard.ai.ui.UiState
import kotlin.math.PI
import kotlin.math.roundToInt

/** What the pipeline is really doing, in the terms the analysis screen tells the user. */
enum class AnalysisStatus { LOADING_IMAGE, WARMING_MODEL, DETECTING, GEMMA, REFINING, FINISHED }

/**
 * Reading of the pipeline state for the analysis screen. The sweep runs while [isRunning] is true
 * and no longer: there is no percentage to show, because neither detector reports any progress.
 */
object AnalysisFlow {

    fun isRunning(state: UiState): Boolean = when (state) {
        is UiState.ModelLoading,
        is UiState.Detecting,
        is UiState.DetectionsReady,
        is UiState.AnalyzingDeep,
        -> true
        is UiState.Idle,
        is UiState.AnalysisComplete,
        is UiState.Error,
        -> false
    }

    /** Boxes known so far. Gemma delivers them only at the end; a refining pass has them early. */
    fun detections(state: UiState): List<DetectionResult> = when (state) {
        is UiState.DetectionsReady -> state.detections
        is UiState.AnalyzingDeep -> state.detections
        is UiState.AnalysisComplete -> state.result.rtdetrDetections
        else -> emptyList()
    }

    /**
     * @param warmingModel True while the detector is being loaded from cold.
     * @param gemmaDetector True when the detector that will run is Gemma, not when it was chosen.
     */
    fun status(state: UiState, warmingModel: Boolean, gemmaDetector: Boolean): AnalysisStatus =
        when (state) {
            is UiState.ModelLoading ->
                if (warmingModel) AnalysisStatus.WARMING_MODEL else AnalysisStatus.LOADING_IMAGE
            is UiState.AnalyzingDeep ->
                if (state.detections.isEmpty()) AnalysisStatus.GEMMA else AnalysisStatus.REFINING
            is UiState.Detecting,
            is UiState.DetectionsReady,
            -> if (gemmaDetector) AnalysisStatus.GEMMA else AnalysisStatus.DETECTING
            is UiState.Idle,
            is UiState.AnalysisComplete,
            is UiState.Error,
            -> AnalysisStatus.FINISHED
        }

    /** One pass over the photo is half a turn of the mode's radar: slower models sweep slower. */
    fun sweepPeriodMs(mode: AnalysisMode): Int =
        (PI / mode.radarRadPerSec * MILLIS_PER_SECOND).roundToInt()

    private const val MILLIS_PER_SECOND = 1_000
}
