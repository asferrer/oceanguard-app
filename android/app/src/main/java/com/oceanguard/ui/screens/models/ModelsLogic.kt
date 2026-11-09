package com.oceanguard.ai.ui.screens.models

import androidx.annotation.StringRes
import com.oceanguard.ai.ModelLoadingState
import com.oceanguard.ai.ModelStatus
import com.oceanguard.ai.R
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.data.AnalysisModeAvailability
import com.oceanguard.ai.data.AnalysisModels
import com.oceanguard.ai.inference.DetectorType
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.inference.TextModelVariant
import com.oceanguard.ai.inference.VlmDownloadState
import com.oceanguard.ai.ui.components.sonar.ButtonProgress
import java.util.Locale

/**
 * Everything the user can have on the phone besides the detector that ships in the app.
 *
 * @param tier null for the species pack, which is not a text model.
 * @param approxBytes Download size used to check free space before starting.
 * @param advancedOnly Only offered for download with advanced options on. An installed one is
 *   always listed so it can be deleted.
 * @param boundToMode Reached by choosing an analysis mode, not from the plain download list.
 */
enum class CatalogModel(
    val tier: TextModelTier?,
    val variant: TextModelVariant,
    val approxBytes: Long,
    val advancedOnly: Boolean,
    val boundToMode: Boolean,
    @StringRes val titleRes: Int,
    @StringRes val purposeRes: Int,
) {
    GEMMA_BASE(
        TextModelTier.GEMMA4_E2B, TextModelVariant.BASE, 2_580_000_000L, false, true,
        R.string.models_item_gemma_base, R.string.models_item_gemma_base_purpose,
    ),
    GEMMA_TUNED(
        TextModelTier.GEMMA4_E2B, TextModelVariant.FINETUNED, 5_245_718_144L, false, true,
        R.string.models_item_gemma_tuned, R.string.models_item_gemma_tuned_purpose,
    ),
    SPECIES_PACK(
        null, TextModelVariant.BASE, 615_000_000L, false, false, // pack v3: BioCLIP 2 (fp16 weights) 611.5 MB with index and catalogue, plus raster
        R.string.models_item_species, R.string.models_item_species_purpose,
    ),
    QWEN_FAST(
        TextModelTier.FAST, TextModelVariant.BASE, 533_000_000L, true, false,
        R.string.models_item_qwen_fast, R.string.models_item_qwen_purpose,
    ),
    QWEN_BALANCED(
        TextModelTier.BALANCED, TextModelVariant.BASE, 1_100_000_000L, true, false,
        R.string.models_item_qwen_balanced, R.string.models_item_qwen_purpose,
    ),
    QWEN_QUALITY(
        TextModelTier.QUALITY, TextModelVariant.BASE, 2_740_000_000L, true, false,
        R.string.models_item_qwen_quality, R.string.models_item_qwen_purpose,
    ),
    GEMMA_GGUF(
        TextModelTier.OCEANGUARD_FT, TextModelVariant.BASE, 3_420_000_000L, true, false,
        R.string.models_item_gguf, R.string.models_item_gguf_purpose,
    );

    /** Species files have no engine to release and are kept: only text models can be deleted. */
    val deletable: Boolean get() = tier != null

    companion object {
        /** The catalog entry of a text model; the base file when that variant is not listed. */
        fun forTier(tier: TextModelTier, variant: TextModelVariant): CatalogModel? =
            entries.firstOrNull { it.tier == tier && it.variant == variant }
                ?: entries.firstOrNull { it.tier == tier && it.variant == TextModelVariant.BASE }

        fun forMode(mode: AnalysisMode): CatalogModel? = when {
            !mode.needsGemma -> null
            mode.variant == TextModelVariant.FINETUNED -> GEMMA_TUNED
            else -> GEMMA_BASE
        }
    }
}

/** What listing the catalog gives for the current installs. */
data class CatalogSplit(
    /** Installed, with their size: what can be deleted. */
    val installed: List<CatalogModel>,
    /** Not installed and offered for download outside the mode list. */
    val downloadable: List<CatalogModel>,
)

fun splitCatalog(isInstalled: (CatalogModel) -> Boolean, advanced: Boolean): CatalogSplit {
    val (have, missing) = CatalogModel.entries.partition(isInstalled)
    return CatalogSplit(
        installed = have,
        downloadable = missing.filter { !it.boundToMode && (advanced || !it.advancedOnly) },
    )
}

/** The catalog model a downloaded file belongs to; [finetunedFilename] may come from a manifest. */
fun modelForFile(filename: String, finetunedFilename: String, speciesFiles: List<String>): CatalogModel? {
    val matches = CatalogModel.entries.firstOrNull { model ->
        val tier = model.tier
        tier != null && model.variant == TextModelVariant.BASE && tier.filename == filename
    }
    return when {
        filename == finetunedFilename -> CatalogModel.GEMMA_TUNED
        matches != null -> matches
        filename in speciesFiles -> CatalogModel.SPECIES_PACK
        else -> null
    }
}

// ---------------------------------------------------------------------------
// Before downloading
// ---------------------------------------------------------------------------

enum class NetworkKind { NONE, UNMETERED, METERED }

/** What to tell the user before a download starts. */
sealed interface DownloadPlan {
    data object Proceed : DownloadPlan
    data object NoNetwork : DownloadPlan
    /** Mobile data or a metered Wi-Fi: ask first. */
    data object Metered : DownloadPlan
    data class NoSpace(val missingBytes: Long) : DownloadPlan
}

/** Room left for the system and for the rename of the finished file. */
const val SPACE_MARGIN_BYTES = 300_000_000L

fun planDownload(requiredBytes: Long, freeBytes: Long, network: NetworkKind): DownloadPlan {
    val needed = requiredBytes + SPACE_MARGIN_BYTES
    return when {
        freeBytes < needed -> DownloadPlan.NoSpace(needed - freeBytes)
        network == NetworkKind.NONE -> DownloadPlan.NoNetwork
        network == NetworkKind.METERED -> DownloadPlan.Metered
        else -> DownloadPlan.Proceed
    }
}

/** Decimal units, like the labels on the stores and the file manager: 2.6 GB, 533 MB. */
fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
    bytes >= 1_000_000_000L -> String.format(locale, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> String.format(locale, "%d MB", Math.round(bytes / 1e6))
    else -> String.format(locale, "%d KB", Math.round(bytes / 1e3))
}

// ---------------------------------------------------------------------------
// In use now
// ---------------------------------------------------------------------------

enum class RingState { ACTIVE, READY, MISSING, UNAVAILABLE }

data class Ring(val mode: AnalysisMode, val state: RingState)

/** One ring per mode, shallowest first: the active one lit, missing ones dashed. */
fun ringStates(
    effective: AnalysisMode,
    isDownloaded: (TextModelTier, TextModelVariant) -> Boolean,
    finetunedPublished: Boolean,
): List<Ring> = AnalysisMode.entries.map { mode ->
    val state = when (mode.availability(isDownloaded, finetunedPublished)) {
        AnalysisModeAvailability.NOT_PUBLISHED -> RingState.UNAVAILABLE
        AnalysisModeAvailability.NEEDS_DOWNLOAD -> RingState.MISSING
        AnalysisModeAvailability.READY ->
            if (mode == effective) RingState.ACTIVE else RingState.READY
    }
    Ring(mode, state)
}

/** Name of the model that detects with [mode]: product names, saved the same way as sessions. */
fun detectionModelName(mode: AnalysisMode): String = when {
    mode.refineWithGemma -> AnalysisModels.refined(AnalysisModels.RT_DETR_V2, AnalysisModels.GEMMA4_BASE)
    mode.detector == DetectorType.GEMMA4_VISION ->
        if (mode.variant == TextModelVariant.FINETUNED) {
            AnalysisModels.GEMMA4_OCEANGUARD
        } else {
            AnalysisModels.GEMMA4_BASE
        }
    else -> AnalysisModels.RT_DETR_V2
}

enum class LoadState { READY, LOADING, ERROR, ON_DEMAND }

/** What the engine behind the detector is doing, from the state the app really publishes. */
fun detectorLoadState(effective: AnalysisMode, loading: ModelLoadingState): LoadState {
    val status = if (effective.detector == DetectorType.GEMMA4_VISION) {
        loading.gemma4Vision
    } else {
        loading.rtdetr
    }
    return when (status) {
        ModelStatus.Ready -> LoadState.READY
        ModelStatus.Loading, ModelStatus.WarmingUp -> LoadState.LOADING
        ModelStatus.Error -> LoadState.ERROR
        ModelStatus.NotLoaded, ModelStatus.Standby -> LoadState.ON_DEMAND
    }
}

enum class ModelTask(@StringRes val titleRes: Int) {
    DETECT(R.string.models_task_detect),
    SCAN(R.string.models_task_scan),
    REPORT(R.string.models_task_report),
    SPECIES(R.string.models_task_species),
}

/** @param modelName null when nothing is installed for the task yet. */
data class TaskUse(val task: ModelTask, val modelName: String?)

/** The model behind each task. Continuous scan and video always use RT-DETRv2. */
fun taskUses(effective: AnalysisMode, reportModel: String?, speciesReady: Boolean): List<TaskUse> = listOf(
    TaskUse(ModelTask.DETECT, detectionModelName(effective)),
    TaskUse(ModelTask.SCAN, AnalysisModels.RT_DETR_V2),
    TaskUse(ModelTask.REPORT, reportModel),
    TaskUse(ModelTask.SPECIES, if (speciesReady) AnalysisModels.GEMMA4_BASE else null),
)

/** A chosen mode whose download has finished is applied once, without another tap. */
fun shouldApplyPending(
    pending: AnalysisMode?,
    isDownloaded: (TextModelTier, TextModelVariant) -> Boolean,
    finetunedPublished: Boolean,
): Boolean = pending != null &&
    pending.availability(isDownloaded, finetunedPublished) == AnalysisModeAvailability.READY

// ---------------------------------------------------------------------------
// Download progress on the right button
// ---------------------------------------------------------------------------

/**
 * Which model owns the running download. There is a single download state for the whole app, so
 * the file name identifies it while bytes flow, and the last request covers preparing and
 * installing, which carry no name.
 */
fun downloadOwner(
    state: VlmDownloadState,
    requested: CatalogModel?,
    finetunedFilename: String,
    speciesFiles: List<String>,
): CatalogModel? = when (state) {
    is VlmDownloadState.Downloading ->
        modelForFile(state.currentFile, finetunedFilename, speciesFiles) ?: requested
    VlmDownloadState.Preparing, VlmDownloadState.Installing -> requested
    else -> null
}

/** The button of the owner shows the progress; the rest stay idle (and disabled by the caller). */
fun buttonProgress(state: VlmDownloadState, isOwner: Boolean): ButtonProgress = when {
    !isOwner -> ButtonProgress.Idle
    state is VlmDownloadState.Downloading -> ButtonProgress.Determinate(state.progress)
    state == VlmDownloadState.Preparing || state == VlmDownloadState.Installing ->
        ButtonProgress.Indeterminate
    else -> ButtonProgress.Idle
}

/** True while a download occupies the single download slot. */
fun isDownloadBusy(state: VlmDownloadState): Boolean =
    state is VlmDownloadState.Downloading || state == VlmDownloadState.Preparing ||
        state == VlmDownloadState.Installing
