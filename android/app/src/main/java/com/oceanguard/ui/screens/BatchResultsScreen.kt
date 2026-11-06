package com.oceanguard.ai.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.UiState
import com.oceanguard.ai.ui.components.spotlight.SpotlightController
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.components.sonar.SonarEmptyState
import com.oceanguard.ai.ui.components.sonar.SonarErrorState
import com.oceanguard.ai.ui.screens.results.BatchGridContent
import com.oceanguard.ai.ui.screens.results.BatchGridState
import com.oceanguard.ai.ui.screens.results.BatchRun
import com.oceanguard.ai.ui.screens.results.ContributionPrompts
import com.oceanguard.ai.ui.screens.results.rememberBatchRun
import com.oceanguard.ai.ui.screens.results.summarizeBatch
import com.oceanguard.ai.ui.screens.results.toOutcome
import kotlinx.coroutines.launch

/**
 * Analyses [uriList] one photo at a time through the analysis service and shows the photos as they
 * finish. When all are done a summary appears above the grid, which stays open for review.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchResultsScreen(
    navController: NavController,
    viewModel: MainViewModel,
    uriList: List<Uri> = emptyList(),
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as OceanGuardApp }
    val boundsMap = rememberSpotlightBounds()
    val snackbarHostState = remember { SnackbarHostState() }
    val run = rememberBatchRun(uriList, viewModel, app)
    val tour = rememberSpotlightController(TourDefinitions.BATCH)
    BatchTourStart(app, tour)

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.batch_title), style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = { leaveBatch(viewModel, navController) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_go_back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            BatchBody(navController, viewModel, run, boundsMap, padding)
        }
        BatchSpotlight(app, tour, boundsMap)
        ContributionPrompts(viewModel, app, snackbarHostState)
    }
}

private fun leaveBatch(viewModel: MainViewModel, navController: NavController) {
    viewModel.resetState()
    navController.popBackStack()
}

@Composable
private fun BatchTourStart(app: OceanGuardApp, tour: SpotlightController) {
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.BATCH))
        .collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(tourComplete) { if (!tourComplete) tour.start() }
}

@Composable
private fun BatchSpotlight(app: OceanGuardApp, tour: SpotlightController, boundsMap: MutableMap<String, Rect>) {
    val scope = rememberCoroutineScope()
    SpotlightOverlay(
        controller = tour,
        targetBounds = boundsMap,
        onComplete = {
            scope.launch {
                app.settingsRepository.markTourComplete(TourDefinitions.getScreenId(TourDefinitions.BATCH))
            }
        },
    )
}

@Composable
private fun BatchBody(
    navController: NavController,
    viewModel: MainViewModel,
    run: BatchRun,
    boundsMap: MutableMap<String, Rect>,
    padding: PaddingValues,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val modifier = Modifier.padding(padding)
    val failedBeforeStart = uiState is UiState.Error && !run.running && run.completed == 0
    when {
        run.items.isEmpty() -> SonarEmptyState(
            icon = Icons.Filled.BrokenImage,
            title = stringResource(R.string.batch_empty_title),
            message = stringResource(R.string.batch_empty_message),
            modifier = modifier,
            actionLabel = stringResource(R.string.batch_btn_back),
            onAction = { navController.popBackStack() },
        )
        failedBeforeStart -> BatchError(viewModel, navController, run, modifier)
        else -> BatchGridContent(
            state = BatchGridState(
                items = run.items,
                currentIndex = run.currentIndex,
                stats = if (run.finished) summarizeBatch(run.items.map { it.toOutcome() }, run.durationMs) else null,
            ),
            boundsMap = boundsMap,
            onNewBatch = {
                viewModel.resetState()
                navController.navigate("home") { popUpTo("home") { inclusive = true } }
            },
            onViewHistory = { navController.navigate("history") },
            onViewMap = { navController.navigate("map") },
            onItemClick = { navController.navigate("session/$it") },
            modifier = modifier,
        )
    }
}

@Composable
private fun BatchError(viewModel: MainViewModel, navController: NavController, run: BatchRun, modifier: Modifier) {
    Column(modifier) {
        SonarErrorState(
            title = stringResource(R.string.batch_error_title),
            message = stringResource(R.string.results_error_message),
            onRetry = {
                viewModel.resetState()
                run.resetToPending()
            },
        )
        SonarButton(
            text = stringResource(R.string.batch_btn_back),
            onClick = { leaveBatch(viewModel, navController) },
            style = SonarButtonStyle.Quiet,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}
