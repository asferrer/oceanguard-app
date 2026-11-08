package com.oceanguard.ai.ui.screens.home

import androidx.compose.runtime.Immutable
import com.oceanguard.ai.ModelLoadingState
import com.oceanguard.ai.ModelStatus
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.data.AnalysisModeAvailability
import com.oceanguard.ai.inference.DetectorType
import com.oceanguard.ai.ui.motion.OceanMotion
import java.util.Locale

/** What the user chose to scan from Home. */
enum class ScanTarget { DEBRIS, SPECIES }

/** What the status line says about the detector that will really run. */
enum class DetectorStatusKind {
    READY,
    LOADING,
    WARMING_UP,
    DOWNLOADING,
    NEEDS_DOWNLOAD,
    FAILED,
    STANDBY,
    NOT_LOADED;

    /** Scanning is offered when the model is ready, or when tapping can start its download. */
    val canScan: Boolean get() = this == READY || this == NEEDS_DOWNLOAD
}

/** Everything Home shows about the analysis mode and its models. */
@Immutable
data class HomeModelUi(
    val mode: AnalysisMode,
    val availability: AnalysisModeAvailability,
    val kind: DetectorStatusKind,
    /** Name of the detector that is loaded, or null while the selected mode must be downloaded. */
    val modelName: String?,
    /** True when the model that writes reports is not on the phone yet. */
    val reportModelMissing: Boolean,
)

/** Decisions of the Home screen kept apart from Compose so they can be tested. */
object HomeLogic {

    /** Delay before the guided tour starts, so the entry animations settle first. */
    const val TOUR_START_DELAY_MS = OceanMotion.TRANSITION_MS + OceanMotion.STAGGER_MS * 4

    /** How long to wait for the location permission answer before giving up on it. */
    const val LOCATION_WAIT_MS = 15_000L

    private const val BYTES_PER_STEP = 1024
    private const val PERCENT = 100f
    private const val MIN_DECIMAL_UNIT = 2

    /** Status of the detector of [effective], the mode that will really run. */
    fun detectorStatus(effective: AnalysisMode, state: ModelLoadingState): ModelStatus =
        if (effective.detector == DetectorType.GEMMA4_VISION) state.gemma4Vision else state.rtdetr

    fun statusKind(
        availability: AnalysisModeAvailability,
        isDownloading: Boolean,
        status: ModelStatus,
    ): DetectorStatusKind = when {
        isDownloading -> DetectorStatusKind.DOWNLOADING
        availability == AnalysisModeAvailability.NEEDS_DOWNLOAD -> DetectorStatusKind.NEEDS_DOWNLOAD
        else -> when (status) {
            ModelStatus.Ready -> DetectorStatusKind.READY
            ModelStatus.Loading -> DetectorStatusKind.LOADING
            ModelStatus.WarmingUp -> DetectorStatusKind.WARMING_UP
            ModelStatus.Error -> DetectorStatusKind.FAILED
            ModelStatus.Standby -> DetectorStatusKind.STANDBY
            ModelStatus.NotLoaded -> DetectorStatusKind.NOT_LOADED
        }
    }

    /**
     * Whether the status line offers a one-tap download of the report model: only while it is
     * missing and nothing is downloading. It is a quiet row, never a pop-up, so there is nothing
     * to dismiss and no repeated prompt.
     */
    fun offerReportDownload(reportModelMissing: Boolean, kind: DetectorStatusKind): Boolean =
        reportModelMissing && kind != DetectorStatusKind.DOWNLOADING

    /** Species do not depend on the debris detector, so they can always be scanned. */
    fun scanEnabled(target: ScanTarget, kind: DetectorStatusKind): Boolean =
        target == ScanTarget.SPECIES || kind.canScan

    /** Debris scans with a model that is still missing offer its download instead of starting. */
    fun needsDownloadFirst(target: ScanTarget, kind: DetectorStatusKind): Boolean =
        target == ScanTarget.DEBRIS && kind == DetectorStatusKind.NEEDS_DOWNLOAD

    /** The Gemma engine is on disk but not in memory: load it now so the first scan is not slow. */
    fun shouldPrewarmGemma(
        mode: AnalysisMode,
        availability: AnalysisModeAvailability,
        gemmaStatus: ModelStatus,
    ): Boolean = mode.needsGemma &&
        availability == AnalysisModeAvailability.READY &&
        (gemmaStatus == ModelStatus.NotLoaded || gemmaStatus == ModelStatus.Standby)

    /** The first-run tour waits for the location prompt so a system dialog never covers it. */
    fun shouldStartTour(tourComplete: Boolean, locationResolved: Boolean): Boolean =
        !tourComplete && locationResolved

    /** Update dialogs must not appear over a tour that is about to start or is running. */
    fun mayShowUpdateDialogs(tourComplete: Boolean, tourActive: Boolean): Boolean =
        tourComplete && !tourActive

    fun entryDelayMs(index: Int): Int = index.coerceAtLeast(0) * OceanMotion.STAGGER_MS

    fun formatGb(gb: Float, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f", gb)

    /** "123 MB / 2.5 GB  (45%)" for the inline download progress. */
    fun formatBytesLine(downloaded: Long, total: Long, progress: Float): String {
        val pct = (progress * PERCENT).toInt().coerceIn(0, 100)
        return "${humanReadableSize(downloaded)} / ${humanReadableSize(total)}  ($pct%)"
    }

    fun humanReadableSize(bytes: Long, locale: Locale = Locale.US): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= BYTES_PER_STEP && unit < units.lastIndex) {
            value /= BYTES_PER_STEP
            unit++
        }
        val format = if (unit >= MIN_DECIMAL_UNIT) "%.1f %s" else "%.0f %s"
        return String.format(locale, format, value, units[unit])
    }
}
