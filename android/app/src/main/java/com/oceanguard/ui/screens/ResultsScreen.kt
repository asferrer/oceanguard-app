package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.inference.DetectorType
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.UiState
import com.oceanguard.ai.ui.components.AnalysisProgressIndicator
import com.oceanguard.ai.ui.components.spotlight.SpotlightController
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.screens.results.ContributionPrompts
import com.oceanguard.ai.ui.screens.results.FullResultContent
import com.oceanguard.ai.ui.screens.results.LoadingContent
import com.oceanguard.ai.ui.screens.results.PartialResultContent
import com.oceanguard.ai.ui.screens.results.ResultsErrorContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.concurrent.TimeUnit

/** Result of the photo just analysed: progress while it runs, then the full result. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    navController: NavController,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OceanGuardApp }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val boundsMap = rememberSpotlightBounds()
    val tour = rememberSpotlightController(TourDefinitions.RESULTS)
    ResultsTourStart(app, viewModel, tour)

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.results_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_go_back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            ResultsBody(navController, viewModel, app, boundsMap, listState, padding)
        }
        ResultsSpotlight(app, tour, boundsMap, listState)
        ContributionPrompts(viewModel, app, snackbarHostState)
    }
}

/** Starts the first-use tour once there is a result to point at. */
@Composable
private fun ResultsTourStart(app: OceanGuardApp, viewModel: MainViewModel, tour: SpotlightController) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.RESULTS))
        .collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(tourComplete, uiState) {
        if (!tourComplete && uiState is UiState.AnalysisComplete) tour.start()
    }
}

@Composable
private fun ResultsSpotlight(
    app: OceanGuardApp,
    tour: SpotlightController,
    boundsMap: MutableMap<String, Rect>,
    listState: LazyListState,
) {
    val scope = rememberCoroutineScope()
    SpotlightOverlay(
        controller = tour,
        targetBounds = boundsMap,
        onComplete = {
            scope.launch {
                app.settingsRepository.markTourComplete(TourDefinitions.getScreenId(TourDefinitions.RESULTS))
            }
        },
        onScrollToTarget = { targetId ->
            val index = when (targetId) {
                "results_image" -> 0
                "results_health" -> 1
                "results_actions" -> listState.layoutInfo.totalItemsCount - 1
                else -> null
            }
            if (index != null && index >= 0) listState.animateScrollToItem(index)
        },
    )
}

@Composable
private fun ResultsBody(
    navController: NavController,
    viewModel: MainViewModel,
    app: OceanGuardApp,
    boundsMap: MutableMap<String, Rect>,
    listState: LazyListState,
    padding: PaddingValues,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val imageUri by viewModel.capturedImageUri.collectAsStateWithLifecycle()
    val annotatedUri by viewModel.annotatedThumbnailUri.collectAsStateWithLifecycle()
    val detectorKey by app.settingsRepository.detectorMode
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_DETECTOR_MODE)
    val isGemma = detectorKey == DetectorType.GEMMA4_VISION.key
    val modifier = Modifier.padding(padding)

    when (val state = uiState) {
        is UiState.Detecting, is UiState.ModelLoading ->
            DetectingBody(state, isGemma, modifier)
        is UiState.DetectionsReady, is UiState.AnalyzingDeep -> PartialBody(state, isGemma, imageUri, modifier)
        is UiState.AnalysisComplete -> CompleteBody(
            navController, viewModel, state, imageUri, annotatedUri, boundsMap, listState, modifier,
        )
        is UiState.Error -> ResultsErrorContent(
            onRetry = {
                imageUri?.let { viewModel.analyzeImage(it) } ?: run {
                    viewModel.resetState()
                    navController.popBackStack()
                }
            },
            onBack = {
                viewModel.resetState()
                navController.popBackStack()
            },
            modifier = modifier,
        )
        is UiState.Idle -> LoadingContent(
            message = stringResource(R.string.results_status_preparing),
            subMessage = stringResource(R.string.results_status_please_wait),
            modifier = modifier,
        )
    }
}

@Composable
private fun DetectingBody(state: UiState, isGemma: Boolean, modifier: Modifier) {
    val message = when {
        state is UiState.ModelLoading -> state.progress
        isGemma -> stringResource(R.string.results_status_detecting_gemma4)
        else -> stringResource(R.string.results_status_detecting)
    }
    // A visible clock tells the person the app is alive during the long Gemma pass.
    var elapsedSec by remember(state) { mutableIntStateOf(0) }
    LaunchedEffect(state) {
        while (true) {
            delay(1_000)
            elapsedSec += 1
        }
    }
    LoadingContent(
        message = message,
        subMessage = stringResource(
            if (isGemma) R.string.results_status_gemma4_subtitle else R.string.results_status_fast_detection,
        ),
        modifier = modifier,
        extraLine = if (isGemma && elapsedSec > 0) stringResource(R.string.results_status_elapsed, elapsedSec) else null,
        topSlot = {
            AnalysisProgressIndicator(
                state = state,
                isDeepAnalysis = isGemma,
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
            )
        },
    )
}

@Composable
private fun PartialBody(state: UiState, isGemma: Boolean, imageUri: android.net.Uri?, modifier: Modifier) {
    val detections = when (state) {
        is UiState.DetectionsReady -> state.detections
        is UiState.AnalyzingDeep -> state.detections
        else -> emptyList()
    }
    val status = stringResource(
        if (state is UiState.AnalyzingDeep) R.string.results_status_generating else R.string.results_status_deep_analysis,
    )
    androidx.compose.foundation.layout.Column(modifier.fillMaxSize()) {
        AnalysisProgressIndicator(
            state = state,
            isDeepAnalysis = isGemma,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
        PartialResultContent(
            imageUri = imageUri,
            detections = detections,
            statusMessage = status,
            showConfidence = !isGemma,
        )
    }
}

@Composable
private fun CompleteBody(
    navController: NavController,
    viewModel: MainViewModel,
    state: UiState.AnalysisComplete,
    imageUri: android.net.Uri?,
    annotatedUri: String?,
    boundsMap: MutableMap<String, Rect>,
    listState: LazyListState,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    // The analysis service saves the session before it reports completion, so it is already saved.
    var saved by remember { mutableStateOf(true) }
    // A photo with nothing detected has no health to celebrate.
    val celebrate = state.result.totalDebrisCount > 0 && state.result.healthScore >= 80

    Box(modifier) {
        FullResultContent(
            imageUri = imageUri,
            annotatedThumbnailUri = annotatedUri,
            result = state.result,
            sessionSaved = saved,
            boundsMap = boundsMap,
            listState = listState,
            onSave = {
                scope.launch {
                    val uri = imageUri?.toString().orEmpty()
                    viewModel.saveSession(uri, state.result, viewModel.resolveLocation(uri))
                    saved = true
                }
            },
            onNewScan = {
                viewModel.resetState()
                navController.navigate("home") { popUpTo("home") { inclusive = true } }
            },
        )
        if (celebrate && !LocalReducedMotion.current) Celebration()
    }
}

/** Short burst of confetti in the theme's own colours. */
@Composable
private fun Celebration() {
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.primaryContainer).map { it.toArgb() }
    KonfettiView(
        modifier = Modifier.fillMaxSize(),
        parties = listOf(
            Party(
                speed = 0f,
                maxSpeed = 30f,
                damping = 0.9f,
                spread = 360,
                colors = colors,
                emitter = Emitter(duration = 2, TimeUnit.SECONDS).perSecond(50),
                position = Position.Relative(0.5, 0.0),
            ),
        ),
    )
}
