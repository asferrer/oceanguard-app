package com.oceanguard.ai.ui.screens

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.data.AnalysisModels
import com.oceanguard.ai.inference.AnalysisResult
import com.oceanguard.ai.inference.AnalysisState
import com.oceanguard.ai.service.InferenceServiceState
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.UiState
import com.oceanguard.ai.ui.components.AnalysisStatusPill
import com.oceanguard.ai.ui.components.analysis.AnalysisFlow
import com.oceanguard.ai.ui.components.analysis.AnalysisStatus
import com.oceanguard.ai.ui.components.analysis.AnalysisSweepStage
import com.oceanguard.ai.ui.components.analysis.SweepReveal
import com.oceanguard.ai.ui.components.analysis.rememberElapsedSeconds
import com.oceanguard.ai.ui.components.analysis.rememberSweepReveal
import com.oceanguard.ai.ui.components.sonar.SonarErrorState
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.navigation.OceanRoutes
import com.oceanguard.ai.ui.navigation.OceanSharedKeys
import com.oceanguard.ai.ui.navigation.oceanSharedElement
import com.oceanguard.ai.ui.screens.camera.AnalysisPanel
import java.util.Locale

private const val MILLIS_PER_SECOND = 1_000f

private val SIDE_PANEL_WIDTH = 320.dp
private val TOP_BAR_PADDING = 4.dp
private val STATUS_PADDING = 16.dp
private val ERROR_PADDING = 24.dp

/**
 * Landing of every analysis started from the camera: the signature moment and the result, on the
 * same photo.
 *
 * The sonar line runs for exactly as long as the pipeline works. When the boxes arrive, a last pass
 * reveals them in order; neither detector reports progress, so nothing here pretends to. The screen
 * then stays until the user picks "New scan" or "View details".
 */
@Composable
fun CameraResultScreen(
    navController: NavController,
    viewModel: MainViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val capturedImageUri by viewModel.capturedImageUri.collectAsStateWithLifecycle()
    val serviceState by viewModel.inferenceServiceState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OceanGuardApp }
    val initialMode = remember { app.settingsRepository.getAnalysisModeSync() }
    val mode by app.settingsRepository.analysisMode.collectAsStateWithLifecycle(initialMode)
    val contributing by app.settingsRepository.contributeConsentGiven
        .collectAsStateWithLifecycle(initialValue = false)

    val reveal = rememberSweepReveal(AnalysisFlow.detections(uiState))
    val result = (uiState as? UiState.AnalysisComplete)?.result
    val sessionId = (serviceState as? InferenceServiceState.SingleComplete)?.sessionId?.takeIf { it > 0 }
    val effectiveMode = remember(mode) { mode.effective(app::isModelDownloaded) }
    val exits = remember(navController, viewModel) { AnalysisExits(navController, viewModel) }
    DisposableEffect(exits) { onDispose(exits::clearAfterLeaving) }

    AnalysisLayout(
        header = { AnalysisHeader(analysisHeaderText(result, effectiveMode), exits::back) },
        stage = { stageModifier ->
            PhotoStage(
                imageUri = capturedImageUri,
                reveal = reveal,
                uiState = uiState,
                app = app,
                mode = effectiveMode,
                sessionId = sessionId,
                onRetry = { capturedImageUri?.let(exits::retry) },
                modifier = stageModifier,
            )
        },
        panel = { panelModifier ->
            AnalysisPanel(
                found = reveal.fired,
                result = result,
                contributing = contributing,
                onViewDetails = sessionId?.let { id -> { exits.details(id) } },
                onNewScan = exits::newScan,
                modifier = panelModifier,
            )
        },
    )
}

/**
 * Everything that leaves this analysis. It is cleared once the screen is gone, not before: the
 * photo and its boxes must stay visible during the transition, and the photo may fly to the detail.
 */
private class AnalysisExits(
    private val navController: NavController,
    private val viewModel: MainViewModel,
) {
    private var leaving = false

    fun clearAfterLeaving() {
        if (leaving) viewModel.resetState()
    }

    fun back() {
        leaving = true
        navController.popBackStack()
    }

    fun retry(photo: Uri) {
        viewModel.resetState()
        viewModel.analyzeImage(photo)
    }

    fun newScan() {
        leaving = true
        navController.navigate(OceanRoutes.CAMERA) {
            popUpTo(OceanRoutes.HOME) { inclusive = false }
        }
    }

    fun details(sessionId: Long) {
        leaving = true
        navController.navigate("session/$sessionId") {
            popUpTo(OceanRoutes.HOME) { inclusive = false }
        }
    }
}

/** Photo above the panel in portrait, next to it in landscape, where height is short. */
@Composable
private fun AnalysisLayout(
    header: @Composable () -> Unit,
    stage: @Composable (Modifier) -> Unit,
    panel: @Composable (Modifier) -> Unit,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        header()
        if (isLandscape) {
            Row(Modifier.fillMaxSize()) {
                stage(Modifier.weight(1f).fillMaxHeight())
                panel(
                    Modifier
                        .width(SIDE_PANEL_WIDTH)
                        .verticalScroll(rememberScrollState()),
                )
            }
        } else {
            stage(Modifier.weight(1f).fillMaxWidth())
            panel(Modifier)
        }
    }
}

@Composable
private fun analysisHeaderText(result: AnalysisResult?, mode: AnalysisMode): String =
    if (result == null) {
        stringResource(mode.labelRes)
    } else {
        stringResource(
            R.string.sonar_analysis_done_meta,
            result.modelName,
            String.format(Locale.getDefault(), "%.1f", result.processingTimeMs / MILLIS_PER_SECOND),
        )
    }

@Composable
private fun AnalysisHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(TOP_BAR_PADDING),
        horizontalArrangement = Arrangement.spacedBy(TOP_BAR_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_go_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PhotoStage(
    imageUri: Uri?,
    reveal: SweepReveal,
    uiState: UiState,
    app: OceanGuardApp,
    mode: AnalysisMode,
    sessionId: Long?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = AnalysisFlow.isRunning(uiState)
    val result = (uiState as? UiState.AnalysisComplete)?.result
    val modelName = result?.modelName ?: app.activeDetectorModelName()
    val hasConfidence = AnalysisModels.hasModelConfidence(modelName)
    Box(modifier = modifier) {
        AnalysisSweepStage(
            imageUri = imageUri,
            reveal = reveal,
            running = running,
            sweepPeriodMs = AnalysisFlow.sweepPeriodMs(mode),
            showConfidence = hasConfidence,
            modifier = Modifier.fillMaxSize(),
            imageModifier = sessionId?.let {
                Modifier.oceanSharedElement(OceanSharedKeys.sessionImage(it))
            } ?: Modifier,
        )
        AnalysisStatusOverlay(uiState, app, gemmaDetector = !hasConfidence)
        if (uiState is UiState.Error) AnalysisErrorOverlay(onRetry)
    }
}

/** Says what is happening and for how long: the screen shows no estimate it cannot back. */
@Composable
private fun BoxScope.AnalysisStatusOverlay(
    uiState: UiState,
    app: OceanGuardApp,
    gemmaDetector: Boolean,
) {
    val pipelineState by app.detectionOrchestrator.analysisState.collectAsStateWithLifecycle()
    val running = AnalysisFlow.isRunning(uiState)
    val elapsed = rememberElapsedSeconds(running)
    val warming = pipelineState is AnalysisState.WarmingUpModel
    AnimatedVisibility(
        visible = running,
        enter = fadeIn(OceanMotion.fast()),
        exit = fadeOut(OceanMotion.exit()),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(STATUS_PADDING),
    ) {
        AnalysisStatusPill(
            statusText = statusText(AnalysisFlow.status(uiState, warming, gemmaDetector)),
            subText = stringResource(R.string.results_status_elapsed, elapsed).takeIf { elapsed > 0 },
        )
    }
}

@Composable
private fun BoxScope.AnalysisErrorOverlay(onRetry: () -> Unit) {
    Surface(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(ERROR_PADDING),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        SonarErrorState(
            title = stringResource(R.string.results_error_title),
            message = stringResource(R.string.sonar_analysis_error_message),
            onRetry = onRetry,
        )
    }
}

@Composable
private fun statusText(status: AnalysisStatus): String = stringResource(
    when (status) {
        AnalysisStatus.LOADING_IMAGE, AnalysisStatus.FINISHED -> R.string.results_status_preparing
        AnalysisStatus.WARMING_MODEL -> R.string.sonar_analysis_status_warming
        AnalysisStatus.DETECTING -> R.string.results_status_detecting
        AnalysisStatus.GEMMA -> R.string.results_status_detecting_gemma4
        AnalysisStatus.REFINING -> R.string.sonar_analysis_status_refining
    },
)
