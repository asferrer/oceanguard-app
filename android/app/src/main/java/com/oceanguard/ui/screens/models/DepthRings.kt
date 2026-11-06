package com.oceanguard.ai.ui.screens.models

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.motionSpec
import com.oceanguard.ai.ui.motion.rememberOceanLoop
import com.oceanguard.ai.ui.theme.SonarTheme

private const val PING_PERIOD_MS = 3_200
private const val FILL_ALPHA = 0.35f
private const val PING_ALPHA = 0.5f
private val RING_STROKE = 2.dp
private val ACTIVE_STROKE = 3.dp
private val DASH = 6.dp
private const val MIN_RADIUS_FRACTION = 0.28f

/**
 * Depth rings: one ring per analysis mode, the shallowest in the middle. The mode in use is lit
 * and sends out an echo; a mode whose model is missing is dashed. The text next to it says the
 * same, so the drawing is hidden from TalkBack. Under "Remove animations" there is no echo and
 * the highlight changes at once.
 */
@Composable
fun DepthRings(rings: List<Ring>, modifier: Modifier = Modifier) {
    val colors = SonarTheme.colors
    val reduced = LocalReducedMotion.current
    val active = rings.indexOfFirst { it.state == RingState.ACTIVE }
    val phase by rememberOceanLoop(PING_PERIOD_MS, running = active >= 0)
    val fills = rings.map { ring ->
        val lit by animateFloatAsState(
            targetValue = if (ring.state == RingState.ACTIVE) 1f else 0f,
            animationSpec = motionSpec(OceanMotion.softSpring()),
            label = "ringFill",
        )
        lit
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        val maxRadius = size.minDimension / 2f
        rings.forEachIndexed { index, ring ->
            val radius = ringRadius(index, rings.size, maxRadius)
            drawRing(ring.state, radius, fills[index], colors.trace, colors.traceContainer, colors.lineStrong, colors.line)
        }
        if (active >= 0 && !reduced) {
            val target = ringRadius(active, rings.size, maxRadius)
            drawCircle(
                color = colors.trace.copy(alpha = PING_ALPHA * (1f - phase)),
                radius = target * phase,
                style = Stroke(RING_STROKE.toPx()),
            )
        }
    }
}

/** Radius of ring [index] of [count]: evenly spaced from a small inner ring to the edge. */
internal fun ringRadius(index: Int, count: Int, maxRadius: Float): Float {
    if (count <= 1) return maxRadius
    val step = (1f - MIN_RADIUS_FRACTION) / (count - 1)
    return maxRadius * (MIN_RADIUS_FRACTION + step * index)
}

private fun DrawScope.drawRing(
    state: RingState,
    radius: Float,
    lit: Float,
    trace: Color,
    traceContainer: Color,
    solid: Color,
    dim: Color,
) {
    if (lit > 0f) drawCircle(traceContainer.copy(alpha = FILL_ALPHA * lit), radius, Offset(center.x, center.y))
    val dashed = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), DASH.toPx()))
    when (state) {
        RingState.ACTIVE -> drawCircle(trace, radius, style = Stroke(ACTIVE_STROKE.toPx()))
        RingState.READY -> drawCircle(solid, radius, style = Stroke(RING_STROKE.toPx()))
        RingState.MISSING ->
            drawCircle(solid, radius, style = Stroke(RING_STROKE.toPx(), pathEffect = dashed))
        RingState.UNAVAILABLE ->
            drawCircle(dim, radius, style = Stroke(RING_STROKE.toPx(), pathEffect = dashed))
    }
}
