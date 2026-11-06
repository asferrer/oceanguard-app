package com.oceanguard.ai.inference

import android.content.Context
import android.net.Uri
import android.os.Process
import android.util.Log
import com.oceanguard.ai.data.*
import com.oceanguard.ai.utils.ImagePreprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Orchestrates single-detector inference:
 *  - RT-DETRv2 (default) for detection; Gemma 4 then writes the report.
 *  - Optionally (setting) Gemma 4 refines sub-type/material of the RT-DETRv2 boxes ([refiner]).
 *  - Gemma 4 Vision as the detector itself for open-vocabulary deep analysis (slower; latency pending measurement).
 *
 * The active detector is chosen at app startup (and updated through
 * [OceanGuardApp.applyDetectorMode] when the user changes settings). Detector
 * outputs are canonicalized to one of the 11 [DebrisType.CANONICAL] entries
 * before they leave this class, guaranteeing the rest of the app — MarineDex,
 * achievements, reports — never deals with an extended type.
 *
 * Emits granular states via StateFlow for the progress indicator: LoadingImage
 * -> WarmingUpModel (only if the model is cold) -> Detecting or AnalyzingDeep
 * -> DetectionsReady -> Complete.
 */
class DetectionOrchestrator(
    private val context: Context,
    @Volatile var detector: ObjectDetector,
) {
    /**
     * Optional second stage (multi-model flow): when set and ready, and the main detector is
     * RT-DETRv2, Gemma 4 runs on the same image and [DetectionRefiner] copies sub-type/material
     * onto the RT-DETRv2 boxes. Set by [OceanGuardApp.applyDetectorMode]; null = RT-DETRv2 only.
     */
    @Volatile var refiner: ObjectDetector? = null

    companion object {
        private const val TAG = "DetectionOrchestrator"
    }

    private val imagePreprocessor = ImagePreprocessor(context)

    private val _analysisState = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val analysisState: StateFlow<AnalysisState> = _analysisState.asStateFlow()

    /**
     * Full single-detector analysis pipeline.
     *
     * @param imageUri URI of the image to analyze
     * @param skipVLM kept for source compatibility with existing call sites;
     *                ignored — there is no parallel VLM step any more.
     * @return Complete analysis result with canonicalized debris types.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun analyzeImage(
        imageUri: Uri,
        skipVLM: Boolean = false,
        confidenceThreshold: Float = 0.6f,
    ): AnalysisResult = withContext(Dispatchers.Default) {
        // Yield CPU priority to the UI thread while inference runs on this
        // coroutine. Without this, the worker has the same OS priority as the
        // main thread, so on a fully-loaded device (Gemma 4 + XNNPACK saturating
        // most of the 8 cores) the Compose animation can stutter or freeze.
        // THREAD_PRIORITY_LESS_FAVORABLE = nice +1, just enough to make the
        // Linux scheduler prefer the UI thread for frame work without harming
        // detector throughput noticeably.
        val tid = Process.myTid()
        val originalPriority = runCatching { Process.getThreadPriority(tid) }
            .getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
        runCatching { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_LESS_FAVORABLE) }

        val startTime = System.currentTimeMillis()
        val deepAnalysis = detector is Gemma4VisionDetector

        try {
            _analysisState.value = AnalysisState.LoadingImage

            val bitmap = imagePreprocessor.loadAndPreprocess(imageUri, detector.inputSize)
            Log.i(TAG, "Image loaded: ${bitmap.width}x${bitmap.height} for ${detector.displayName}")

            if (!detector.isReady()) {
                _analysisState.value = AnalysisState.WarmingUpModel
                Log.i(TAG, "${detector.displayName} not ready -- attempting cold initialize")
                try {
                    detector.initialize()
                    detector.warmUp()
                } catch (e: Exception) {
                    Log.w(TAG, "Detector initialize failed: ${e.message}", e)
                }
                if (!detector.isReady()) {
                    throw IllegalStateException("${detector.displayName} could not be loaded")
                }
            }

            _analysisState.value = if (deepAnalysis) {
                AnalysisState.AnalyzingDeep(emptyList())
            } else {
                AnalysisState.Detecting
            }

            var detections = detector.detect(bitmap, confidenceThreshold)
            detections = refineWithGemma(imageUri, detections, deepAnalysis)
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Detection: ${detections.size} objects in ${elapsed}ms")
            _analysisState.value = AnalysisState.DetectionsReady(detections)

            val totalTime = System.currentTimeMillis() - startTime
            val refined = detections.any { it.subType != null || it.rawLabel != null }
            val result = buildResult(detections, totalTime, deepAnalysis || refined)
            _analysisState.value = AnalysisState.Complete(result)
            Log.i(TAG, "Analysis complete in ${totalTime}ms (deep=$deepAnalysis)")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Analysis pipeline failed", e)
            _analysisState.value = AnalysisState.Error(e.message ?: "Unknown error")
            throw e
        } finally {
            // Restore the pool thread's priority so it doesn't keep a low nice
            // value when reused for unrelated coroutine work.
            runCatching { Process.setThreadPriority(tid, originalPriority) }
        }
    }

    /**
     * Second stage of the multi-model flow. Never fails the analysis: any error keeps the
     * RT-DETRv2 detections as they are.
     */
    private suspend fun refineWithGemma(
        imageUri: Uri,
        detections: List<DetectionResult>,
        deepAnalysis: Boolean,
    ): List<DetectionResult> {
        val second = refiner
        if (deepAnalysis || second == null || detections.isEmpty() || !second.isReady()) return detections
        return try {
            _analysisState.value = AnalysisState.AnalyzingDeep(detections)
            val bitmap = imagePreprocessor.loadAndPreprocess(imageUri, second.inputSize)
            val refined = DetectionRefiner.refine(detections, second.detect(bitmap, 0f))
            Log.i(TAG, "Gemma 4 refined ${refined.count { it.subType != null || it.rawLabel != null }}/${detections.size} boxes")
            refined
        } catch (e: Exception) {
            Log.w(TAG, "Gemma 4 refinement failed, keeping RT-DETRv2 output: ${e.message}", e)
            detections
        }
    }

    /**
     * Quick detection only (no state side-effects). Used by the live preview.
     */
    suspend fun detectOnly(
        imageUri: Uri,
        confidenceThreshold: Float = 0.6f,
    ): List<DetectionResult> = withContext(Dispatchers.Default) {
        val tid = Process.myTid()
        val originalPriority = runCatching { Process.getThreadPriority(tid) }
            .getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
        runCatching { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_LESS_FAVORABLE) }
        try {
            val bitmap = imagePreprocessor.loadAndPreprocess(imageUri, detector.inputSize)
            detector.detect(bitmap, confidenceThreshold)
        } finally {
            runCatching { Process.setThreadPriority(tid, originalPriority) }
        }
    }

    /**
     * Build the analysis result. Maps each detection to a canonical [DebrisType]
     * so MarineDex unlocks and report tables are coherent regardless of which
     * detector produced the output.
     */
    private fun buildResult(
        detections: List<DetectionResult>,
        processingTimeMs: Long,
        deepAnalysis: Boolean,
    ): AnalysisResult {
        val debrisDetection = buildDebrisDetection(detections)
        return AnalysisResult(
            rtdetrDetections = detections,
            vlmAnalysis = debrisDetection,
            healthScore = debrisDetection.calculateHealthScore(),
            totalDebrisCount = detections.size,
            processingTimeMs = processingTimeMs,
            hasVLMAnalysis = deepAnalysis,
        )
    }

    /**
     * Convert detector outputs into the persistence-friendly [DebrisDetection]
     * shape, collapsing each type to its canonical parent on the way out.
     */
    private fun buildDebrisDetection(detections: List<DetectionResult>): DebrisDetection {
        val debrisList = detections.map { det ->
            val canonType = DebrisType.fromString(det.className).canonical()
            val subType = det.subType?.let { DebrisType.fromString(it) }
            Debris(
                bbox = BoundingBox(det.x1, det.y1, det.width, det.height),
                material = det.material ?: classNameToMaterial(canonType.name),
                type = canonType,
                confidence = det.confidence,
                rawLabel = det.rawLabel,
                subType = subType?.takeIf { it != canonType },   // drop redundant
            )
        }
        return DebrisDetection(
            debrisList = debrisList,
            totalCount = debrisList.size,
            imageQuality = ImageQuality.FAIR,
        )
    }

    /**
     * Map a canonical class name to its [DebrisMaterial]. Operates on canonical
     * names only (BOTTLE, CAN, ...); extended types should never reach this
     * function but are tolerated through the `else -> OTHER` fallback.
     */
    private fun classNameToMaterial(className: String): DebrisMaterial =
        when (className.uppercase()) {
            "BOTTLE", "PLASTIC_DEBRIS", "MASK" -> DebrisMaterial.PLASTIC
            "CAN", "METAL_DEBRIS"              -> DebrisMaterial.METAL
            "FISHING_NET"                      -> DebrisMaterial.FISHING_NET
            "GLOVE", "FABRIC_DEBRIS"           -> DebrisMaterial.FABRIC
            "TIRE"                             -> DebrisMaterial.RUBBER
            "GLASS_DEBRIS"                     -> DebrisMaterial.GLASS
            else                                -> DebrisMaterial.OTHER
        }

    /**
     * Reset state to idle
     */
    fun reset() {
        _analysisState.value = AnalysisState.Idle
    }
}

/**
 * Analysis pipeline state for progressive UI updates.
 *
 * Order roughly: Idle -> LoadingImage -> WarmingUpModel? -> Detecting OR
 * AnalyzingDeep -> DetectionsReady -> Complete. AnalyzingDeep is emitted when
 * the active detector is Gemma 4 Vision (single deep pass); Detecting
 * is emitted for the fast RT-DETRv2 path.
 */
sealed class AnalysisState {
    data object Idle : AnalysisState()
    data object LoadingImage : AnalysisState()
    /** Emitted only when the detector is cold and needs an on-demand initialize. */
    data object WarmingUpModel : AnalysisState()
    data object Detecting : AnalysisState()
    data class DetectionsReady(val detections: List<DetectionResult>) : AnalysisState()
    data class AnalyzingDeep(val detections: List<DetectionResult>) : AnalysisState()
    data class Complete(val result: AnalysisResult) : AnalysisState()
    data class Error(val message: String) : AnalysisState()
}

/**
 * Complete analysis result combining RT-DETRv2 and VLM
 */
data class AnalysisResult(
    val rtdetrDetections: List<DetectionResult>,
    val vlmAnalysis: DebrisDetection,
    val healthScore: Int,
    val totalDebrisCount: Int,
    val processingTimeMs: Long,
    val hasVLMAnalysis: Boolean
)
