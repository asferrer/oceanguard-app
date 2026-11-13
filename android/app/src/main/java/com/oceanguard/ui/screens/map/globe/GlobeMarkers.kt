package com.oceanguard.ai.ui.screens.map.globe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.ZoneCluster
import com.oceanguard.ai.ui.screens.map.MapMarkerPlanner
import com.oceanguard.ai.ui.theme.SonarTheme
import com.oceanguard.ai.ui.theme.healthScoreColor

private val TOUCH_TARGET = 48.dp
private val RING = 2.dp

/**
 * A point drawn on the globe. [label] is the number inside the dot, if any; [description] is what
 * a screen reader says for it.
 */
@Immutable
data class GlobeDot(
    val latitude: Double,
    val longitude: Double,
    val color: Color,
    val description: String,
    val label: String? = null,
    val diameterDp: Int = DEFAULT_DOT_DP,
    /** Larger side, in degrees, of the box around the points this dot stands for; 0 for a lone point. */
    val spanDegrees: Double = 0.0,
    val shape: GlobeDotShape = GlobeDotShape.CIRCLE,
)

/** Zones are circles; species are rounded squares, so the two kinds read apart on the globe. */
enum class GlobeDotShape { CIRCLE, ROUNDED }

const val DEFAULT_DOT_DP = 30

/** One dot per zone, coloured by its health score and numbered with its photos. */
@Composable
fun rememberZoneDots(zones: List<ZoneCluster>): List<GlobeDot> {
    val descriptions = zones.map { pluralStringResource(R.plurals.map_cluster_cd, it.sessions.size, it.sessions.size) }
    return remember(zones, descriptions) {
        zones.mapIndexed { i, zone ->
            GlobeDot(
                latitude = zone.centroidLat,
                longitude = zone.centroidLon,
                color = healthScoreColor(zone.aggregateHealthScore),
                description = descriptions[i],
                label = zone.sessions.size.toString(),
                diameterDp = (MapMarkerPlanner.clusterDiameterDp(zone.sessions.size) * ZONE_DOT_SCALE).toInt(),
            )
        }
    }
}

/**
 * Dots for bare points, grouped by [GlobeClusters]: a crowd shows as one numbered circle, which is
 * what the user taps to fly into it.
 */
@Composable
fun rememberPointDots(
    points: List<Pair<Double, Double>>,
    color: Color,
    shape: GlobeDotShape = GlobeDotShape.CIRCLE,
): List<GlobeDot> {
    val single = stringResource(R.string.map_globe_point_cd)
    val groups = remember(points) { GlobeClusters.group(points) }
    val descriptions = groups.map {
        val n = it.members.size
        if (n == 1) single else pluralStringResource(R.plurals.map_globe_cluster_cd, n, n)
    }
    return remember(groups, color, descriptions, shape) {
        groups.mapIndexed { i, g ->
            val n = g.members.size
            GlobeDot(
                latitude = g.latitude,
                longitude = g.longitude,
                color = color,
                description = descriptions[i],
                label = if (n > 1) n.toString() else null,
                diameterDp = if (n > 1) CLUSTER_DOT_DP + CLUSTER_DOT_STEP_DP * n.toString().length else POINT_DOT_DP,
                spanDegrees = g.spanDegrees,
                shape = shape,
            )
        }
    }
}

private const val POINT_DOT_DP = 16
private const val DOT_ROUNDING = 8
private const val CLUSTER_DOT_DP = 22
private const val CLUSTER_DOT_STEP_DP = 6

/**
 * Dots on the globe. Those on the far side are not composed, and those near the horizon fade out.
 *
 * Reads the controller's camera, so it recomposes with every frame of the globe's motion.
 */
@Composable
internal fun GlobeMarkers(
    controller: GlobeController,
    dots: List<GlobeDot>,
    onDotClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val camera = controller.view
        val distance = GlobeMath.distanceForZoom(camera.zoom, minOf(maxWidth, maxHeight).value.toDouble())
        val opacity = controller.opacity
        val projected = dots.map {
            GlobeMath.project(it.latitude, it.longitude, camera, maxWidth.value.toDouble(), maxHeight.value.toDouble())
        }
        val zonesAt = dots.indices.filter { dots[it].shape == GlobeDotShape.CIRCLE }.mapNotNull { projected[it]?.pair() }
        val speciesAt = dots.indices.filter { dots[it].shape == GlobeDotShape.ROUNDED }.mapNotNull { projected[it]?.pair() }
        dots.forEachIndexed { index, dot ->
            val raw = projected[index] ?: return@forEachIndexed
            val isSpecies = dot.shape == GlobeDotShape.ROUNDED
            val shift = MapMarkerPlanner.sideBySideShift(
                raw.x, raw.y, isSpecies, if (isSpecies) zonesAt else speciesAt, GLOBE_SIDE_BY_SIDE_DP,
            )
            val at = raw.copy(x = raw.x + shift)
            val rotated = GlobeMath.rotateToView(dot.latitude, dot.longitude, camera.latitude, camera.longitude)
            val alpha = GlobeMath.limbOpacity(rotated, distance) * opacity
            if (alpha <= 0f) return@forEachIndexed
            key(index) { GlobeDotView(dot, at, alpha, maxWidth, maxHeight) { onDotClick(index) } }
        }
    }
}

@Composable
private fun GlobeDotView(
    dot: GlobeDot,
    at: ScreenPoint,
    alpha: Float,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
) {
    if (at.x < -TOUCH_TARGET.value || at.y < -TOUCH_TARGET.value) return
    if (at.x > width.value + TOUCH_TARGET.value || at.y > height.value + TOUCH_TARGET.value) return
    val description = dot.description
    val dotShape = if (dot.shape == GlobeDotShape.ROUNDED) RoundedCornerShape(DOT_ROUNDING.dp) else CircleShape
    Box(
        modifier = Modifier
            .offset(at.x.dp - TOUCH_TARGET / 2, at.y.dp - TOUCH_TARGET / 2)
            .size(TOUCH_TARGET)
            .graphicsLayer { this.alpha = alpha }
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(dot.diameterDp.dp)
                .clip(dotShape)
                .background(dot.color)
                .border(RING, SonarTheme.colors.ink, dotShape),
            contentAlignment = Alignment.Center,
        ) {
            dot.label?.let { Text(it, style = SonarTheme.type.data, color = SonarTheme.colors.abyss) }
        }
    }
}

private fun ScreenPoint.pair(): Pair<Float, Float> = x to y

/**
 * Centre-to-centre distance of a zone dot and a species dot on the same place, so the species
 * square does not cover the zone's count.
 */
private const val GLOBE_SIDE_BY_SIDE_DP = 34f

/** Zone dots are smaller than the flat map's clusters: a whole ocean of them has to fit. */
private const val ZONE_DOT_SCALE = 0.62f
