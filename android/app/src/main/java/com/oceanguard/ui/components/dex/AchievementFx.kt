package com.oceanguard.ai.ui.components.dex

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.collection.AchievementCategory
import com.oceanguard.ai.data.collection.DexSprites
import com.oceanguard.ai.ui.theme.AchievementColors
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Colours of the unlock effects, all taken from theme tokens. */
private data class FxPalette(
    val trace: Color,
    val echo: Color,
    val ring: Color,
    val waterDeep: Color,
    val waterLight: Color,
)

@Composable
private fun rememberFxPalette(): FxPalette {
    val sonar = SonarTheme.colors
    return FxPalette(
        trace = sonar.trace,
        echo = sonar.echo,
        ring = AchievementColors.Ring,
        waterDeep = AchievementColors.GradientMid,
        waterLight = AchievementColors.ProgressBar,
    )
}

/** Sprites that orbit in the collector's effect: one per kind of debris, decoded small. */
private val OrbitTypes = listOf(
    DebrisType.BOTTLE, DebrisType.CAN, DebrisType.TIRE,
    DebrisType.PLASTIC_DEBRIS, DebrisType.GLOVE, DebrisType.MASK,
)

private const val ORBIT_SAMPLE_SIZE = 4

@Composable
private fun rememberOrbitSprites(enabled: Boolean): List<ImageBitmap> {
    val context = LocalContext.current
    val sprites by produceState(emptyList<ImageBitmap>(), enabled) {
        if (!enabled) return@produceState
        value = withContext(Dispatchers.Default) {
            val options = BitmapFactory.Options().apply { inSampleSize = ORBIT_SAMPLE_SIZE }
            OrbitTypes.mapNotNull { type ->
                BitmapFactory.decodeResource(context.resources, DexSprites.getSprite(type), options)
                    ?.asImageBitmap()
            }
        }
    }
    return sprites
}

/**
 * Full-screen celebration of a category: one Canvas effect per [AchievementCategory], driven by
 * [progress] (0..1 over [AchievementFxMath.DURATION_MS]). It draws nothing but the effect, so
 * the caller decides whether to show it at all (it must not under "Remove animations").
 */
@Composable
fun AchievementFx(
    category: AchievementCategory,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val palette = rememberFxPalette()
    val sprites = rememberOrbitSprites(enabled = category == AchievementCategory.COLLECTOR || category == AchievementCategory.BIODEX)
    Canvas(modifier = modifier) {
        val k = progress()
        when (category) {
            AchievementCategory.FIRST_STEPS -> drawPing(k, palette)
            AchievementCategory.EXPLORER -> drawSweep(k, palette)
            AchievementCategory.COLLECTOR, AchievementCategory.BIODEX -> drawOrbit(k, sprites)
            AchievementCategory.IMPACT -> drawTide(k, palette)
            AchievementCategory.RESEARCH -> drawNetwork(k, palette)
            AchievementCategory.ELITE -> drawRays(k, palette)
        }
    }
}

private fun DrawScope.drawPing(k: Float, palette: FxPalette) {
    for (i in 0 until AchievementFxMath.PING_COUNT) {
        val p = AchievementFxMath.pingProgress(k, i) ?: continue
        drawCircle(
            color = palette.ring.copy(alpha = AchievementFxMath.pingAlpha(p)),
            radius = p * size.width * 0.85f,
            center = center,
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

private fun DrawScope.drawSweep(k: Float, palette: FxPalette) {
    val range = size.width * 0.62f
    repeat(AchievementFxMath.SWEEP_RINGS) { ring ->
        drawCircle(
            color = palette.trace.copy(alpha = 0.28f),
            radius = range * (ring + 1) / AchievementFxMath.SWEEP_RINGS,
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
    val angle = AchievementFxMath.sweepAngle(k)
    val trail = Brush.sweepGradient(
        0f to Color.Transparent,
        0.2f to palette.trace.copy(alpha = 0.5f),
        0.2001f to Color.Transparent,
        center = center,
    )
    rotate(degrees = Math.toDegrees((angle - 1.3f).toDouble()).toFloat(), pivot = center) {
        drawCircle(brush = trail, radius = range, center = center)
    }
    AchievementFxMath.SWEEP_FINDINGS.forEach { (theta, share) ->
        if (!AchievementFxMath.findingVisible(k, theta)) return@forEach
        val at = Offset(center.x + cos(theta) * range * share, center.y + sin(theta) * range * share)
        drawCircle(color = palette.echo, radius = 4.dp.toPx(), center = at)
    }
}

private fun DrawScope.drawOrbit(k: Float, sprites: List<ImageBitmap>) {
    val half = 30.dp.toPx()
    sprites.forEachIndexed { index, sprite ->
        val point = AchievementFxMath.orbitPoint(index, sprites.size, k)
        val x = center.x + point.x * size.width
        val y = center.y + point.y * size.width
        drawImage(
            image = sprite,
            srcSize = IntSize(sprite.width, sprite.height),
            dstOffset = IntOffset((x - half).toInt(), (y - half).toInt()),
            dstSize = IntSize((half * 2).toInt(), (half * 2).toInt()),
            alpha = AchievementFxMath.orbitAlpha(k),
            filterQuality = FilterQuality.None,
        )
    }
}

private fun DrawScope.drawTide(k: Float, palette: FxPalette) {
    val level = size.height * (1f - AchievementFxMath.tideLevel(k))
    val layers = listOf(
        palette.ring.copy(alpha = 0.5f),
        palette.waterDeep.copy(alpha = 0.7f),
        palette.waterLight.copy(alpha = 0.35f),
    )
    val step = 8.dp.toPx()
    layers.forEachIndexed { layer, color ->
        val path = Path().apply {
            moveTo(0f, size.height)
            var x = 0f
            while (x <= size.width) {
                lineTo(x, level + layer * 16.dp.toPx() + AchievementFxMath.waveOffset(x, k, layer, 9.dp.toPx()))
                x += step
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path, color)
    }
}

private fun DrawScope.drawNetwork(k: Float, palette: FxPalette) {
    val points = List(AchievementFxMath.NETWORK_POINTS) { i ->
        val p = AchievementFxMath.networkPoint(AchievementFxMath.networkStart(i), k)
        Offset(p.x * size.width, p.y * size.height)
    }
    if (AchievementFxMath.networkLinksVisible(k)) {
        AchievementFxMath.networkLinks(points.size).forEach { (a, b) ->
            drawLine(palette.trace.copy(alpha = 0.35f), points[a], points[b], strokeWidth = 1.dp.toPx())
        }
    }
    points.forEach { drawCircle(palette.trace, radius = 3.dp.toPx(), center = it) }
}

private fun DrawScope.drawRays(k: Float, palette: FxPalette) {
    val length = size.width * 0.72f * AchievementFxMath.rayLength(k)
    val half = 9.dp.toPx()
    val gold = Brush.verticalGradient(
        colors = listOf(palette.echo.copy(alpha = 0f), palette.echo.copy(alpha = 0.6f)),
        startY = center.y - length,
        endY = center.y,
    )
    val ray = Path().apply {
        moveTo(center.x - half, center.y)
        lineTo(center.x, center.y - length)
        lineTo(center.x + half, center.y)
        close()
    }
    val spin = Math.toDegrees(AchievementFxMath.raySpin(k).toDouble()).toFloat()
    for (i in 0 until AchievementFxMath.RAY_COUNT) {
        rotate(spin + 360f / AchievementFxMath.RAY_COUNT * (i + 1), center) { drawPath(ray, gold) }
    }
}
