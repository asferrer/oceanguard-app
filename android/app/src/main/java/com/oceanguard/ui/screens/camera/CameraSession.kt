package com.oceanguard.ai.ui.screens.camera

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.inference.LiveDetectionManager
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.SpotlightStep
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.navigation.OceanRoutes
import com.oceanguard.ai.ui.navigation.routes.CAMERA_ROUTE_PATTERN
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

private const val TAG = "CameraSession"
private const val FILE_TIMESTAMP = "yyyyMMdd_HHmmss"

/** Takes still photos with CameraX and remembers how the last attempt went. */
@Stable
internal class StillCaptureState(
    private val context: Context,
    private val executor: ExecutorService,
) {
    val imageCapture: ImageCapture = CaptureConfig.newImageCapture()

    var isCapturing by mutableStateOf(false)
        private set

    /** True after a failed photo, until the next attempt: nobody should fail in silence. */
    var failed by mutableStateOf(false)
        private set

    /**
     * Saves a photo to the app's external files directory, where the photo picker and the
     * inference pipeline can read it. The callbacks run on the main thread. Does nothing while
     * another capture is in flight.
     */
    fun capture(
        filePrefix: String,
        flashMode: Int = ImageCapture.FLASH_MODE_OFF,
        onFailure: () -> Unit = {},
        onSaved: (Uri) -> Unit,
    ) {
        if (isCapturing) return
        isCapturing = true
        failed = false
        imageCapture.flashMode = flashMode
        val timestamp = SimpleDateFormat(FILE_TIMESTAMP, Locale.US).format(System.currentTimeMillis())
        val file = File(context.getExternalFilesDir(null), "${filePrefix}_$timestamp.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        imageCapture.takePicture(
            options,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = output.savedUri ?: Uri.fromFile(file)
                    mainExecutor.execute {
                        isCapturing = false
                        onSaved(uri)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    mainExecutor.execute {
                        isCapturing = false
                        failed = true
                        onFailure()
                    }
                }
            },
        )
    }
}

/**
 * Capture state tied to the screen. Follows the device orientation sensor so the saved JPEG carries
 * the EXIF orientation the phone was really held in, even with system auto-rotate locked: the
 * picture must reach the detector the natural way up.
 */
@Composable
internal fun rememberStillCapture(): StillCaptureState {
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val state = remember { StillCaptureState(context, executor) }
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                state.imageCapture.targetRotation = surfaceRotationFor(orientation)
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose {
            listener.disable()
            executor.shutdown()
        }
    }
    return state
}

private fun surfaceRotationFor(degrees: Int): Int = when (degrees) {
    in 45..134 -> Surface.ROTATION_270
    in 135..224 -> Surface.ROTATION_180
    in 225..314 -> Surface.ROTATION_90
    else -> Surface.ROTATION_0
}

/**
 * Viewfinder that drives the given use cases. `unbindAll` hands the camera over from whichever
 * screen had it, and the implementation mode lets Compose draw and fade over the preview.
 */
internal fun createViewfinder(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    useCases: List<UseCase>,
    onBound: (Camera) -> Unit = {},
): PreviewView {
    val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    val providerFuture = ProcessCameraProvider.getInstance(context)
    val bind = Runnable {
        val provider = providerFuture.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        try {
            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                *(listOf<UseCase>(preview) + useCases).toTypedArray(),
            )
            onBound(camera)
        } catch (exception: Exception) {
            Log.e(TAG, "CameraX use-case binding failed", exception)
        }
    }
    providerFuture.addListener(bind, ContextCompat.getMainExecutor(context))
    return previewView
}

/**
 * Volume keys act as a hardware shutter while the calling screen is resumed: through a dive housing
 * they are often the only control that can be pressed.
 *
 * It follows the lifecycle and not the composition because the camera and the continuous scan
 * overlap during their transition: the screen that leaves would clear the flag of the one that came.
 */
@Composable
internal fun VolumeShutterEffect(onShutter: () -> Unit) {
    val app = LocalContext.current.applicationContext as OceanGuardApp
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestShutter by rememberUpdatedState(onShutter)
    DisposableEffect(app, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> app.consumeVolumeKeysForCapture = true
                Lifecycle.Event.ON_PAUSE -> app.consumeVolumeKeysForCapture = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                app.consumeVolumeKeysForCapture = false
            }
        }
    }
    LaunchedEffect(app) {
        app.volumeShutterRequests.collect { latestShutter() }
    }
}

/** Guided tour of a camera screen: starts on the first visit. Goes last, over the content. */
@Composable
internal fun CameraScreenTour(steps: List<SpotlightStep>, boundsMap: MutableMap<String, Rect>) {
    val settings = (LocalContext.current.applicationContext as OceanGuardApp).settingsRepository
    val scope = rememberCoroutineScope()
    val controller = rememberSpotlightController(steps)
    val screenId = TourDefinitions.getScreenId(steps)
    val complete by settings.isTourComplete(screenId).collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(complete) {
        if (!complete) controller.start()
    }
    SpotlightOverlay(
        controller = controller,
        targetBounds = boundsMap,
        onComplete = { scope.launch { settings.markTourComplete(screenId) } },
    )
}

/**
 * Starts the analysis of a photo and opens the signature moment. The viewfinder and the continuous
 * scan leave the stack, so Back from the result goes to Home.
 *
 * Waits for a continuous scan pass that may still be finishing, because it uses the same model.
 * Clearing the previous analysis first keeps its result from flashing on the new photo.
 */
internal suspend fun openCameraAnalysis(
    navController: NavController,
    viewModel: MainViewModel,
    photo: Uri,
) {
    LiveDetectionManager.awaitIdle()
    viewModel.resetState()
    viewModel.analyzeImage(photo)
    navController.navigate(OceanRoutes.CAMERA_RESULT) {
        popUpTo(CAMERA_ROUTE_PATTERN) { inclusive = true }
    }
}
