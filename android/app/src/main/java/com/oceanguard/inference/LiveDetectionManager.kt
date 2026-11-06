package com.oceanguard.ai.inference

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.oceanguard.ai.data.AnalysisModels
import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DebrisDetection
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionRepository
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.utils.BitmapAnnotator
import com.oceanguard.ai.utils.LocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Manages continuous object detection on camera frames for live detection mode.
 *
 * Key design choices:
 * - Single inference at a time via Mutex (prevents overlap)
 * - Frame dropping: if inference is in progress, new frames are discarded
 * - Cadence measured between completed passes ([LiveCadenceMeter])
 * - Auto-saves frames with detections to history (throttled)
 */
class LiveDetectionManager(
    private val detector: ObjectDetector,
    private val context: Context? = null,
    private val repository: DetectionRepository? = null,
    private val locationProvider: LocationProvider? = null,
    var confidenceThreshold: Float = 0.6f,
) {
    companion object {
        private const val TAG = "LiveDetectionManager"
        private const val SAVE_INTERVAL_MS = 10_000L

        /**
         * Shared by every instance: the detector's interpreter is also used by single-photo
         * analysis, is not thread-safe, and a pass cannot be cancelled once it has started.
         */
        private val inferenceMutex = Mutex()

        /** Suspends until no pass is running. Call it before analysing a photo with RT-DETRv2. */
        suspend fun awaitIdle() = inferenceMutex.withLock { }
    }

    data class LiveDetectionState(
        val detections: List<DetectionResult> = emptyList(),
        val inferenceTimeMs: Long = 0,
        val frameCount: Long = 0,
        val isProcessing: Boolean = false,
        val inputResolution: Int = 480,
        /** Mean time between completed passes; null until two of them have finished. */
        val cadenceMs: Long? = null,
        val savedCount: Int = 0,
        /** Size of the frame the boxes refer to; 0 until the first pass has finished. */
        val frameWidth: Int = 0,
        val frameHeight: Int = 0,
    )

    private val _state = MutableStateFlow(LiveDetectionState())
    val state: StateFlow<LiveDetectionState> = _state.asStateFlow()

    @Volatile
    private var isActive = false

    /** True while a pass runs: lets the caller skip converting a frame that would be dropped. */
    val isBusy: Boolean get() = inferenceMutex.isLocked

    private val cadenceMeter = LiveCadenceMeter()

    // Auto-save throttle
    private var lastSaveTimestamp = 0L
    private var savedCount = 0

    /**
     * Process a single camera frame.
     * Called from ImageAnalysis.Analyzer on the analysis executor.
     * Returns immediately if an inference is already in progress (frame dropping).
     * The bitmap is always resized to INPUT_SIZE (640) for the TFLite model.
     */
    suspend fun processFrame(bitmap: Bitmap) {
        if (!isActive) return
        if (inferenceMutex.isLocked) return // Drop frame — backpressure

        inferenceMutex.withLock {
            if (!isActive) return

            _state.value = _state.value.copy(isProcessing = true)

            try {
                runPass(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing failed", e)
                _state.value = _state.value.copy(isProcessing = false)
            }
        }
    }

    /** Runs inference directly (no orchestrator, no VLM) and publishes the result. */
    private suspend fun runPass(bitmap: Bitmap) {
        val startTime = System.currentTimeMillis()

        // The model has a fixed input size.
        val targetSize = detector.inputSize
        val resized = if (bitmap.width != targetSize || bitmap.height != targetSize) {
            Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        } else {
            bitmap
        }
        val detections = detector.detect(resized, confidenceThreshold)
        if (resized !== bitmap) {
            resized.recycle()
        }

        val inferenceTime = System.currentTimeMillis() - startTime
        if (detections.isNotEmpty() && shouldSave()) {
            saveFrameToHistory(bitmap, detections, inferenceTime)
        }

        _state.value = LiveDetectionState(
            detections = detections,
            inferenceTimeMs = inferenceTime,
            frameCount = _state.value.frameCount + 1,
            isProcessing = false,
            inputResolution = targetSize,
            cadenceMs = cadenceMeter.record(SystemClock.elapsedRealtime()),
            savedCount = savedCount,
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
        )
    }

    fun start() {
        isActive = true
        cadenceMeter.reset()
        savedCount = 0
        lastSaveTimestamp = 0L
        _state.value = LiveDetectionState()
    }

    fun stop() {
        isActive = false
        _state.value = LiveDetectionState()
    }

    // ---------------------------------------------------------------------
    // Auto-save logic
    // ---------------------------------------------------------------------

    private fun shouldSave(): Boolean {
        if (context == null || repository == null) return false
        return System.currentTimeMillis() - lastSaveTimestamp >= SAVE_INTERVAL_MS
    }

    private suspend fun saveFrameToHistory(
        originalBitmap: Bitmap,
        detections: List<DetectionResult>,
        inferenceTimeMs: Long,
    ) {
        val ctx = context ?: return
        val repo = repository ?: return

        withContext(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                    .format(System.currentTimeMillis())
                val photoFile = File(ctx.getExternalFilesDir(null), "OceanGuard_Live_$timestamp.jpg")
                FileOutputStream(photoFile).use { out ->
                    originalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                val debrisList = detections.map(::toDebris)
                val session = DetectionSession(
                    imageUri = Uri.fromFile(photoFile).toString(),
                    thumbnailUri = saveAnnotatedThumbnail(ctx, originalBitmap, detections, timestamp),
                    debrisList = debrisList,
                    totalCount = debrisList.size,
                    healthScore = DebrisDetection(
                        debrisList, debrisList.size, ImageQuality.FAIR, inferenceTimeMs,
                    ).calculateHealthScore(),
                    location = locationProvider?.getLastKnownLocation(),
                    imageQuality = ImageQuality.FAIR,
                    processingTimeMs = inferenceTimeMs,
                    tags = "live_detection",
                    modelName = AnalysisModels.RT_DETR_V2,
                )
                repo.saveSession(session, skipAchievements = true)

                savedCount++
                lastSaveTimestamp = System.currentTimeMillis()
                Log.i(TAG, "Auto-saved live frame #$savedCount (${debrisList.size} objects)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save live detection frame", e)
            }
        }
    }

    private fun saveAnnotatedThumbnail(
        ctx: Context,
        frame: Bitmap,
        detections: List<DetectionResult>,
        timestamp: String,
    ): String {
        val annotated = BitmapAnnotator.annotate(frame, detections)
        val thumbDir = File(ctx.filesDir, "annotated").also { it.mkdirs() }
        val thumbFile = File(thumbDir, "annotated_live_$timestamp.jpg")
        FileOutputStream(thumbFile).use { out ->
            annotated.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        annotated.recycle()
        return Uri.fromFile(thumbFile).toString()
    }

    /** Normalised box, like every other session: the COCO export depends on it. */
    private fun toDebris(detection: DetectionResult) = Debris(
        bbox = BoundingBox(detection.x1, detection.y1, detection.width, detection.height),
        material = classNameToMaterial(detection.className),
        type = classNameToType(detection.className),
        confidence = detection.confidence,
    )

    private fun classNameToType(className: String): DebrisType = when (className.lowercase()) {
        "bottle" -> DebrisType.BOTTLE
        "can" -> DebrisType.CAN
        "fishing_net" -> DebrisType.FISHING_NET
        "glove" -> DebrisType.GLOVE
        "mask" -> DebrisType.MASK
        "metal_debris" -> DebrisType.METAL_DEBRIS
        "plastic_debris" -> DebrisType.PLASTIC_DEBRIS
        "tire" -> DebrisType.TIRE
        else -> DebrisType.OTHER
    }

    private fun classNameToMaterial(className: String): DebrisMaterial = when (className.uppercase()) {
        "BOTTLE", "PLASTIC_DEBRIS", "BOTTLE_CAP", "PLASTIC_BAG", "FOOD_WRAPPER",
        "STYROFOAM", "PLASTIC_CUP", "STRAW", "PLASTIC_UTENSIL", "SIX_PACK_RING",
        "PLASTIC_SHEETING", "DIAPER", "MASK", "CIGARETTE_BUTT",
        "CIGARETTE_LIGHTER" -> DebrisMaterial.PLASTIC
        "CAN", "METAL_DEBRIS", "AEROSOL_CAN", "METAL_DRUM", "WIRE_CABLE",
        "BATTERY", "ELECTRONICS" -> DebrisMaterial.METAL
        "FISHING_NET", "FISHING_LINE", "ROPE", "FISHING_BUOY",
        "FISHING_TRAP" -> DebrisMaterial.FISHING_NET
        "GLOVE", "FABRIC_DEBRIS", "CLOTHING", "SHOE" -> DebrisMaterial.FABRIC
        "TIRE", "FLIP_FLOP", "RUBBER_HOSE" -> DebrisMaterial.RUBBER
        "GLASS_DEBRIS", "GLASS_BOTTLE", "GLASS_JAR", "GLASS_FRAGMENT",
        "LIGHT_BULB" -> DebrisMaterial.GLASS
        "CARDBOARD", "PAPER" -> DebrisMaterial.PAPER
        "WOOD_PALLET", "LUMBER" -> DebrisMaterial.WOOD
        "CERAMIC_FRAGMENT", "BRICK" -> DebrisMaterial.CERAMIC
        "PAINT_CAN", "OIL_CONTAINER", "SYRINGE", "CHEMICAL_DRUM" -> DebrisMaterial.CHEMICAL
        else -> DebrisMaterial.OTHER
    }
}
