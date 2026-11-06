package com.oceanguard.ai.ui.screens

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.oceanguard.ai.ModelStatus
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.sonar.AnalysisModePicker
import com.oceanguard.ai.ui.screens.models.rememberModelDownloader
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.navigation.OceanRoutes
import com.oceanguard.ai.ui.screens.camera.CameraCircleButton
import com.oceanguard.ai.ui.screens.camera.CameraPermissionScreen
import com.oceanguard.ai.ui.screens.camera.CameraPill
import com.oceanguard.ai.ui.screens.camera.CameraScreenTour
import com.oceanguard.ai.ui.screens.camera.CaptureDock
import com.oceanguard.ai.ui.screens.camera.CaptureHint
import com.oceanguard.ai.ui.screens.camera.CaptureMode
import com.oceanguard.ai.ui.screens.camera.CaptureModeToggle
import com.oceanguard.ai.ui.screens.camera.CapturePreviewOverlay
import com.oceanguard.ai.ui.screens.camera.RuleOfThirdsGrid
import com.oceanguard.ai.ui.screens.camera.ShutterButton
import com.oceanguard.ai.ui.screens.camera.StillCaptureState
import com.oceanguard.ai.ui.screens.camera.VolumeShutterEffect
import com.oceanguard.ai.ui.screens.camera.createViewfinder
import com.oceanguard.ai.ui.screens.camera.openCameraAnalysis
import com.oceanguard.ai.ui.screens.camera.rememberStillCapture
import java.io.File
import kotlinx.coroutines.launch

private const val PHOTO_FILE_PREFIX = "OceanGuard"

private val TOP_BAR_PADDING = 12.dp
private val TOP_BUTTON_GAP = 8.dp

/**
 * Full-screen CameraX viewfinder with debris and species capture.
 *
 * What the camera will do is always on screen: the target (debris or species), the analysis mode,
 * which can be changed here, and for debris the switch to the continuous scan. That switch pushes
 * the scan over this screen, so Back from it returns here.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    navController: NavController,
    viewModel: MainViewModel,
    speciesMode: Boolean = false,
    onSpeciesCapture: ((Uri) -> Unit)? = null,
) {
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    val app = LocalContext.current.applicationContext as OceanGuardApp
    val modelState by app.modelLoadingState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val boundsMap = rememberSpotlightBounds()

    CameraWarmUpEffects(app, speciesMode)
    LocationPermissionEffect(cameraGranted = cameraPermissionState.status.isGranted)

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            CameraPreviewContent(
                boundsMap = boundsMap,
                speciesMode = speciesMode,
                continuousEnabled = modelState.rtdetr == ModelStatus.Ready,
                onOpenContinuous = { navController.navigate(OceanRoutes.CONTINUOUS_SCAN) },
                onImageCaptured = { uri ->
                    if (speciesMode && onSpeciesCapture != null) {
                        onSpeciesCapture(uri)
                    } else {
                        scope.launch { openCameraAnalysis(navController, viewModel, uri) }
                    }
                },
                onBack = { navController.popBackStack() },
            )
            CameraScreenTour(TourDefinitions.CAMERA, boundsMap)
        } else {
            CameraPermissionScreen(
                shouldShowRationale = cameraPermissionState.status.shouldShowRationale,
                onRequestPermission = { cameraPermissionState.launchPermissionRequest() },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * Loads the models in the background so the first capture does not pay the cold start while the
 * user frames the shot. Runs again when the mode changes here: the new model may need it. Both
 * calls do nothing if their model is missing, not selected or already loaded.
 */
@Composable
private fun CameraWarmUpEffects(app: OceanGuardApp, speciesMode: Boolean) {
    val initialMode = remember { app.settingsRepository.getAnalysisModeSync() }
    val mode by app.settingsRepository.analysisMode.collectAsStateWithLifecycle(initialMode)
    LaunchedEffect(mode) { app.prewarmGemma4DetectorIfAvailable() }
    LaunchedEffect(speciesMode) {
        if (speciesMode) app.prewarmSpeciesEncoder()
    }
}

/** Asks for location with the camera, so sessions can carry GPS. The app works without it. */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun LocationPermissionEffect(cameraGranted: Boolean) {
    val locationPermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
    )
    LaunchedEffect(cameraGranted) {
        if (cameraGranted && !locationPermissions.allPermissionsGranted) {
            locationPermissions.launchMultiplePermissionRequest()
        }
    }
}

@Composable
private fun CameraPreviewContent(
    boundsMap: MutableMap<String, Rect>,
    speciesMode: Boolean,
    continuousEnabled: Boolean,
    onOpenContinuous: () -> Unit,
    onImageCaptured: (Uri) -> Unit,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as OceanGuardApp
    val confirmCapture by app.settingsRepository.confirmCapture
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_CONFIRM_CAPTURE)
    val capture = rememberStillCapture()
    val settings = remember { ViewfinderSettings() }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val takePhoto: () -> Unit = {
        if (pendingUri == null) {
            capture.capture(PHOTO_FILE_PREFIX, settings.flashMode) { uri ->
                if (confirmCapture) pendingUri = uri else onImageCaptured(uri)
            }
        }
    }
    VolumeShutterEffect(takePhoto)

    Box(modifier = Modifier.fillMaxSize()) {
        CameraViewfinder(capture)
        val pending = pendingUri
        if (pending != null) {
            ConfirmCapture(
                photo = pending,
                onConfirm = {
                    pendingUri = null
                    onImageCaptured(pending)
                },
                onDiscard = { pendingUri = null },
            )
        } else {
            if (settings.showGrid) RuleOfThirdsGrid()
            CameraTopBar(speciesMode, settings, boundsMap, onBack)
            CameraDockContent(
                capture, speciesMode, continuousEnabled, boundsMap, onOpenContinuous, takePhoto,
            )
        }
    }
}

@Composable
private fun CameraViewfinder(capture: StillCaptureState) {
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        factory = { context -> createViewfinder(context, lifecycleOwner, listOf(capture.imageCapture)) },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Confirmation step. Back, like the button, throws the photo away and returns to the viewfinder. */
@Composable
private fun ConfirmCapture(photo: Uri, onConfirm: () -> Unit, onDiscard: () -> Unit) {
    val discard: () -> Unit = {
        runCatching { photo.path?.let { File(it).delete() } }
        onDiscard()
    }
    BackHandler(onBack = discard)
    CapturePreviewOverlay(imageUri = photo, onConfirm = onConfirm, onRetake = discard)
}

/** What the top bar changes on the viewfinder. */
private class ViewfinderSettings {
    var flashMode by mutableIntStateOf(ImageCapture.FLASH_MODE_OFF)
        private set
    var showGrid by mutableStateOf(false)
        private set

    fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    fun toggleGrid() {
        showGrid = !showGrid
    }
}

@Composable
private fun CameraTopBar(
    speciesMode: Boolean,
    settings: ViewfinderSettings,
    boundsMap: MutableMap<String, Rect>,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(TOP_BAR_PADDING),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CameraCircleButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDesc = stringResource(R.string.cd_go_back),
            onClick = onBack,
        )
        CameraPill(
            text = stringResource(
                if (speciesMode) R.string.sonar_camera_target_species else R.string.sonar_camera_target_debris,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TOP_BUTTON_GAP)) {
            GridButton(settings, Modifier.spotlightTarget("camera_grid", boundsMap))
            FlashButton(settings, Modifier.spotlightTarget("camera_flash", boundsMap))
        }
    }
}

@Composable
private fun GridButton(settings: ViewfinderSettings, modifier: Modifier) {
    CameraCircleButton(
        icon = if (settings.showGrid) Icons.Filled.GridOn else Icons.Filled.GridOff,
        contentDesc = stringResource(R.string.cd_toggle_grid),
        stateDesc = stringResource(
            if (settings.showGrid) R.string.sonar_camera_grid_on else R.string.sonar_camera_grid_off,
        ),
        onClick = settings::toggleGrid,
        modifier = modifier,
    )
}

@Composable
private fun FlashButton(settings: ViewfinderSettings, modifier: Modifier) {
    CameraCircleButton(
        icon = when (settings.flashMode) {
            ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn
            ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto
            else -> Icons.Filled.FlashOff
        },
        contentDesc = stringResource(R.string.cd_flash_mode),
        stateDesc = stringResource(
            when (settings.flashMode) {
                ImageCapture.FLASH_MODE_ON -> R.string.sonar_camera_flash_on
                ImageCapture.FLASH_MODE_AUTO -> R.string.sonar_camera_flash_auto
                else -> R.string.sonar_camera_flash_off
            },
        ),
        onClick = settings::cycleFlash,
        modifier = modifier,
    )
}

/** Analysis mode and continuous scan apply to debris: species always use their own pipeline. */
@Composable
private fun BoxScope.CameraDockContent(
    capture: StillCaptureState,
    speciesMode: Boolean,
    continuousEnabled: Boolean,
    boundsMap: MutableMap<String, Rect>,
    onOpenContinuous: () -> Unit,
    onShutter: () -> Unit,
) {
    val downloader = rememberModelDownloader()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    downloader.PreflightDialog()
    CaptureDock(isLandscape) {
        if (!speciesMode) {
            AnalysisModePicker(onDownload = { mode: AnalysisMode -> downloader.request(mode) })
            CaptureModeToggle(
                selected = CaptureMode.PHOTO,
                continuousEnabled = continuousEnabled,
                onSelect = { if (it == CaptureMode.CONTINUOUS) onOpenContinuous() },
            )
        }
        ShutterButton(
            isCapturing = capture.isCapturing,
            onClick = onShutter,
            contentDescription = stringResource(R.string.cd_capture_photo),
            modifier = Modifier.spotlightTarget("camera_shutter", boundsMap),
        )
        CaptureHint(
            failed = capture.failed,
            fallback = stringResource(
                if (speciesMode || continuousEnabled) {
                    R.string.camera_hint_tap_to_capture
                } else {
                    R.string.sonar_camera_continuous_loading
                },
            ),
        )
    }
}
