package com.oceanguard.ai.ui.screens.report

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.data.SessionDatesHelper
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.inference.TextModelVariant
import com.oceanguard.ai.ui.screens.models.CatalogModel
import com.oceanguard.ai.ui.screens.models.ModelDownloader
import com.oceanguard.ai.ui.screens.models.rememberModelDownloader
import com.oceanguard.ai.ui.components.DataDotDatePickerDialog
import com.oceanguard.ai.ui.components.spotlight.GuidedTourTransitionDialog
import com.oceanguard.ai.ui.components.spotlight.SpotlightController
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.screens.results.ReportExportFormat
import kotlinx.coroutines.launch

/** Confirmations the page can raise: download the model, delete a report, pick custom dates. */
@Composable
internal fun ReportDialogs(
    app: OceanGuardApp,
    ui: ReportUiState,
    choices: ReportChoicesState,
    sessions: List<DetectionSession>,
) {
    val downloader = rememberModelDownloader()
    if (ui.showDownloadDialog) DownloadModelDialog(app, ui, downloader)
    downloader.PreflightDialog()
    ui.reportToDelete?.let { DeleteReportDialog(app, ui, it) }
    if (choices.pickerVisible) {
        val period = choices.choices.period
        DataDotDatePickerDialog(
            onDismissRequest = choices::dismissPicker,
            initialStartDateMillis = period.customStartMs,
            initialEndDateMillis = period.customEndMs,
            datesWithData = remember(sessions) { SessionDatesHelper.datesWithData(sessions) },
            onConfirm = choices::confirmDates,
        )
    }
}

@Composable
private fun DownloadModelDialog(app: OceanGuardApp, ui: ReportUiState, downloader: ModelDownloader) {
    val tierKey by app.settingsRepository.vlmModelTier
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_VLM_MODEL_TIER)
    val tier = TextModelTier.fromKey(tierKey)
    AlertDialog(
        onDismissRequest = { ui.showDownloadDialog = false },
        title = { Text(stringResource(R.string.vlm_download_dialog_title)) },
        text = { Text(stringResource(R.string.vlm_download_dialog_message, tier.sizeLabel)) },
        confirmButton = {
            TextButton(onClick = {
                ui.showDownloadDialog = false
                // Checks space and network first; the report starts on its own once it arrives.
                val model = CatalogModel.forTier(tier, TextModelVariant.BASE)
                if (model != null) {
                    downloader.request(model) { ui.pendingStart = true }
                } else {
                    ui.pendingStart = true
                    app.launchVlmDownload(tier)
                }
            }) { Text(stringResource(R.string.vlm_download_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = { ui.showDownloadDialog = false }) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun DeleteReportDialog(app: OceanGuardApp, ui: ReportUiState, report: GeneratedReport) {
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { ui.reportToDelete = null },
        title = { Text(stringResource(R.string.report_dialog_delete_title)) },
        text = { Text(stringResource(R.string.report_dialog_delete_message)) },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { app.database.generatedReportDao().delete(report) }
                if (ui.viewing?.id == report.id) ui.close()
                ui.reportToDelete = null
            }) { Text(stringResource(R.string.report_btn_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = { ui.reportToDelete = null }) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** The open report over the page. It rises from below, or just fades under "Remove animations". */
@Composable
internal fun ReportViewOverlay(
    app: OceanGuardApp,
    ui: ReportUiState,
    snackbar: SnackbarHostState,
    selection: ReportSelection,
    navController: NavController,
    sessions: List<DetectionSession>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val reportSessions = rememberViewedSessions(app, ui, selection)

    AnimatedVisibility(
        visible = ui.viewing != null,
        enter = if (reduced) fadeIn(OceanMotion.reducedFade()) else slideInVertically(OceanMotion.transition()) { it } + fadeIn(OceanMotion.transition()),
        exit = if (reduced) fadeOut(OceanMotion.reducedFade()) else slideOutVertically(OceanMotion.exit()) { it } + fadeOut(OceanMotion.exit()),
    ) {
        ui.lastViewed?.let { report ->
            ReportFullScreenView(
                report = report,
                sessions = reportSessions,
                exporting = ui.exporting,
                actions = ReportViewActions(
                    onClose = ui::close,
                    onExport = { format -> scope.launch { runExport(context, ui, snackbar, format, report, reportSessions) } },
                    onSessionClick = { id ->
                        ui.close()
                        navController.navigate("session/$id")
                    },
                    onAddLocation = {
                        val target = reportSessions.firstOrNull { it.location == null } ?: sessions.firstOrNull { it.location == null }
                        target?.let { navController.navigate("location_picker/${it.id}") }
                    },
                ),
            )
        }
    }
}

private suspend fun runExport(
    context: android.content.Context,
    ui: ReportUiState,
    snackbar: SnackbarHostState,
    format: ReportExportFormat,
    report: GeneratedReport,
    sessions: List<DetectionSession>,
) {
    ui.exporting = true
    val ok = exportReport(context, format, report, sessions)
    ui.exporting = false
    if (!ok) snackbar.showSnackbar(context.getString(R.string.results_export_error))
}

/**
 * Photos of the open report: the ones stored with it, or, for older reports without them, the
 * ones of the zone and period currently selected.
 */
@Composable
private fun rememberViewedSessions(app: OceanGuardApp, ui: ReportUiState, selection: ReportSelection): List<DetectionSession> {
    var sessions by remember { mutableStateOf<List<DetectionSession>>(emptyList()) }
    val report = ui.viewing
    LaunchedEffect(report?.id, selection.sessions) {
        val ids = report?.sessionIds?.split(",")?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()
        sessions = when {
            report == null -> emptyList()
            ids.isNotEmpty() -> app.database.detectionSessionDao().getSessionsByIds(ids)
            else -> selection.sessions
        }
    }
    return sessions
}

/** First-use tour of the page and the dialog that continues the guided tour. */
@Composable
internal fun ReportTour(
    app: OceanGuardApp,
    navController: NavController,
    tour: SpotlightController,
    boundsMap: MutableMap<String, Rect>,
) {
    val scope = rememberCoroutineScope()
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.REPORTS))
        .collectAsStateWithLifecycle(initialValue = true)
    val guided by app.settingsRepository.guidedTourActive.collectAsStateWithLifecycle(initialValue = false)
    var showTransition by remember { mutableStateOf(false) }
    LaunchedEffect(tourComplete) { if (!tourComplete) tour.start() }

    SpotlightOverlay(
        controller = tour,
        targetBounds = boundsMap,
        onComplete = {
            scope.launch {
                app.settingsRepository.markTourComplete(TourDefinitions.getScreenId(TourDefinitions.REPORTS))
                if (guided) showTransition = true else app.launchDemoCleanupIfComplete()
            }
        },
        isGuidedTour = guided,
        onSkipTutorial = {
            scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
            app.launchDemoCleanup()
        },
    )
    if (showTransition) {
        GuidedTourTransitionDialog(
            screenId = "reports",
            onContinue = {
                showTransition = false
                navController.navigate("history") {
                    popUpTo("home") { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            onSkipTutorial = {
                showTransition = false
                scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                app.launchDemoCleanup()
            },
        )
    }
}
