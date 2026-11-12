package com.oceanguard.ai.ui.screens.map.globe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
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
 * Zones on the globe: one dot per zone, coloured by its health score and numbered with its
 * photos. Zones on the far side are not composed, and those near the horizon fade out.
 *
 * Reads the controller's camera, so it recomposes with every frame of the globe's motion.
 */
@Composable
internal fun GlobeMarkers(
    controller: GlobeController,
    zones: List<ZoneCluster>,
    onZoneClick: (zoneIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val camera = controller.view
        val distance = GlobeMath.distanceForZoom(camera.zoom, minOf(maxWidth, maxHeight).value.toDouble())
        val opacity = controller.opacity
        zones.forEachIndexed { index, zone ->
            val at = GlobeMath.project(zone.centroidLat, zone.centroidLon, camera, maxWidth.value.toDouble(), maxHeight.value.toDouble())
                ?: return@forEachIndexed
            val rotated = GlobeMath.rotateToView(zone.centroidLat, zone.centroidLon, camera.latitude, camera.longitude)
            val alpha = GlobeMath.limbOpacity(rotated, distance) * opacity
            if (alpha <= 0f) return@forEachIndexed
            key(index) { GlobeZoneDot(zone, at, alpha, maxWidth, maxHeight) { onZoneClick(index) } }
        }
    }
}

@Composable
private fun GlobeZoneDot(
    zone: ZoneCluster,
    at: ScreenPoint,
    alpha: Float,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
) {
    if (at.x < -TOUCH_TARGET.value || at.y < -TOUCH_TARGET.value) return
    if (at.x > width.value + TOUCH_TARGET.value || at.y > height.value + TOUCH_TARGET.value) return
    val count = zone.sessions.size
    val description = pluralStringResource(R.plurals.map_cluster_cd, count, count)
    val dot = MapMarkerPlanner.clusterDiameterDp(count).dp * DOT_SCALE
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
                .size(dot)
                .clip(CircleShape)
                .background(healthScoreColor(zone.aggregateHealthScore))
                .border(RING, SonarTheme.colors.ink, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(count.toString(), style = SonarTheme.type.data, color = SonarTheme.colors.abyss)
        }
    }
}

/** Dots are smaller than the flat map's clusters: a whole ocean of them has to fit. */
private const val DOT_SCALE = 0.62f
