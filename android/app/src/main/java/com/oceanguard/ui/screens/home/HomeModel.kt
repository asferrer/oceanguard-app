package com.oceanguard.ai.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.ui.screens.models.CatalogModel
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.inference.TextModelVariant
import com.oceanguard.ai.inference.VlmDownloadState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Collects what Home shows about models: the saved mode, the real engine state, any download in
 * progress and whether the report model is installed. Recomputes when a download finishes.
 */
@Composable
fun rememberHomeModel(app: OceanGuardApp): HomeModelUi {
    val initial = remember { app.settingsRepository.getAnalysisModeSync() }
    val mode by app.settingsRepository.analysisMode.collectAsStateWithLifecycle(initial)
    val engines by app.modelLoadingState.collectAsStateWithLifecycle()
    val isDownloading by rememberIsDownloading(app)
    val installs = rememberInstallCounter(app)

    val availability = remember(mode, installs.intValue) { mode.availability(app::isModelDownloaded) }
    val effective = remember(mode, installs.intValue) { mode.effective(app::isModelDownloaded) }
    val kind = HomeLogic.statusKind(
        availability = availability,
        isDownloading = isDownloading,
        status = HomeLogic.detectorStatus(effective, engines),
    )
    val modelName = remember(effective, engines, kind) {
        if (kind == DetectorStatusKind.NEEDS_DOWNLOAD) null else app.activeDetectorModelName()
    }
    val reportMissing = remember(installs.intValue, mode) { isReportModelMissing(app) }
    return HomeModelUi(mode, availability, kind, modelName, reportMissing)
}

/** Tier the user chose for reports, so the status line can say whether it is installed. */
fun reportTier(app: OceanGuardApp): TextModelTier =
    TextModelTier.fromKey(app.settingsRepository.getVlmModelTierSync())

/** The catalog entry the report model download goes through, or null when there is none. */
fun reportCatalogModel(app: OceanGuardApp): CatalogModel? =
    CatalogModel.forTier(reportTier(app), TextModelVariant.fromKey(app.settingsRepository.getVlmModelVariantSync()))

private fun isReportModelMissing(app: OceanGuardApp): Boolean {
    val variant = TextModelVariant.fromKey(app.settingsRepository.getVlmModelVariantSync())
    return !app.isModelDownloaded(reportTier(app), variant)
}

/** True while a model is being prepared, downloaded or installed. Ignores byte-level progress. */
@Composable
private fun rememberIsDownloading(app: OceanGuardApp): State<Boolean> {
    val flow = remember(app) {
        // The species pack has its own progress card: it must not also show as the detector downloading.
        combine(app.vlmModelManager.downloadState, app.vlmModelManager.speciesPackSelected) { state, species ->
            !species && (state is VlmDownloadState.Preparing || state is VlmDownloadState.Downloading || state is VlmDownloadState.Installing)
        }.distinctUntilChanged()
    }
    return flow.collectAsStateWithLifecycle(initialValue = false)
}

/** Goes up each time a download completes, to re-read what is on disk without polling. */
@Composable
private fun rememberInstallCounter(app: OceanGuardApp): MutableIntState {
    val counter = remember { mutableIntStateOf(0) }
    LaunchedEffect(app) {
        app.vlmModelManager.downloadState
            .map { it is VlmDownloadState.Complete }
            .distinctUntilChanged()
            .collect { complete -> if (complete) counter.intValue++ }
    }
    return counter
}
