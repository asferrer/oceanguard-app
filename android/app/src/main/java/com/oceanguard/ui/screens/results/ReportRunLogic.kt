package com.oceanguard.ai.ui.screens.results

import com.oceanguard.ai.ReportGenerationState
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.inference.VlmDownloadState
import com.oceanguard.ai.ui.components.sonar.ButtonProgress

/** Stages a report goes through, in the order the app really runs them. */
enum class ReportStage { MODEL, DATA, WRITING }

enum class StepStatus { PENDING, ACTIVE, DONE }

data class ReportStepState(
    val stage: ReportStage,
    val status: StepStatus,
    /** `3/8` while the data stage queries its tools. */
    val progress: String? = null,
)

/** The stage a generation state belongs to; null when nothing is being generated. */
fun activeStage(state: ReportGenerationState): ReportStage? = when (state) {
    is ReportGenerationState.LoadingModel -> ReportStage.MODEL
    is ReportGenerationState.Generating,
    is ReportGenerationState.ToolExecuting -> ReportStage.DATA
    is ReportGenerationState.ComposingProse,
    is ReportGenerationState.StreamingText -> ReportStage.WRITING
    else -> null
}

/** Every stage with its status: those before [active] are done, those after still pending. */
fun reportSteps(active: ReportStage?, toolProgress: Pair<Int, Int>?): List<ReportStepState> =
    ReportStage.entries.map { stage ->
        val status = when {
            active == null -> StepStatus.PENDING
            stage.ordinal < active.ordinal -> StepStatus.DONE
            stage == active -> StepStatus.ACTIVE
            else -> StepStatus.PENDING
        }
        val progress = if (stage == ReportStage.DATA && status == StepStatus.ACTIVE) {
            toolProgress?.let { "${it.first}/${it.second}" }
        } else {
            null
        }
        ReportStepState(stage, status, progress)
    }

/** Whether the report model is ready before the person presses Generate. */
enum class ReportModelStatus { READY, NEEDS_DOWNLOAD, DOWNLOADING }

fun reportModelStatus(available: Boolean, downloading: Boolean): ReportModelStatus = when {
    downloading -> ReportModelStatus.DOWNLOADING
    available -> ReportModelStatus.READY
    else -> ReportModelStatus.NEEDS_DOWNLOAD
}

/**
 * Whether a download that just finished should start the report on its own: only when the person
 * asked for it by pressing Generate and the model is now there.
 */
fun shouldStartAfterDownload(requested: Boolean, modelNowAvailable: Boolean, generating: Boolean): Boolean =
    requested && modelNowAvailable && !generating

enum class ReportExportFormat(val mimeType: String) {
    PDF("application/pdf"),
    MARKDOWN("text/markdown"),
    CSV("text/csv"),
    GEOJSON("application/geo+json"),
}

/** Why a format cannot be produced for these sessions. */
enum class ExportBlock { NO_SESSIONS, NO_LOCATION }

/** The reason [format] cannot be produced for [sessions], or null when it can. */
fun exportBlock(format: ReportExportFormat, sessions: List<DetectionSession>): ExportBlock? = when (format) {
    ReportExportFormat.CSV -> if (sessions.isEmpty()) ExportBlock.NO_SESSIONS else null
    ReportExportFormat.GEOJSON -> when {
        sessions.isEmpty() -> ExportBlock.NO_SESSIONS
        sessions.none { it.location != null } -> ExportBlock.NO_LOCATION
        else -> null
    }
    else -> null
}

/** What the report screen needs to know about a generation without reading every streamed token. */
data class GenerationInfo(
    val generating: Boolean,
    val stage: ReportStage?,
    val toolProgress: Pair<Int, Int>?,
    val toolName: String?,
)

fun generationInfo(state: ReportGenerationState): GenerationInfo {
    val tool = state as? ReportGenerationState.ToolExecuting
    return GenerationInfo(
        generating = activeStage(state) != null,
        stage = activeStage(state),
        toolProgress = tool?.takeIf { it.progressTotal > 0 }?.let { it.progressCurrent to it.progressTotal },
        toolName = tool?.toolName,
    )
}

/** The download as the button shows it: a fraction while bytes arrive, indeterminate otherwise. */
fun downloadProgress(state: VlmDownloadState): ButtonProgress = when (state) {
    is VlmDownloadState.Downloading -> ButtonProgress.Determinate(state.progress.coerceIn(0f, 1f))
    is VlmDownloadState.Preparing, is VlmDownloadState.Installing -> ButtonProgress.Indeterminate
    else -> ButtonProgress.Idle
}

fun isDownloading(state: VlmDownloadState): Boolean = downloadProgress(state) != ButtonProgress.Idle

/** Whether [timeMs] falls inside the optional bounds, both inclusive. */
fun inRange(timeMs: Long, startMs: Long?, endMs: Long?): Boolean =
    (startMs == null || timeMs >= startMs) && (endMs == null || timeMs <= endMs)
