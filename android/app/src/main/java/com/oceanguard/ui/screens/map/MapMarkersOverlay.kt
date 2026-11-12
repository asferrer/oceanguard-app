package com.oceanguard.ai.ui.screens.map

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.ZoneCluster
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.ui.screens.map.species.SpeciesSpot
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlinx.coroutines.delay
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.Position

private val PIN_TOUCH_TARGET = 48.dp
private val PIN_PHOTO = 40.dp
private val PIN_RING = 2.dp
private val OFF_SCREEN_MARGIN = 64.dp
private const val STAGGER_STEPS = 8

/**
 * Draws zones and photos over the map at their real coordinates. Far away each zone is a numbered
 * cluster; close up (see [MapMarkerPlanner.EXPAND_ZOOM]) it opens into one pin per photo, and each
 * pin pops in with a spring. Tapping a cluster reports its zone so the caller can fly to it.
 *
 * Species observations come in the same way, as [SpeciesClusterMarker] and [SpeciesPinMarker],
 * drawn differently so the two kinds are told apart.
 *
 * @param selectedSessionId Pin drawn highlighted, the one whose card is open.
 * @param species Species spots to draw next to the zones.
 * @param speciesCatalog Where the species pins take their sprite and name from.
 */
@Composable
fun MapMarkersOverlay(
    cameraState: CameraState,
    zones: List<ZoneCluster>,
    selectedSessionId: Long?,
    onClusterClick: (zoneIndex: Int) -> Unit,
    onPinClick: (sessionId: Long) -> Unit,
    modifier: Modifier = Modifier,
    species: List<SpeciesSpot> = emptyList(),
    speciesCatalog: SpeciesCatalog? = null,
    selectedSpeciesKey: String? = null,
    onSpeciesClusterClick: (spotIndex: Int) -> Unit = {},
    onSpeciesPinClick: (SpeciesPinMarker) -> Unit = {},
) {
    val expanded by remember(cameraState) {
        derivedStateOf { MapMarkerPlanner.isExpanded(cameraState.position.zoom) }
    }
    val markers = remember(zones, species, expanded) { MapMarkerPlanner.planAll(zones, species, expanded) }
    BoxWithConstraints(modifier) {
        // Read here, not in a child, so every camera frame re-projects the markers.
        cameraState.position
        val projection = cameraState.projection ?: return@BoxWithConstraints
        var shown = 0
        for (marker in markers) {
            if (shown >= MapMarkerPlanner.MAX_MARKERS) break
            val at = projection.screenLocationFromPosition(Position(marker.longitude, marker.latitude))
            if (!at.isOnScreen(maxWidth, maxHeight)) continue
            key(marker.key) {
                PositionedMarker(
                    marker, at.x, at.y, shown, selectedSessionId, onClusterClick, onPinClick,
                    SpeciesMarkerActions(speciesCatalog, selectedSpeciesKey, onSpeciesClusterClick, onSpeciesPinClick),
                )
            }
            shown++
        }
    }
}

/** What the species markers need besides their position. */
private class SpeciesMarkerActions(
    val catalog: SpeciesCatalog?,
    val selectedKey: String?,
    val onCluster: (Int) -> Unit,
    val onPin: (SpeciesPinMarker) -> Unit,
)

private fun androidx.compose.ui.unit.DpOffset.isOnScreen(width: Dp, height: Dp): Boolean =
    MapMarkerPlanner.isOnScreen(x.value, y.value, width.value, height.value, OFF_SCREEN_MARGIN.value)

@Composable
private fun PositionedMarker(
    marker: MapMarker,
    x: Dp,
    y: Dp,
    order: Int,
    selectedSessionId: Long?,
    onClusterClick: (Int) -> Unit,
    onPinClick: (Long) -> Unit,
    species: SpeciesMarkerActions,
) {
    when (marker) {
        is ClusterMarker -> {
            val size = MapMarkerPlanner.clusterDiameterDp(marker.sessionCount).dp
            Box(Modifier.offset(x - size / 2, y - size / 2).size(size)) {
                ClusterBubble(marker, size, onClick = { onClusterClick(marker.zoneIndex) })
            }
        }
        is SpeciesClusterMarker -> {
            val size = MapMarkerPlanner.clusterDiameterDp(marker.observationCount).dp
            Box(Modifier.offset(x - size / 2, y - size / 2).size(size)) {
                SpeciesClusterBubble(marker, size, onClick = { species.onCluster(marker.spotIndex) })
            }
        }
        is SpeciesPinMarker -> Box(
            Modifier.offset(x - SPECIES_TOUCH_TARGET / 2, y - SPECIES_TOUCH_TARGET / 2).size(SPECIES_TOUCH_TARGET),
        ) {
            SpeciesPinBadge(marker, species.catalog, order, marker.speciesKey != null && marker.speciesKey == species.selectedKey) {
                species.onPin(marker)
            }
        }
        is PinMarker -> Box(
            Modifier.offset(x - PIN_TOUCH_TARGET / 2, y - PIN_TOUCH_TARGET / 2).size(PIN_TOUCH_TARGET),
        ) {
            PhotoPin(marker, order, marker.sessionId == selectedSessionId) { onPinClick(marker.sessionId) }
        }
    }
}

@Composable
private fun ClusterBubble(marker: ClusterMarker, size: Dp, onClick: () -> Unit) {
    val description = pluralStringResource(R.plurals.map_cluster_cd, marker.sessionCount, marker.sessionCount)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(SonarTheme.colors.echo)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = marker.sessionCount.toString(),
            style = SonarTheme.type.data,
            color = SonarTheme.colors.onEcho,
        )
    }
}

@Composable
private fun PhotoPin(pin: PinMarker, order: Int, selected: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.map_pin_cd, pin.healthScore)
    val ring = if (selected) SonarTheme.colors.trace else SonarTheme.colors.echo
    Box(
        modifier = Modifier
            .size(PIN_TOUCH_TARGET)
            .popIn(order)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = pin.imageUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(if (selected) PIN_TOUCH_TARGET else PIN_PHOTO)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(PIN_RING, ring, CircleShape),
        )
    }
}

/**
 * Enters with the pop spring, each pin a step after the previous one. Under "Remove animations"
 * it only fades in, with no scaling.
 */
@Composable
internal fun Modifier.popIn(order: Int): Modifier {
    val reduced = LocalReducedMotion.current
    val scale = remember { Animatable(if (reduced) 1f else 0f) }
    val alpha = remember { Animatable(if (reduced) 0f else 1f) }
    LaunchedEffect(Unit) {
        delay((order % STAGGER_STEPS) * OceanMotion.STAGGER_MS.toLong())
        if (reduced) alpha.animateTo(1f, OceanMotion.reducedFade()) else scale.animateTo(1f, OceanMotion.popSpring())
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        this.alpha = alpha.value
    }
}
