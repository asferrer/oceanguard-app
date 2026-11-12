package com.oceanguard.ai.ui.screens.map.globe

import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlinx.coroutines.launch

/** Atmosphere blue; the theme's trace colour is mixed in so the glow belongs to the app. */
private val ATMOSPHERE = Color(0xFF4DA6FF)
private const val ATMOSPHERE_TRACE_MIX = 0.22f
private const val RIM = 0x7FB8FF
private const val DOUBLE_TAP_ZOOM_STEP = 1.0

/**
 * A textured, lit 3D Earth with an atmosphere glow, drawn with OpenGL ES 2.0 and a bundled
 * texture, so it works with no network. Drag to turn it (it keeps spinning when flung), pinch or
 * double-tap to zoom; [dots] are drawn on it and report taps through [onDotClick].
 *
 * Draws nothing and takes no touches while [GlobeController.mode] is the flat map.
 */
@Composable
fun GlobeView(
    controller: GlobeController,
    dots: List<GlobeDot>,
    onDotClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val started = rememberIsStarted()
    if (controller.mode != GlobeMode.GLOBE || !started) return
    val density = LocalDensity.current.density
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val handler = remember(controller, density, reduced) {
        GlobeGestureHandler(
            onTouch = { scope.launch { controller.grab() } },
            onDrag = { dx, dy -> controller.drag(dx / density, dy / density) },
            onPinch = controller::pinch,
            onRelease = { vx, vy -> scope.launch { controller.fling(vx / density, vy / density) } },
            onDoubleTap = { scope.launch { controller.zoomStep(DOUBLE_TAP_ZOOM_STEP, reduced) } },
        )
    }
    val description = stringResource(R.string.map_globe_cd)
    val zoomIn = stringResource(R.string.map_globe_zoom_in)
    val zoomOut = stringResource(R.string.map_globe_zoom_out)
    Box(
        modifier
            .onSizeChanged { controller.onSizeChanged(it.width / density, it.height / density) }
            .globeGestures(handler)
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(zoomIn) { scope.launch { controller.zoomStep(DOUBLE_TAP_ZOOM_STEP, reduced) }; true },
                    CustomAccessibilityAction(zoomOut) { scope.launch { controller.zoomStep(-DOUBLE_TAP_ZOOM_STEP, reduced) }; true },
                )
            },
    ) {
        GlobeSurface(controller)
        GlobeMarkers(controller, dots, onDotClick, Modifier.matchParentSize())
    }
}

/** The GL surface, above the flat map's own surface so the globe can fade over it. */
@Composable
private fun GlobeSurface(controller: GlobeController) {
    val context = LocalContext.current
    val space = SonarTheme.colors.abyss
    val trace = SonarTheme.colors.trace
    val colors = remember(space, trace) {
        GlobeColors(space.toRgb(), lerp(ATMOSPHERE, trace, ATMOSPHERE_TRACE_MIX).toRgb(), RIM)
    }
    val renderer = remember(context, colors) { GlobeRenderer(context.applicationContext, colors) }
    var surface by remember { mutableStateOf<GLSurfaceView?>(null) }
    SurfaceLifecycle(surface)
    AndroidView(
        factory = { ctx -> createSurface(ctx, renderer).also { surface = it } },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            renderer.frame = GlobeFrame(controller.view.latitude, controller.view.longitude, controller.distance, controller.opacity)
            view.requestRender()
        },
        onRelease = { surface = null },
    )
}

private fun createSurface(context: android.content.Context, renderer: GlobeRenderer) = GLSurfaceView(context).apply {
    setEGLContextClientVersion(2)
    setEGLConfigChooser(8, 8, 8, 8, DEPTH_BITS, 0)
    holder.setFormat(PixelFormat.TRANSLUCENT)
    // Above the flat map's surface, below the window: the app's controls stay on top.
    setZOrderMediaOverlay(true)
    setRenderer(renderer)
    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

private const val DEPTH_BITS = 16

/**
 * False while the screen is covered by another destination or the app is in the background. The
 * globe then leaves the composition, which releases its GL surface: only the visible globe
 * renders, and two never fight over z-order.
 */
@Composable
private fun rememberIsStarted(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    return state.isAtLeast(Lifecycle.State.STARTED)
}

/** Pauses the GL thread with the screen and resumes it with it. */
@Composable
private fun SurfaceLifecycle(surface: GLSurfaceView?) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, surface) {
        val view = surface ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.onPause()
        }
    }
}

private fun Color.toRgb(): Int = toArgb() and 0xFFFFFF
