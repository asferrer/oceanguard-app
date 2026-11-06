package com.oceanguard.ai.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Circular reveal from the scan button to the camera.
 *
 * CameraX draws on a `SurfaceView`, which Compose cannot clip, so the circle cannot cut the camera
 * itself: a circle of the abyss colour grows from the button over the app, the navigation happens
 * behind it once it covers the screen, and it fades away over the viewfinder.
 */
@Stable
class CameraRevealState internal constructor(
    private val scope: CoroutineScope,
    private val reducedMotion: () -> Boolean,
) {
    internal var center by mutableStateOf<Offset?>(null)
        private set
    internal val growth = Animatable(0f)
    internal val opacity = Animatable(1f)
    private var job: Job? = null

    /**
     * @param origin    Centre of the button in root coordinates (`boundsInRoot().center`).
     * @param navigate  Runs once the app is covered, or at once under "Remove animations".
     */
    fun launch(origin: Offset, navigate: () -> Unit) {
        if (reducedMotion()) {
            navigate()
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            growth.snapTo(0f)
            opacity.snapTo(1f)
            center = origin
            growth.animateTo(1f, tween(OceanMotion.TRANSITION_MS, easing = OceanMotion.Reveal))
            navigate()
            opacity.animateTo(0f, OceanMotion.standard(OceanMotion.STANDARD_MS))
            center = null
        }
    }
}

/** Null outside the app shell, as in previews: then navigate directly. */
val LocalCameraReveal = compositionLocalOf<CameraRevealState?> { null }

@Composable
internal fun rememberCameraReveal(): CameraRevealState {
    val scope = rememberCoroutineScope()
    val reduced by rememberUpdatedState(LocalReducedMotion.current)
    return remember(scope) { CameraRevealState(scope) { reduced } }
}

/** Draws above the whole app. It takes no input: nothing is blocked while it plays. */
@Composable
internal fun CameraRevealOverlay(state: CameraRevealState, color: Color = SonarTheme.colors.abyss) {
    val center = state.center ?: return
    Canvas(Modifier.fillMaxSize()) {
        val reach = maxOf(
            hypot(center.x, center.y),
            hypot(size.width - center.x, center.y),
            hypot(center.x, size.height - center.y),
            hypot(size.width - center.x, size.height - center.y),
        )
        drawCircle(
            color = color,
            radius = reach * state.growth.value,
            center = center,
            alpha = state.opacity.value,
        )
    }
}
