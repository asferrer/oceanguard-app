package com.oceanguard.ai.ui.screens.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import com.oceanguard.ai.data.ZoneCluster
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.screens.map.globe.GlobeCamera
import com.oceanguard.ai.ui.screens.map.globe.GlobeClusters
import com.oceanguard.ai.ui.screens.map.globe.GlobeDotShape
import com.oceanguard.ai.ui.screens.map.globe.GlobeMapBridge
import com.oceanguard.ai.ui.screens.map.globe.GlobeMode
import com.oceanguard.ai.ui.screens.map.globe.GlobeView
import com.oceanguard.ai.ui.screens.map.globe.diveInto
import com.oceanguard.ai.ui.screens.map.globe.divingCamera
import com.oceanguard.ai.ui.screens.map.globe.rememberPointDots
import com.oceanguard.ai.ui.screens.map.globe.rememberZoneDots
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.Position

/** The map's markers appear as the globe thins out, not before, so the two never double up. */
private const val MARKERS_HANDOVER_OPACITY = 0.99f

/**
 * Base map, the zones, species and photos drawn on it, the globe in front, and the camera that
 * moves them. What is drawn follows [MapScreenState.dataMode]: debris zones, species spots or both.
 */
@Composable
fun MapStage(
    state: MapScreenState,
    data: MapData,
    style: MapStyle,
    focus: Position?,
    camera: CameraState,
    boundsMap: MutableMap<String, Rect>,
) {
    val globe = state.globe
    val mode = state.dataMode
    val zones = if (mode.showsDebris) data.zones else emptyList()
    val species = if (mode.showsSpecies) state.species else null
    val speciesPoints = species?.points.orEmpty()
    val heat = state.showHeatmap && mode.showsDebris
    key(state.retryKey) {
        OceanGuardMap(
            cameraState = camera,
            geoJson = data.geoJson,
            styleUrl = style.url,
            showHeatmap = heat,
            zoneClusters = zones,
            fitAllTrigger = state.fitAllTrigger,
            focusPosition = focus,
            focusTrigger = if (focus != null) 1 else 0,
            flightsEnabled = globe.mode == GlobeMode.MAP,
            flightsEpoch = globe.introEpoch,
            speciesPoints = speciesPoints,
            onLoadFailed = { state.loadFailed = true },
            onLoadFinished = { state.loadFailed = false },
            modifier = Modifier.fillMaxSize().spotlightTarget("map_view", boundsMap),
        )
    }
    val place = remember(focus, zones, speciesPoints) { globePlace(focus, zones, speciesPoints) }
    val diveTarget = remember(place) { place?.let { divingCamera(it.latitude, it.longitude) } }
    GlobeMapBridge(globe, camera, place, diveTarget, hasFocus = focus != null, fitAllTrigger = state.fitAllTrigger)
    FlatMarkers(state, camera, if (heat) emptyList() else zones, species)
    GlobeDots(state, camera, zones)
}

/** Zone clusters, species clusters and the photo and species pins over the flat map. */
@Composable
private fun FlatMarkers(
    state: MapScreenState,
    camera: CameraState,
    zones: List<ZoneCluster>,
    species: com.oceanguard.ai.ui.screens.map.species.SpeciesMapData?,
) {
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val globe = state.globe
    val shown by remember(globe) {
        derivedStateOf { globe.mode == GlobeMode.MAP || globe.opacity < MARKERS_HANDOVER_OPACITY }
    }
    if (state.loadFailed || !shown) return
    val spots = species?.spots.orEmpty()
    MapMarkersOverlay(
        cameraState = camera,
        zones = zones,
        selectedSessionId = state.selectedPinId,
        onClusterClick = { index ->
            val zone = zones.getOrNull(index) ?: return@MapMarkersOverlay
            scope.launch {
                camera.flyTo(Position(zone.centroidLon, zone.centroidLat), MapMarkerPlanner.FLY_ZOOM, reduced)
                state.selectedZone = zone
            }
        },
        onPinClick = { state.selectedPinId = it },
        modifier = Modifier.fillMaxSize().graphicsLayer {
            alpha = if (globe.mode == GlobeMode.MAP) 1f else 1f - globe.opacity
        },
        species = spots,
        speciesCatalog = species?.catalog,
        selectedSpeciesKey = state.selectedSpecies?.speciesKey,
        onSpeciesClusterClick = { index ->
            val spot = spots.getOrNull(index) ?: return@MapMarkersOverlay
            scope.launch { camera.flyTo(Position(spot.longitude, spot.latitude), MapMarkerPlanner.FLY_ZOOM, reduced) }
        },
        onSpeciesPinClick = {
            state.selectedSpeciesGroup = null
            state.selectedSpecies = it
        },
        onGroupClick = { group -> MapMarkerPlanner.zoneFor(group, zones)?.let { state.selectedZone = it } },
        onSpeciesGroupClick = {
            state.selectedSpecies = null
            state.selectedSpeciesGroup = it
        },
    )
}

/**
 * The globe, with zone dots (circles) and species dots (rounded squares, grouped by
 * [GlobeClusters]). Tapping a dot is the only thing that zooms in by itself: it dives to the
 * flat map and frames what the dot stands for.
 */
@Composable
private fun GlobeDots(state: MapScreenState, camera: CameraState, zones: List<ZoneCluster>) {
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val globe = state.globe
    val zoneDots = rememberZoneDots(zones)
    val speciesPoints = if (state.dataMode.showsSpecies) state.species.points else emptyList()
    val speciesDots = rememberPointDots(speciesPoints, SonarTheme.colors.trace, GlobeDotShape.ROUNDED)
    val dots = remember(zoneDots, speciesDots) { zoneDots + speciesDots }
    GlobeView(
        controller = globe,
        dots = dots,
        onDotClick = { index ->
            val dot = dots.getOrNull(index) ?: return@GlobeView
            scope.launch {
                val at = Position(dot.longitude, dot.latitude)
                globe.diveInto(at, camera, reduced)
                if (index < zoneDots.size) {
                    camera.flyTo(at, MapMarkerPlanner.FLY_ZOOM, reduced)
                    state.selectedZone = zones.getOrNull(index)
                } else {
                    camera.flyTo(at, GlobeClusters.frameZoom(dot.spanDegrees, globe.minDimension), reduced)
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Where the globe turns to face on opening: the deep-linked place, else the middle of the zones and
 * species spots, else nowhere. Facing never zooms; see [com.oceanguard.ai.ui.screens.map.globe.GlobeOpening].
 */
private fun globePlace(focus: Position?, zones: List<ZoneCluster>, speciesPoints: List<Pair<Double, Double>>): GlobeCamera? {
    if (focus != null) return GlobeCamera(focus.latitude, focus.longitude, 0.0)
    val fit = MapMarkerPlanner.fitTarget(zones, speciesPoints) ?: return null
    return GlobeCamera(fit.latitude, fit.longitude, 0.0)
}
