package com.oceanguard.ai.ui.screens.models

import com.oceanguard.ai.inference.DownloadFailureKind
import com.oceanguard.ai.inference.VlmDownloadState

/** What the species pack looks like to the person: installed, missing, being fetched or failed. */
sealed interface SpeciesPackStatus {
    data object Ready : SpeciesPackStatus
    data object Missing : SpeciesPackStatus

    /** [progress] is null while preparing or installing (no byte count yet). */
    data class Downloading(
        val progress: Float?,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
    ) : SpeciesPackStatus

    data class Failed(val kind: DownloadFailureKind) : SpeciesPackStatus
}

/** Decisions behind the species pack prompts, kept apart from Compose so they can be tested. */
object SpeciesPackLogic {

    /**
     * Status of the pack. The download state is shared with the report and detector models, so it
     * only counts as the pack's own while [speciesSelected] says the last download was the pack's.
     */
    fun status(installed: Boolean, speciesSelected: Boolean, state: VlmDownloadState): SpeciesPackStatus {
        if (installed) return SpeciesPackStatus.Ready
        if (!speciesSelected) return SpeciesPackStatus.Missing
        return when (state) {
            is VlmDownloadState.Downloading ->
                SpeciesPackStatus.Downloading(state.progress, state.downloadedBytes, state.totalBytes)
            VlmDownloadState.Preparing, VlmDownloadState.Installing -> SpeciesPackStatus.Downloading(null)
            is VlmDownloadState.Error -> SpeciesPackStatus.Failed(state.kind)
            else -> SpeciesPackStatus.Missing
        }
    }

    /** The prompt that offers the download is hidden once the pack is installed. */
    fun offersDownload(status: SpeciesPackStatus): Boolean =
        status is SpeciesPackStatus.Missing || status is SpeciesPackStatus.Failed

    /** True on the step where the pack goes from absent to installed, so a screen can resume its work. */
    fun justInstalled(previous: SpeciesPackStatus?, current: SpeciesPackStatus): Boolean =
        previous != null && previous != SpeciesPackStatus.Ready && current == SpeciesPackStatus.Ready
}
