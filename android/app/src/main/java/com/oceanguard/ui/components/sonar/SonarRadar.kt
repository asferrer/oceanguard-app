package com.oceanguard.ai.ui.components.sonar

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.rememberOceanLoop
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TWO_PI = (2.0 * PI).toFloat()
private const val MILLIS_PER_SECOND = 1000f
private const val SWEEP_TAIL_TURN = 0.25f
private const val SWEEP_TAIL_ALPHA = 0.34f
/** Life an echo loses per second after the line passes over it. */
private const val ECHO_FADE_PER_SECOND = 0.32f
private const val STATIC_ECHO_LIFE = 0.8f
private const val PULSE_MAX_ALPHA = 0.8f
private val ECHO_RADIUS = 3.2.dp
private val SWEEP_LINE_WIDTH = 1.5.dp
private val PULSE_WIDTH = 2.dp

/**
 * One blip of the radar.
 *
 * @param radius Distance to the centre as a share of the radius, 0..1.
 * @param angle  Position in radians, clockwise from three o'clock.
 * @param isFinding Amber when it stands for a finding, otherwise trace.
 */
@Immutable
data class RadarEcho(val radius: Float, val angle: Float, val isFinding: Boolean)

/** Decorative blips for when the screen has no real positions to show. */
val DefaultRadarEchoes = listOf(
    RadarEcho(0.72f, -0.6f, true),
    RadarEcho(0.84f, 1.1f, true),
    RadarEcho(0.62f, 2.5f, false),
    RadarEcho(0.90f, 3.9f, false),
    RadarEcho(0.78f, 5.2f, true),
)

/** Lets a button send a ping through the radar. */
@Stable
class SonarRadarState internal constructor(private val scope: CoroutineScope) {
    internal val pulse = Animatable(1f)

    fun ping() {
        scope.launch {
            pulse.snapTo(0f)
            pulse.animateTo(1f, OceanMotion.signature())
        }
    }
}

@Composable
fun rememberSonarRadarState(): SonarRadarState {
    val scope = rememberCoroutineScope()
    return remember(scope) { SonarRadarState(scope) }
}

/** Radar pure maths, kept apart from the drawing to test it. */
object SonarRadarMath {
    /** Angle the line has covered since it passed [echoAngle], in `[0, 2π)`. */
    fun angleBehind(sweep: Float, echoAngle: Float): Float {
        val delta = (sweep - echoAngle) % TWO_PI
        return if (delta < 0f) delta + TWO_PI else delta
    }

    /** Brightness of an echo: full when the line passes over it, then it fades. */
    fun echoLife(sweep: Float, echoAngle: Float, radPerSec: Float): Float {
        if (radPerSec <= 0f) return 0f
        val secondsSincePass = angleBehind(sweep, echoAngle) / radPerSec
        return max(0f, 1f - secondsSincePass * ECHO_FADE_PER_SECOND)
    }

    fun periodMs(radPerSec: Float): Int =
        if (radPerSec <= 0f) 0 else (TWO_PI / radPerSec * MILLIS_PER_SECOND).toInt()
}

/**
 * Sonar screen drawn in Canvas. Give it a square size and put the scan button on top.
 *
 * @param radPerSec Sweep speed: pass `AnalysisMode.radarRadPerSec`. It changes without a jump.
 * @param running   False stops the sweep, for instance while the model is not ready.
 * Paused off screen by [rememberOceanLoop]. Under "Remove animations" it is a still image of the
 * rings and echoes, with no sweep line.
 */
@Composable
fun SonarRadar(
    radPerSec: Float,
    modifier: Modifier = Modifier,
    rings: Int = 4,
    echoes: List<RadarEcho> = DefaultRadarEchoes,
    state: SonarRadarState = rememberSonarRadarState(),
    running: Boolean = true,
) {
    val colors = SonarTheme.colors
    val reduced = LocalReducedMotion.current
    val phase by rememberOceanLoop(SonarRadarMath.periodMs(radPerSec), running)
    val start = -PI.toFloat() / 2f
    Canvas(modifier) {
        val radius = size.minDimension / 2f
        val sweep = start + phase * TWO_PI
        drawGrid(rings, radius, colors.line)
        if (!reduced) drawSweep(sweep, radius, colors.trace)
        echoes.forEach { echo ->
            val life = if (reduced) STATIC_ECHO_LIFE else SonarRadarMath.echoLife(sweep, echo.angle, radPerSec)
            drawEcho(echo, life, radius, if (echo.isFinding) colors.echo else colors.trace)
        }
        if (!reduced) drawPulse(state.pulse.value, radius, colors.trace)
    }
}

private fun DrawScope.drawGrid(rings: Int, radius: Float, line: Color) {
    val stroke = Stroke(1.dp.toPx())
    for (ring in 1..rings) {
        drawCircle(line, radius * ring / rings, center, style = stroke)
    }
    val hairline = 1.dp.toPx()
    drawLine(line, center - Offset(radius, 0f), center + Offset(radius, 0f), hairline)
    drawLine(line, center - Offset(0f, radius), center + Offset(0f, radius), hairline)
}

private fun DrawScope.drawSweep(sweep: Float, radius: Float, trace: Color) {
    val degrees = Math.toDegrees(sweep.toDouble()).toFloat() - 90f
    rotate(degrees, center) {
        drawCircle(
            brush = Brush.sweepGradient(
                0f to Color.Transparent,
                SWEEP_TAIL_TURN to trace.copy(alpha = SWEEP_TAIL_ALPHA),
                SWEEP_TAIL_TURN to Color.Transparent,
                1f to Color.Transparent,
                center = center,
            ),
            radius = radius,
            center = center,
        )
    }
    drawLine(
        color = trace,
        start = center,
        end = center + Offset(cos(sweep) * radius, sin(sweep) * radius),
        strokeWidth = SWEEP_LINE_WIDTH.toPx(),
    )
}

private fun DrawScope.drawEcho(echo: RadarEcho, life: Float, radius: Float, color: Color) {
    if (life <= 0f) return
    val distance = echo.radius * radius
    drawCircle(
        color = color,
        radius = ECHO_RADIUS.toPx(),
        center = center + Offset(cos(echo.angle) * distance, sin(echo.angle) * distance),
        alpha = life,
    )
}

private fun DrawScope.drawPulse(progress: Float, radius: Float, trace: Color) {
    if (progress >= 1f) return
    drawCircle(
        color = trace,
        radius = radius * progress,
        center = center,
        alpha = (1f - progress) * PULSE_MAX_ALPHA,
        style = Stroke(PULSE_WIDTH.toPx()),
    )
}
