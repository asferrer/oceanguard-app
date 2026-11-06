package com.oceanguard.ai.ui.screens

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.inference.LiveDetectionManager
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.ZoomSlider
import com.oceanguard.ai.ui.components.pinchToZoom
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.screens.camera.CameraPermissionScreen
import com.oceanguard.ai.ui.screens.camera.CameraScreenTour
import com.oceanguard.ai.ui.screens.camera.CaptureDock
import com.oceanguard.ai.ui.screens.camera.CaptureHint
import com.oceanguard.ai.ui.screens.camera.CaptureMode
import com.oceanguard.ai.ui.screens.camera.CaptureModeToggle
import com.oceanguard.ai.ui.screens.camera.DisplayRotationEffect
import com.oceanguard.ai.ui.screens.camera.LiveDetectionOverlay
import com.oceanguard.ai.ui.screens.camera.LiveHud
import com.oceanguard.ai.ui.screens.camera.ShutterButton
import com.oceanguard.ai.ui.screens.camera.StillCaptureState
import com.oceanguard.ai.ui.screens.camera.VolumeShutterEffect
import com.oceanguard.ai.ui.screens.camera.buildLiveAnalysis
import com.oceanguard.ai.ui.screens.camera.createViewfinder
import com.oceanguard.ai.ui.screens.camera.openCameraAnalysis
import com.oceanguard.ai.ui.screens.camera.rememberStillCapture
import com.oceanguard.ai.ui.screens.camera.submitLiveFrame
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

private const val LIVE_FILE_PREFIX = "OceanGuard_Live"
private const val DEFAULT_MAX_ZOOM = 10f
private const val LANDSCAPE_SLIDER_WIDTH = 0.6f

private val LANDSCAPE_SLIDER_BOTTOM = 12.dp

/**
 * Continuous scan: the preview runs at full rate while RT-DETRv2 analyses frames in the background,
 * one pass every few seconds. The boxes of a pass stay until the next one replaces them, the real
 * cadence is on screen, and the shutter runs the full analysis of the chosen mode. Only RT-DETRv2
 * runs here: Gemma would take far too long per frame.
 *
 * Back returns to the photo mode of the camera, which stays below on the stack. It is intercepted
 * so that the predictive gesture does not compose the camera underneath: binding its viewfinder
 * would take CameraX from this screen, and a cancelled gesture would leave a frozen preview.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun LiveDetectionScreen(
    navController: NavController,
    viewModel: MainViewModel,
) {
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    val boundsMap = rememberSpotlightBounds()
    val backToCamera: () -> Unit = { navController.popBackStack() }
    BackHandler(onBack = backToCamera)

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            LiveDetectionContent(navController, viewModel, boundsMap, backToCamera)
        } else {
            CameraPermissionScreen(
                shouldShowRationale = cameraPermissionState.status.shouldShowRationale,
                onRequestPermission = { cameraPermissionState.launchPermissionRequest() },
                onBack = backToCamera,
            )
        }
        CameraScreenTour(TourDefinitions.LIVE_DETECTION, boundsMap)
    }
}

/** Zoom of the bound camera, driven by the pinch and by the slider. */
private class LiveZoom {
    var ratio by mutableFloatStateOf(1f)
        private set
    var maxRatio by mutableFloatStateOf(DEFAULT_MAX_ZOOM)
        private set
    private var control: CameraControl? = null

    fun attach(camera: Camera) {
        control = camera.cameraControl
        maxRatio = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: DEFAULT_MAX_ZOOM
    }

    fun set(newRatio: Float) {
        ratio = newRatio
        control?.setZoomRatio(newRatio)
    }
}

@Composable
private fun rememberLiveManager(app: OceanGuardApp): LiveDetectionManager {
    val context = LocalContext.current
    val threshold by app.settingsRepository.confidenceThreshold.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_CONFIDENCE_THRESHOLD,
    )
    val manager = remember {
        LiveDetectionManager(
            detector = app.rtdetrInference,
            context = context,
            repository = app.repository,
            locationProvider = app.locationProvider,
        )
    }
    LaunchedEffect(threshold) { manager.confidenceThreshold = threshold }

    // Runs only with the screen visible: time in the background is not cadence.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, manager) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> manager.start()
                Lifecycle.Event.ON_STOP -> manager.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            manager.stop()
        }
    }
    return manager
}

@Composable
private fun LiveDetectionContent(
    navController: NavController,
    viewModel: MainViewModel,
    boundsMap: MutableMap<String, Rect>,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as OceanGuardApp
    val manager = rememberLiveManager(app)
    val state by manager.state.collectAsStateWithLifecycle()
    val capture = rememberStillCapture()
    val zoom = remember { LiveZoom() }
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }

    val fullAnalysis: () -> Unit = {
        if (!opening) {
            manager.stop()
            capture.capture(LIVE_FILE_PREFIX, onFailure = { manager.start() }) { uri ->
                opening = true
                scope.launch { openCameraAnalysis(navController, viewModel, uri) }
            }
        }
    }
    VolumeShutterEffect(fullAnalysis)
    FirstScanAchievementEffect(hasScanned = state.frameCount > 0, app)

    Box(Modifier.fillMaxSize().pinchToZoom(zoom.ratio, zoom.maxRatio, zoom::set)) {
        LiveCameraPreview(manager, capture.imageCapture, boundsMap, zoom::attach)
        LiveDetectionOverlay(
            detections = state.detections,
            frameSize = Size(state.frameWidth.toFloat(), state.frameHeight.toFloat()),
            isProcessing = state.isProcessing,
            modifier = Modifier.fillMaxSize(),
        )
        LiveHud(
            state = state,
            modelName = app.rtdetrInference.modelName,
            onBack = onBack,
            boundsMap = boundsMap,
        )
        LiveControls(zoom, capture, opening, boundsMap, onBack, fullAnalysis)
    }
}

/** One real pass is what "using the continuous scan" means for the Eagle Eye achievement. */
@Composable
private fun FirstScanAchievementEffect(hasScanned: Boolean, app: OceanGuardApp) {
    LaunchedEffect(hasScanned) {
        if (hasScanned) app.achievementChecker.checkFirstLive()
    }
}

@Composable
private fun BoxScope.LiveControls(
    zoom: LiveZoom,
    capture: StillCaptureState,
    opening: Boolean,
    boundsMap: MutableMap<String, Rect>,
    onPhotoMode: () -> Unit,
    onFullAnalysis: () -> Unit,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val slider: @Composable (Modifier) -> Unit = { placement ->
        ZoomSlider(
            zoomRatio = zoom.ratio,
            maxZoomRatio = zoom.maxRatio,
            onZoomChanged = zoom::set,
            modifier = placement,
        )
    }
    if (isLandscape) {
        slider(
            Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .fillMaxWidth(LANDSCAPE_SLIDER_WIDTH)
                .padding(bottom = LANDSCAPE_SLIDER_BOTTOM),
        )
    }
    CaptureDock(isLandscape) {
        if (!isLandscape) slider(Modifier)
        CaptureModeToggle(
            selected = CaptureMode.CONTINUOUS,
            continuousEnabled = true,
            onSelect = { if (it == CaptureMode.PHOTO) onPhotoMode() },
        )
        ShutterButton(
            isCapturing = capture.isCapturing || opening,
            onClick = onFullAnalysis,
            contentDescription = stringResource(R.string.cd_capture_and_analyze),
            modifier = Modifier.spotlightTarget("live_capture", boundsMap),
        )
        CaptureHint(
            failed = capture.failed,
            fallback = stringResource(R.string.sonar_live_full_analysis),
        )
    }
}

@Composable
private fun LiveCameraPreview(
    manager: LiveDetectionManager,
    imageCapture: ImageCapture,
    boundsMap: MutableMap<String, Rect>,
    onCameraReady: (Camera) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val analysis = remember { buildLiveAnalysis() }
    DisposableEffect(analysis) {
        val executor = Executors.newSingleThreadExecutor()
        analysis.setAnalyzer(executor) { proxy -> submitLiveFrame(proxy, manager, scope) }
        onDispose {
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }
    DisplayRotationEffect(analysis)

    AndroidView(
        factory = { context ->
            createViewfinder(
                context = context,
                lifecycleOwner = lifecycleOwner,
                useCases = listOf(analysis, imageCapture),
                onBound = onCameraReady,
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .spotlightTarget("live_viewfinder", boundsMap),
    )
}
