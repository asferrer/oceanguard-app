package com.oceanguard.ai.ui.screens.map.globe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Size
import com.oceanguard.ai.ui.motion.OceanMotion
import kotlin.math.abs
import kotlin.math.min

/** Which of the two views is in charge of touches and of what the user sees. */
enum class GlobeMode { GLOBE, MAP }

/**
 * State and motion of the globe: its camera, whether it or the flat map is showing, and the
 * flights, spin and fling that move it. Every motion runs under one mutex, so a touch cancels
 * whatever was moving the camera.
 *
 * Lengths are in dp, the unit the flat map's zoom is defined in.
 */
@Stable
class GlobeController(initial: GlobeCamera = GlobeCamera(DEFAULT_LATITUDE, DEFAULT_LONGITUDE, DEFAULT_ZOOM)) {

    var view by mutableStateOf(initial)
        private set
    var mode by mutableStateOf(GlobeMode.GLOBE)
        private set

    /** True from the first touch: the user is steering, so nothing flies the camera on its own. */
    var userTookOver by mutableStateOf(false)
        private set

    /** Incremented every time the globe hands over to the map after an automatic flight. */
    var introEpoch by mutableIntStateOf(0)
        private set

    private var size by mutableStateOf(Size.Zero)
    private val entryOpacity = Animatable(1f)
    private val mutex = MutatorMutex()
    private var introPending = false

    /** Shorter side of the viewport, in dp. */
    val minDimension: Double
        get() = if (size.minDimension > 0f) size.minDimension.toDouble() else DEFAULT_MIN_DIMENSION

    /** Opacity of the globe: fading in after the map let go, and fading out as the map takes over. */
    val opacity: Float
        get() = if (mode == GlobeMode.MAP) 0f else min(entryOpacity.value, GlobeTransition.globeOpacity(GlobeMath.mapZoom(view)))

    /** Camera distance, in globe radii, for the current zoom. */
    val distance: Double
        get() = GlobeMath.distanceForZoom(view.zoom, minDimension)

    /** Size of the viewport in dp; clamps the camera to what that size can show. */
    fun onSizeChanged(widthDp: Float, heightDp: Float) {
        size = Size(widthDp, heightDp)
        view = GlobeMath.clamp(view, minDimension)
    }

    /** Moves the camera as one finger drags by ([dx], [dy]) dp. */
    fun drag(dx: Float, dy: Float) {
        view = GlobeMath.pan(view, dx.toDouble(), dy.toDouble(), minDimension)
    }

    /** Zooms by a pinch that scaled the gap between fingers by [scale]. */
    fun pinch(scale: Float) {
        view = GlobeMath.zoomBy(view, scale.toDouble(), minDimension)
    }

    /** A finger touched the globe: take the camera back from any motion in progress. */
    suspend fun grab() {
        userTookOver = true
        introPending = false
        mutex.mutate(MutatePriority.UserInput) { }
    }

    /** Keeps the camera moving after a drag released at ([vx], [vy]) dp/s, slowing under friction. */
    suspend fun fling(vx: Float, vy: Float) {
        mutex.mutate(MutatePriority.UserInput) {
            var speedX = vx.toDouble()
            var speedY = vy.toDouble()
            runFrames { dt, _ ->
                view = GlobeMath.pan(view, speedX * dt, speedY * dt, minDimension)
                speedX = GlobeMath.decay(speedX, dt)
                speedY = GlobeMath.decay(speedY, dt)
                abs(speedX) > FLING_STOP || abs(speedY) > FLING_STOP
            }
        }
    }

    /** Turns the globe slowly east until something else takes the camera. */
    suspend fun spin(degreesPerSecond: Double = SPIN_DEGREES_PER_SECOND) {
        mutex.mutate {
            runFrames { dt, _ ->
                view = view.copy(longitude = GlobeMath.wrapLongitude(view.longitude + degreesPerSecond * dt))
                true
            }
        }
    }

    /**
     * Flies the camera to [target]. Under reduced motion it cuts instead. The flight ends early if
     * the globe hands over to the map on the way, which is the point of flying in.
     *
     * @param intro Marks the flight as the automatic one that opens the screen, so the map frames
     * the zones once it takes over.
     * @param seconds Length of the flight.
     */
    suspend fun flyTo(
        target: GlobeCamera,
        reducedMotion: Boolean,
        intro: Boolean = false,
        seconds: Double = FLIGHT_SECONDS,
    ) {
        introPending = intro
        mutex.mutate {
            val clamped = GlobeMath.clamp(target, minDimension)
            if (reducedMotion) {
                view = clamped
                return@mutate
            }
            val start = GlobeMath.clamp(view, minDimension)
            runFrames { _, elapsed ->
                val t = (elapsed / seconds).coerceIn(0.0, 1.0)
                view = GlobeMath.clamp(
                    GlobeMath.interpolate(start, clamped, FastOutSlowInEasing.transform(t.toFloat()).toDouble()),
                    minDimension,
                )
                t < 1.0
            }
        }
    }

    /** Zooms by [steps] levels with a short flight, as a double tap or an accessibility action asks. */
    suspend fun zoomStep(steps: Double, reducedMotion: Boolean) {
        flyTo(view.copy(zoom = view.zoom + steps), reducedMotion, seconds = ZOOM_STEP_SECONDS)
    }

    /**
     * Calls [step] once per frame with the seconds since the previous frame and since the start,
     * until it returns false or the map takes over.
     */
    private suspend fun runFrames(step: (dt: Double, elapsed: Double) -> Boolean) {
        val first = awaitFrame()
        var last = first
        while (mode == GlobeMode.GLOBE) {
            val now = awaitFrame()
            val keepGoing = step((now - last) / NANOS, (now - first) / NANOS)
            last = now
            if (!keepGoing) return
        }
    }

    /** The map takes over: the globe stops drawing and stops taking touches. */
    fun enterMap() {
        if (mode == GlobeMode.MAP) return
        mode = GlobeMode.MAP
        if (introPending) {
            introPending = false
            introEpoch++
        }
    }

    /** The map zoomed out far enough: the globe comes back at [at], fading in unless motion is reduced. */
    suspend fun enterGlobe(at: GlobeCamera, reducedMotion: Boolean) {
        userTookOver = true
        view = GlobeMath.clamp(at, minDimension)
        entryOpacity.snapTo(if (reducedMotion) 1f else 0f)
        mode = GlobeMode.GLOBE
        if (!reducedMotion) entryOpacity.animateTo(1f, OceanMotion.transition())
    }

    private suspend fun awaitFrame(): Long = withFrameNanos { it }

    private companion object {
        const val DEFAULT_LATITUDE = 18.0
        const val DEFAULT_LONGITUDE = -150.0
        const val DEFAULT_ZOOM = 1.3
        const val DEFAULT_MIN_DIMENSION = 360.0
        const val SPIN_DEGREES_PER_SECOND = 7.0
        const val FLING_STOP = 8.0
        const val FLIGHT_SECONDS = 2.2
        const val ZOOM_STEP_SECONDS = 0.45
        const val NANOS = 1_000_000_000.0
    }
}
