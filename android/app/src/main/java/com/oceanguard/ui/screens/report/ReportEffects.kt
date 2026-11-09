package com.oceanguard.ai.ui.screens.report

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.ReportGenerationState
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.inference.ReportType
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.inference.VlmDownloadState
import com.oceanguard.ai.ui.screens.results.ReportModelStatus
import com.oceanguard.ai.ui.screens.results.shouldStartAfterDownload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the page can ask of the effects: press Generate, whatever state the model is in. */
@Stable
internal class ReportEffectsHandle(
    val onGenerate: (ReportZones, ReportChoices, ReportSelection, ReportModelStatus) -> Unit,
)

/**
 * Keeps the report run in step with the app: turns a finished run into a message and a highlighted
 * card, a failed one into an error state, and starts the report on its own when a download that
 * the person asked for finishes.
 */
@Composable
internal fun ReportEffects(
    app: OceanGuardApp,
    ui: ReportUiState,
    snackbar: SnackbarHostState,
): ReportEffectsHandle {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val generation by app.reportGenerationState.collectAsStateWithLifecycle()
    val download by app.vlmModelManager.downloadState.collectAsStateWithLifecycle()
    val tierKey by app.settingsRepository.vlmModelTier
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_VLM_MODEL_TIER)
    val pending = remember { PendingStart() }

    LaunchedEffect(generation::class) {
        when (val state = generation) {
            is ReportGenerationState.Complete -> onReportReady(app, ui, snackbar, scope, state.report, context)
            is ReportGenerationState.Error -> {
                ui.screen = ReportScreenState.Error(state.message)
                app.reportGenerationState.value = ReportGenerationState.Idle
            }
            else -> Unit
        }
    }
    LaunchedEffect(download) {
        onDownloadChanged(download, app, ui, snackbar, scope, pending, TextModelTier.fromKey(tierKey), context)
    }
    return remember(app, ui) {
        ReportEffectsHandle { zones, choices, selection, status ->
            requestGeneration(app, ui, pending, zones, choices, selection, status)
        }
    }
}

private fun onDownloadChanged(
    state: VlmDownloadState,
    app: OceanGuardApp,
    ui: ReportUiState,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
    pending: PendingStart,
    tier: TextModelTier,
    context: android.content.Context,
) {
    when (state) {
        is VlmDownloadState.Complete -> {
            val generating = app.reportGenerationState.value !is ReportGenerationState.Idle
            if (shouldStartAfterDownload(ui.pendingStart, app.vlmModelManager.isAnyVariantAvailable(tier), generating)) {
                ui.pendingStart = false
                pending.action?.invoke()
            }
            scope.launch { snackbar.showSnackbar(context.getString(R.string.vlm_download_complete_snackbar)) }
        }
        is VlmDownloadState.Error -> {
            ui.pendingStart = false
            scope.launch {
                val result = snackbar.showSnackbar(
                    message = context.getString(R.string.vlm_download_error_snackbar, state.message),
                    actionLabel = context.getString(R.string.vlm_download_retry),
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    ui.pendingStart = pending.action != null
                    app.launchVlmDownload(tier)
                }
            }
        }
        else -> Unit
    }
}

/** The report to start once the model is there; kept outside composition state on purpose. */
private class PendingStart {
    var action: (() -> Unit)? = null
}

private fun requestGeneration(
    app: OceanGuardApp,
    ui: ReportUiState,
    pending: PendingStart,
    zones: ReportZones,
    choices: ReportChoices,
    selection: ReportSelection,
    status: ReportModelStatus,
) {
    if (zones.selectedZone == null || !selection.ready) return
    ui.screen = ReportScreenState.Idle
    val start = {
        if (selection.type == ReportType.DEBRIS) {
            app.launchZoneReportGeneration(selection.toInput(zones), choices.language)
        } else {
            app.launchSpeciesReportGeneration(selection.toSpeciesInput(zones), choices.language)
        }
    }
    when (status) {
        ReportModelStatus.READY -> start()
        ReportModelStatus.NEEDS_DOWNLOAD -> {
            pending.action = start
            ui.showDownloadDialog = true
        }
        ReportModelStatus.DOWNLOADING -> {
            pending.action = start
            ui.pendingStart = true
        }
    }
}

private fun onReportReady(
    app: OceanGuardApp,
    ui: ReportUiState,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
    report: GeneratedReport,
    context: android.content.Context,
) {
    ui.screen = ReportScreenState.Idle
    ui.newReportId = report.id
    app.reportGenerationState.value = ReportGenerationState.Idle
    scope.launch {
        val result = snackbar.showSnackbar(
            message = context.getString(R.string.report_snackbar_generated),
            actionLabel = context.getString(R.string.report_snackbar_view_action),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) ui.open(report)
    }
}
