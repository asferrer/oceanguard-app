package com.oceanguard.ai.ui.components.analysis

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import kotlin.math.max

private const val TRAIL_HEIGHT_FRACTION = 0.42f
private const val TRAIL_MID_STOP = 0.45f
private const val TRAIL_MID_ALPHA = 0.05f
private const val TRAIL_END_ALPHA = 0.32f
private const val SECOND_RING_LAG = 0.28f
private const val LABEL_MIN_SCALE = 0.5f

private val LINE_WIDTH = 2.dp
private val RING_WIDTH = 2.dp
private val RING_MIN_RADIUS = 30.dp
private val BOX_WIDTH = 2.4.dp
private val BOX_CORNER = 5.dp
private val LABEL_GAP = 3.dp
private val LABEL_CORNER = 7.dp
private val LABEL_PADDING_X = 7.dp
private val LABEL_PADDING_Y = 3.dp

/** Line of the sonar at [fraction] of [frame], with its phosphorescent trail behind it. */
internal fun DrawScope.drawSweepLine(frame: Rect, fraction: Float, color: Color, alpha: Float) {
    val lineY = frame.top + frame.height * fraction
    val trailTop = lineY - frame.height * TRAIL_HEIGHT_FRACTION
    val visibleTop = max(trailTop, frame.top)
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            TRAIL_MID_STOP to color.copy(alpha = TRAIL_MID_ALPHA * alpha),
            1f to color.copy(alpha = TRAIL_END_ALPHA * alpha),
            startY = trailTop,
            endY = lineY,
        ),
        topLeft = Offset(frame.left, visibleTop),
        size = Size(frame.width, lineY - visibleTop),
    )
    drawLine(
        color = color.copy(alpha = alpha),
        start = Offset(frame.left, lineY),
        end = Offset(frame.right, lineY),
        strokeWidth = LINE_WIDTH.toPx(),
    )
}

/** Two rings that leave the centre of a detection, the second one a little later. */
internal fun DrawScope.drawEchoRings(rect: Rect, progress: Float, color: Color) {
    if (progress <= 0f || progress >= 1f) return
    val maxRadius = max(rect.width, RING_MIN_RADIUS.toPx())
    val stroke = Stroke(RING_WIDTH.toPx())
    fun ring(t: Float) {
        if (t <= 0f) return
        drawCircle(color.copy(alpha = 1f - t), t * maxRadius, rect.center, style = stroke)
    }
    ring(progress)
    ring((progress - SECOND_RING_LAG) / (1f - SECOND_RING_LAG))
}

/** Box outline, drawn along its perimeter up to [progress]. */
internal fun DrawScope.drawTracedBox(rect: Rect, progress: Float, color: Color) {
    if (progress <= 0f) return
    val perimeter = 2f * (rect.width + rect.height)
    val trace = if (progress >= 1f) {
        null
    } else {
        PathEffect.dashPathEffect(floatArrayOf(perimeter, perimeter), perimeter * (1f - progress))
    }
    drawRoundRect(
        color = color,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = CornerRadius(BOX_CORNER.toPx()),
        style = Stroke(BOX_WIDTH.toPx(), pathEffect = trace),
    )
}

/**
 * Pill above a box, kept inside [frame]. [progress] may overshoot 1: it comes from a spring, and
 * the jump is that overshoot.
 */
internal fun DrawScope.drawEchoLabel(
    layout: TextLayoutResult,
    rect: Rect,
    frame: Rect,
    progress: Float,
    color: Color,
    textColor: Color = Color.Unspecified,
) {
    if (progress <= 0f) return
    val paddingX = LABEL_PADDING_X.toPx()
    val paddingY = LABEL_PADDING_Y.toPx()
    val pill = Size(layout.size.width + 2f * paddingX, layout.size.height + 2f * paddingY)
    val left = rect.left.coerceAtMost(frame.right - pill.width).coerceAtLeast(frame.left)
    val top = (rect.top - pill.height - LABEL_GAP.toPx()).coerceAtLeast(frame.top)
    val pivot = Offset(left, top + pill.height)
    val pop = LABEL_MIN_SCALE + (1f - LABEL_MIN_SCALE) * progress
    scale(pop, pop, pivot) {
        val alpha = progress.coerceIn(0f, 1f)
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(left, top),
            size = pill,
            cornerRadius = CornerRadius(LABEL_CORNER.toPx()),
        )
        drawText(layout, color = textColor, topLeft = Offset(left + paddingX, top + paddingY), alpha = alpha)
    }
}
