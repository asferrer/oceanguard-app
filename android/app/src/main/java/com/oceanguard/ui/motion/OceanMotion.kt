package com.oceanguard.ai.ui.motion

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.sqrt
import kotlinx.coroutines.isActive

/**
 * Single source of motion values. No screen may hard-code a duration, curve or spring:
 * everything goes through this object so "exit faster than enter" and "Remove animations"
 * hold everywhere.
 */
object OceanMotion {

    const val FAST_MS = 150
    const val STANDARD_MS = 300
    const val TRANSITION_MS = 450
    /** Upper bound of a signature moment. The analysis sweep lasts what inference lasts. */
    const val SIGNATURE_MS = 800
    /** Enter delay and list stagger. */
    const val STAGGER_MS = 45
    /** Only movement allowed under "Remove animations": a short cross-fade. */
    const val REDUCED_FADE_MS = 120

    /** Share of the content width travelled on the X axis between tabs. */
    const val AXIS_OFFSET_FRACTION = 0.10f
    const val DEPTH_SCALE_IN = 0.96f
    const val DEPTH_SCALE_OUT = 1.03f
    const val PRESS_SCALE = 0.96f

    /** CSS `cubic-bezier(.2,0,0,1)`: the enter curve of the whole app. */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** CSS `cubic-bezier(.16,1,.3,1)`: count-ups and arcs that settle. */
    val Settle: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    /** Exit curve: leaves without decelerating. */
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    /** Circular reveal of the camera. */
    val Reveal: Easing = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)

    const val SNAP_STIFFNESS = 420f
    const val SNAP_DAMPING = 30f
    const val SOFT_STIFFNESS = 180f
    const val SOFT_DAMPING = 18f
    const val POP_STIFFNESS = 320f
    const val POP_DAMPING = 18f

    /** Converts physical `damping` (the second number of a stiffness/damping pair) into Compose's ratio. */
    fun dampingRatio(stiffness: Float, damping: Float): Float = damping / (2f * sqrt(stiffness))

    fun <T> snapSpring(): SpringSpec<T> =
        spring(dampingRatio(SNAP_STIFFNESS, SNAP_DAMPING), SNAP_STIFFNESS)

    fun <T> softSpring(): SpringSpec<T> =
        spring(dampingRatio(SOFT_STIFFNESS, SOFT_DAMPING), SOFT_STIFFNESS)

    fun <T> popSpring(): SpringSpec<T> =
        spring(dampingRatio(POP_STIFFNESS, POP_DAMPING), POP_STIFFNESS)

    fun <T> fast(delayMs: Int = 0): TweenSpec<T> = tween(FAST_MS, delayMs, Emphasized)

    fun <T> standard(delayMs: Int = 0): TweenSpec<T> = tween(STANDARD_MS, delayMs, Emphasized)

    fun <T> transition(delayMs: Int = 0): TweenSpec<T> = tween(TRANSITION_MS, delayMs, Emphasized)

    fun <T> signature(delayMs: Int = 0): TweenSpec<T> = tween(SIGNATURE_MS, delayMs, Settle)

    /** Exits are always the fast step. */
    fun <T> exit(): TweenSpec<T> = tween(FAST_MS, easing = Accelerate)

    fun <T> reducedFade(): TweenSpec<T> = tween(REDUCED_FADE_MS)

    /** `ANIMATOR_DURATION_SCALE == 0` is what the system's "Remove animations" writes. */
    fun isAnimatorOff(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

    fun readAnimatorDurationScale(context: Context): Float = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )

    /** Pure step of [rememberOceanLoop]: advances a 0..1 phase by [deltaMs]. */
    fun advancePhase(phase: Float, deltaMs: Long, periodMs: Int): Float {
        if (periodMs <= 0) return phase
        val next = phase + deltaMs.toFloat() / periodMs
        return next - next.toInt()
    }
}

/**
 * True when the system setting "Remove animations" is on. Provided once by
 * [com.oceanguard.ai.ui.theme.OceanGuardTheme]: read this local, never the setting.
 */
val LocalReducedMotion = compositionLocalOf { false }

/** Observes the setting so toggling it from Quick Settings takes effect without a restart. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember(context) {
        mutableStateOf(OceanMotion.isAnimatorOff(OceanMotion.readAnimatorDurationScale(context)))
    }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = OceanMotion.isAnimatorOff(OceanMotion.readAnimatorDurationScale(context))
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        context.contentResolver.registerContentObserver(uri, false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/** [spec] normally, an immediate jump to the final value under "Remove animations". */
@Composable
@ReadOnlyComposable
fun <T> motionSpec(spec: AnimationSpec<T>): AnimationSpec<T> =
    if (LocalReducedMotion.current) snap() else spec

/**
 * Phase in `[0, 1)` of an endless loop. It only advances while the owning screen is RESUMED and
 * animations are allowed, and it stays where it was while paused. This is the only sanctioned
 * way to run an endless animation: never use `rememberInfiniteTransition` in new code.
 *
 * @param periodMs Length of one cycle. May change between frames (the radar speed does).
 * @param running  Set to false to stop the loop from the caller's own logic.
 */
@Composable
fun rememberOceanLoop(periodMs: Int, running: Boolean = true): State<Float> {
    val reduced = LocalReducedMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val phase = remember { mutableFloatStateOf(0f) }
    val period by rememberUpdatedState(periodMs)
    LaunchedEffect(lifecycle, running, reduced) {
        if (!running || reduced) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = withInfiniteAnimationFrameMillis { it }
            while (isActive) {
                val now = withInfiniteAnimationFrameMillis { it }
                phase.floatValue = OceanMotion.advancePhase(phase.floatValue, now - last, period)
                last = now
            }
        }
    }
    return phase
}
