package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.inference.ReportType
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.inference.VlmDownloadState
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.sonar.SonarErrorState
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.screens.report.GenerateUi
import com.oceanguard.ai.ui.screens.report.ReportChoicesState
import com.oceanguard.ai.ui.screens.report.ReportEffectsHandle
import com.oceanguard.ai.ui.screens.report.ReportZones
import com.oceanguard.ai.ui.screens.report.SavedReportsDatePicker
import com.oceanguard.ai.ui.screens.report.ReportDialogs
import com.oceanguard.ai.ui.screens.report.ReportEffects
import com.oceanguard.ai.ui.screens.report.ReportGenerateSection
import com.oceanguard.ai.ui.screens.report.ReportProgressCard
import com.oceanguard.ai.ui.screens.report.ReportScreenState
import com.oceanguard.ai.ui.screens.report.ReportSelection
import com.oceanguard.ai.ui.screens.report.ReportSetupPanel
import com.oceanguard.ai.ui.screens.report.ReportTour
import com.oceanguard.ai.ui.screens.report.ReportUiState
import com.oceanguard.ai.ui.screens.report.ReportViewOverlay
import com.oceanguard.ai.ui.screens.report.SavedReportsFilter
import com.oceanguard.ai.ui.screens.report.rememberReportChoices
import com.oceanguard.ai.ui.screens.report.rememberReportZones
import com.oceanguard.ai.ui.screens.report.savedReportsSection
import com.oceanguard.ai.ui.screens.results.GenerationInfo
import com.oceanguard.ai.ui.screens.results.ReportModelStatus
import com.oceanguard.ai.ui.screens.results.downloadProgress
import com.oceanguard.ai.ui.screens.results.generationInfo
import com.oceanguard.ai.ui.screens.results.isDownloading
import com.oceanguard.ai.ui.screens.results.reportModelStatus

/**
 * Reports: choose zone, period, language and audience, see whether the model is ready, generate,
 * follow the real steps and read or export what was written. The choices are kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    navController: NavController,
    viewModel: MainViewModel,
    preselectedLat: Double? = null,
    preselectedLon: Double? = null,
    preselectedName: String? = null,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OceanGuardApp }
    val ui = remember { ReportUiState() }
    val snackbar = remember { SnackbarHostState() }
    val sessions by viewModel.allSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    var type by rememberSaveable { mutableStateOf(ReportType.DEBRIS) }
    val observations by app.database.speciesObservationDao().getAll()
        .collectAsStateWithLifecycle(initialValue = emptyList<SpeciesObservation>())
    val zones = rememberReportZones(sessions, observations, type, preselectedLat, preselectedLon, preselectedName)
    val choices = rememberReportChoices(app.settingsRepository)
    val selection = remember(zones.selectedArea, choices.choices.period, type) {
        ReportSelection.ofArea(zones.selectedArea, choices.choices.period, type)
    }
    val boundsMap = rememberSpotlightBounds()
    val tour = rememberSpotlightController(TourDefinitions.REPORTS)
    val effects = ReportEffects(app, ui, snackbar)

    ReportDialogs(app, ui, choices, sessions)
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { ReportTopBar(onBack = { navController.popBackStack() }) },
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            ReportPage(
                app = app,
                ui = ui,
                sessions = sessions,
                hasObservations = observations.isNotEmpty(),
                type = type,
                onType = { type = it },
                zones = zones,
                choices = choices,
                selection = selection,
                effects = effects,
                boundsMap = boundsMap,
                onAddLocation = { addLocation(navController, sessions) },
                modifier = Modifier.padding(padding),
            )
        }
        ReportViewOverlay(app, ui, snackbar, selection, navController, sessions)
        ReportTour(app, navController, tour, boundsMap)
    }
}

/** Sends the person to place the first photo that has no location. */
private fun addLocation(navController: NavController, sessions: List<DetectionSession>) {
    sessions.firstOrNull { it.location == null }?.let { navController.navigate("location_picker/${it.id}") }
}

/** Everything the page needs from the app, read once and handed to the list below. */
private class ReportPageData(
    val info: GenerationInfo,
    val download: VlmDownloadState,
    val tier: TextModelTier,
    val modelStatus: ReportModelStatus,
    val savedReports: List<GeneratedReport>,
)

@Composable
private fun rememberReportPageData(app: OceanGuardApp): ReportPageData {
    // Derived, so the streamed tokens do not redraw the page: only a change of step does.
    val generationState = app.reportGenerationState.collectAsStateWithLifecycle()
    val info by remember { derivedStateOf { generationInfo(generationState.value) } }
    val download by app.vlmModelManager.downloadState.collectAsStateWithLifecycle()
    val tierKey by app.settingsRepository.vlmModelTier
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_VLM_MODEL_TIER)
    val tier = remember(tierKey) { TextModelTier.fromKey(tierKey) }
    val saved by app.database.generatedReportDao().getAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val status = reportModelStatus(app.vlmModelManager.isAnyVariantAvailable(tier), isDownloading(download))
    return ReportPageData(info, download, tier, status, saved)
}

@Composable
private fun ReportPage(
    app: OceanGuardApp,
    ui: ReportUiState,
    sessions: List<DetectionSession>,
    hasObservations: Boolean,
    type: ReportType,
    onType: (ReportType) -> Unit,
    zones: ReportZones,
    choices: ReportChoicesState,
    selection: ReportSelection,
    effects: ReportEffectsHandle,
    boundsMap: MutableMap<String, Rect>,
    onAddLocation: () -> Unit,
    modifier: Modifier,
) {
    val data = rememberReportPageData(app)
    val filter = remember { SavedReportsFilter() }
    val listState = rememberLazyListState()
    val generate = { effects.onGenerate(zones, choices.choices, selection, data.modelStatus) }
    val failed = ui.screen is ReportScreenState.Error
    val savedIndex = 2 + listOf(data.info.generating, failed).count { it }

    LaunchedEffect(ui.newReportId) { if (ui.newReportId != null) listState.animateScrollToItem(savedIndex) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ReportSetupPanel(
                zones = zones,
                hasSessions = sessions.isNotEmpty(),
                hasObservations = hasObservations,
                type = type,
                onType = onType,
                selection = selection,
                choices = choices.choices,
                actions = choices.actions,
                enabled = !data.info.generating,
                boundsMap = boundsMap,
                onAddLocation = onAddLocation,
            )
        }
        item { GenerateItem(app, data, zones, selection, boundsMap, generate) }
        runItems(app, data, failed, generate)
        savedReportsSection(
            hasSessions = sessions.isNotEmpty(),
            reports = data.savedReports,
            filter = filter,
            highlightId = ui.newReportId,
            onView = { ui.open(it) },
            onDelete = { ui.reportToDelete = it },
            onHighlightConsumed = { ui.newReportId = null },
        )
    }
    SavedReportsDatePicker(filter, data.savedReports)
}

/** The progress card while a report is being written, or the failure of the last attempt. */
private fun LazyListScope.runItems(app: OceanGuardApp, data: ReportPageData, failed: Boolean, retry: () -> Unit) {
    if (data.info.generating) {
        item { ReportProgressCard(app, data.info.stage, data.info.toolProgress, data.info.toolName) }
    }
    if (failed) item { ReportFailure(retry) }
}

@Composable
private fun GenerateItem(
    app: OceanGuardApp,
    data: ReportPageData,
    zones: ReportZones,
    selection: ReportSelection,
    boundsMap: MutableMap<String, Rect>,
    onGenerate: () -> Unit,
) {
    ReportGenerateSection(
        ui = GenerateUi(
            modelStatus = data.modelStatus,
            modelName = data.tier.displayName,
            modelSize = data.tier.sizeLabel,
            downloadProgress = downloadProgress(data.download),
            generating = data.info.generating,
            hasZone = zones.selectedZone != null,
            hasData = selection.ready,
            missingData = missingDataMessage(selection),
        ),
        onGenerate = onGenerate,
        onCancelDownload = { app.vlmModelManager.cancelDownload() },
        boundsMap = boundsMap,
    )
}

/** What is missing for a species or combined report, or null when nothing is or it is a debris report. */
private fun missingDataMessage(selection: ReportSelection): Int? = when {
    selection.ready || selection.type == ReportType.DEBRIS -> null
    selection.observations.isEmpty() -> R.string.report_no_species_data
    else -> R.string.report_no_debris_data
}

@Composable
private fun ReportFailure(onRetry: () -> Unit) {
    SonarErrorState(
        title = stringResource(R.string.report_error_title),
        message = stringResource(R.string.results_error_message),
        onRetry = onRetry,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.report_title), style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.report_cd_go_back))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}
