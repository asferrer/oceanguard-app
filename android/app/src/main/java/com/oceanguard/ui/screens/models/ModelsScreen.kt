package com.oceanguard.ai.ui.screens.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.data.AnalysisModels
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.inference.TextModelVariant
import com.oceanguard.ai.inference.VlmDownloadState
import com.oceanguard.ai.inference.VlmModelManager
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.sonar.AdvancedOptionsToggle
import com.oceanguard.ai.ui.components.sonar.UndoableDeletes
import com.oceanguard.ai.ui.components.sonar.rememberUndoableDeletes
import kotlinx.coroutines.launch

private val SCREEN_PADDING = 16.dp
private val SECTION_GAP = 24.dp

/**
 * Models & AI: what is loaded now, the four analysis modes, what is installed (with size and
 * deletion), more downloads and, behind "Show advanced options", the technical settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(navController: NavController, viewModel: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val controller = rememberModelsController(snackbar)
    val deletes = rememberUndoableDeletes<CatalogModel>(snackbar) { controller.commitDelete(it) }
    val downloadState by controller.app.vlmModelManager.downloadState.collectAsStateWithLifecycle()
    LaunchedEffect(downloadState) { controller.onDownloadState(downloadState) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.models_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        ModelsContent(controller, deletes, downloadState, Modifier.padding(padding))
    }
    controller.downloader.PreflightDialog()
}

@Composable
private fun ModelsContent(
    controller: ModelsController,
    deletes: UndoableDeletes<CatalogModel>,
    downloadState: VlmDownloadState,
    modifier: Modifier,
) {
    val app = controller.app
    val data = rememberModelsData(controller, deletes)
    val owner = downloadOwner(
        downloadState,
        controller.downloader.requested,
        app.vlmModelManager.finetunedFilename(),
        app.vlmModelManager.speciesFileNames,
    )
    val ui = DownloadUi(downloadState, owner)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        InUseSection(data.saved, data.effective, data.rings, data.load, data.tasks)
        ModeSection(
            saved = data.saved,
            availability = data.availability,
            ui = ui,
            onSelect = controller::select,
            onDownload = controller::download,
            onCancel = controller::cancelDownload,
        )
        if (downloadState is VlmDownloadState.Error) {
            DownloadErrorCard(downloadState, controller::retry, controller::dismissError)
        }
        InstalledSection(
            models = data.installed,
            sizes = data.sizes,
            freeBytes = data.freeBytes,
            deletes = deletes,
            onDelete = { controller.requestDelete(it, deletes) },
        )
        MoreDownloadsSection(data.downloadable, ui, controller::download, controller::cancelDownload)
        AdvancedBlock(controller, ui)
    }
}

@Composable
private fun AdvancedBlock(controller: ModelsController, ui: DownloadUi) {
    val app = controller.app
    val scope = rememberCoroutineScope()
    val enabled by app.settingsRepository.devModeEnabled.collectAsStateWithLifecycle(initialValue = false)
    AdvancedOptionsToggle(
        enabled = enabled,
        onEnabledChange = { scope.launch { app.settingsRepository.setDevModeEnabled(it) } },
    )
    if (enabled) {
        AdvancedSection(app, scope)
        BenchmarksSection(app, controller.downloader, ui)
    }
}

/** Everything the sections show, read from the files and the engines as they are right now. */
private data class ModelsData(
    val saved: AnalysisMode,
    val effective: AnalysisMode,
    val availability: (AnalysisMode) -> com.oceanguard.ai.data.AnalysisModeAvailability,
    val rings: List<Ring>,
    val load: LoadState,
    val tasks: List<TaskUse>,
    val installed: List<CatalogModel>,
    val sizes: Map<CatalogModel, Long>,
    val downloadable: List<CatalogModel>,
    val freeBytes: Long,
)

@Composable
private fun rememberModelsData(controller: ModelsController, deletes: UndoableDeletes<CatalogModel>): ModelsData {
    val app = controller.app
    val manager = app.vlmModelManager
    val settings = app.settingsRepository
    val saved by settings.analysisMode.collectAsStateWithLifecycle(settings.getAnalysisModeSync())
    val advanced by settings.devModeEnabled.collectAsStateWithLifecycle(initialValue = false)
    val loading by app.modelLoadingState.collectAsStateWithLifecycle()
    val onDisk = remember(controller.revision) {
        CatalogModel.entries.filter(manager::isInstalled).associateWith(manager::sizeOnDisk)
    }
    val installedSet = onDisk.keys
    val isDownloaded: (TextModelTier, TextModelVariant) -> Boolean =
        { tier, variant -> installedSet.any { it.tier == tier && it.variant == variant } }
    val published = VlmModelManager.FINETUNED_LITERTLM_PUBLISHED
    val effective = saved.effective(isDownloaded, published)
    val visible = onDisk.filterKeys { !deletes.isPending(it) }
    return ModelsData(
        saved = saved,
        effective = effective,
        availability = { it.availability(isDownloaded, published) },
        rings = ringStates(effective, isDownloaded, published),
        load = detectorLoadState(effective, loading),
        tasks = taskUses(effective, reportModelName(app, installedSet), speciesReady(installedSet)),
        installed = visible.keys.toList(),
        sizes = visible,
        downloadable = splitCatalog({ it in installedSet }, advanced).downloadable,
        freeBytes = manager.getModelDirectory().usableSpace,
    )
}

private fun speciesReady(installed: Set<CatalogModel>): Boolean =
    CatalogModel.SPECIES_PACK in installed &&
        (CatalogModel.GEMMA_BASE in installed || CatalogModel.GEMMA_TUNED in installed)

/** The model that would write the next report, by name; null when none is installed. */
private fun reportModelName(app: OceanGuardApp, installed: Set<CatalogModel>): String? {
    if (installed.none { it.tier != null }) return null
    val manager = app.vlmModelManager
    val tier = app.getBestAvailableTier()
    val wanted = TextModelVariant.fromKey(app.settingsRepository.getVlmModelVariantSync())
    val variant = tier.variantFallback(wanted).takeIf { manager.isModelAvailable(tier, it) }
        ?: TextModelVariant.BASE
    return AnalysisModels.forReportTier(tier, manager.getTextModelPath(tier, variant))
}
