package com.oceanguard.ai.ui.screens

import android.net.Uri
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.data.collection.ALL_ACHIEVEMENTS
import com.oceanguard.ai.data.collection.MarineDexEntry
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.navigation.LocalCameraReveal
import com.oceanguard.ai.ui.screens.models.ModelDownloader
import com.oceanguard.ai.ui.screens.models.rememberModelDownloader
import com.oceanguard.ai.ui.screens.home.HomeActions
import com.oceanguard.ai.ui.screens.home.HomeCollectionsBlock
import com.oceanguard.ai.ui.screens.home.HomeHeader
import com.oceanguard.ai.ui.screens.home.HomeHub
import com.oceanguard.ai.ui.screens.home.HomeLogic
import com.oceanguard.ai.ui.screens.home.HomeModelUi
import com.oceanguard.ai.ui.screens.home.HomeStatsBlock
import com.oceanguard.ai.ui.screens.home.HomeStatusBlock
import com.oceanguard.ai.ui.screens.home.reportCatalogModel
import com.oceanguard.ai.ui.screens.home.HomeTopLists
import com.oceanguard.ai.ui.screens.home.HomeTourOverlay
import com.oceanguard.ai.ui.screens.home.HomeTourStart
import com.oceanguard.ai.ui.screens.home.HomeUpdateDialogs
import com.oceanguard.ai.ui.screens.home.ModeDownloadDialog
import com.oceanguard.ai.ui.screens.home.PhotoSource
import com.oceanguard.ai.ui.screens.home.ScanTarget
import com.oceanguard.ai.ui.screens.home.rememberHomeModel
import com.oceanguard.ai.ui.screens.home.rememberHomePickers
import com.oceanguard.ai.ui.screens.home.rememberLocationResolved

private val CONTENT_MAX_WIDTH = 600.dp
private val GUTTER = 20.dp

/**
 * Landing screen. Choose debris or species, scan with the radar button or pick photos, see which
 * model is really loaded, your figures and your collections.
 *
 * The first-run tour starts here once the location prompt is over; the four stops are the
 * `home_scan_button`, `home_model_status`, `home_stats` and `home_marinedex` targets.
 */
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: MainViewModel,
    dexEntries: List<MarineDexEntry> = emptyList(),
    discoveredCount: Int = 0,
    achievementsUnlocked: Int = 0,
    onSpeciesBatchPicked: (List<Uri>) -> Unit = {},
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OceanGuardApp }
    val model = rememberHomeModel(app)
    val bounds = rememberSpotlightBounds()
    val tour = rememberSpotlightController(TourDefinitions.HOME)
    val scrollState = rememberScrollState()
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.HOME))
        .collectAsStateWithLifecycle(initialValue = true)
    var downloadPrompt by remember { mutableStateOf<AnalysisMode?>(null) }
    var target by rememberSaveable { mutableStateOf(ScanTarget.DEBRIS) }

    HomeTourStart(tour, tourComplete, rememberLocationResolved())
    HomePrewarm(app, model)
    val downloader = rememberModelDownloader()
    val actions = rememberHomeActions(app, navController, viewModel, model, downloader, onSpeciesBatchPicked) {
        downloadPrompt = it
    }

    Box(Modifier.fillMaxSize()) {
        HomeScaffold(scrollState) {
            HomeHeader(achievementsUnlocked, ALL_ACHIEVEMENTS.size, onAchievements = { navController.navigate("achievements") })
            HomeHub(target, { target = it }, model, bounds, actions)
            HomeStatusBlock(app, model, bounds, target) { reportCatalogModel(app)?.let(downloader::request) }
            HomeStatsBlock(viewModel, bounds)
            HomeCollectionsBlock(
                app, dexEntries, discoveredCount, bounds, navController,
            )
            HomeTopLists(viewModel, navController)
        }
        HomeTourOverlay(app, tour, bounds, scrollState, navController)
        HomeUpdateDialogs(app, HomeLogic.mayShowUpdateDialogs(tourComplete, tour.isActive))
        HomeDownloadPrompt(downloader, downloadPrompt) { downloadPrompt = null }
        downloader.PreflightDialog()
    }
}

/** Asks before downloading the model of the chosen mode, naming its real size. */
@Composable
private fun HomeDownloadPrompt(downloader: ModelDownloader, mode: AnalysisMode?, onClose: () -> Unit) {
    if (mode == null) return
    ModeDownloadDialog(
        mode = mode,
        onConfirm = {
            onClose()
            downloader.request(mode)
        },
        onDismiss = onClose,
    )
}

/** Scrolling column with the content width capped, so tablets do not stretch the cards. */
@Composable
private fun HomeScaffold(scrollState: ScrollState, content: @Composable () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).padding(horizontal = GUTTER),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Loads the Gemma engine as soon as it is on disk, so the first scan does not freeze the camera. */
@Composable
private fun HomePrewarm(app: OceanGuardApp, model: HomeModelUi) {
    val engines by app.modelLoadingState.collectAsStateWithLifecycle()
    LaunchedEffect(model.mode, model.availability, engines.gemma4Vision) {
        if (HomeLogic.shouldPrewarmGemma(model.mode, model.availability, engines.gemma4Vision)) {
            app.prewarmGemma4DetectorIfAvailable()
        }
    }
}

@Composable
private fun rememberHomeActions(
    app: OceanGuardApp,
    navController: NavController,
    viewModel: MainViewModel,
    model: HomeModelUi,
    downloader: ModelDownloader,
    onSpeciesBatchPicked: (List<Uri>) -> Unit,
    onDownloadPrompt: (AnalysisMode) -> Unit,
): HomeActions {
    val pickers = rememberHomePickers(viewModel, navController, onSpeciesBatchPicked)
    val reveal = LocalCameraReveal.current
    val current by rememberUpdatedState(model)
    return remember(pickers, reveal, navController, app, downloader) {
        HomeActions(
            scan = { target, center ->
                if (HomeLogic.needsDownloadFirst(target, current.kind)) {
                    onDownloadPrompt(current.mode)
                } else {
                    val route = if (target == ScanTarget.SPECIES) "camera?mode=species" else "camera?mode=debris"
                    val open = { navController.navigate(route) }
                    reveal?.launch(center) { open() } ?: open()
                }
            },
            pick = { target, source ->
                if (HomeLogic.needsDownloadFirst(target, current.kind) && source != PhotoSource.SPECIES) {
                    onDownloadPrompt(current.mode)
                } else {
                    pickers.pick(source)
                }
            },
            download = downloader::request,
        )
    }
}
