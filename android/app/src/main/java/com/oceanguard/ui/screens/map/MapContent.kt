package com.oceanguard.ai.ui.screens.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.oceanguard.ai.data.ZoneCluster
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration.Companion.milliseconds

/** Default camera: central Pacific Ocean. */
private val DEFAULT_POSITION = CameraPosition(
    target = Position(longitude = -160.0, latitude = 0.0),
    zoom = 3.0,
)

/** Camera state of the detection map, starting over the open ocean until there is data to frame. */
@Composable
fun rememberMapCamera(): CameraState = rememberCameraState(firstPosition = DEFAULT_POSITION)

/**
 * Moves the camera to [target]. Flies over [OceanMotion.SIGNATURE_MS]; with "Remove animations"
 * it jumps, because a flight is spatial motion.
 */
suspend fun CameraState.flyTo(target: Position, zoom: Double, reducedMotion: Boolean) {
    val destination = CameraPosition(target = target, zoom = zoom)
    if (reducedMotion) {
        position = destination
    } else {
        animateTo(finalPosition = destination, duration = OceanMotion.SIGNATURE_MS.milliseconds)
    }
}

/**
 * Base map plus the density layers of the heat view. Zones and photos are drawn over it by
 * [MapMarkersOverlay], which needs the same [cameraState].
 *
 * @param geoJson Zone centroids with their health score, for the heat view.
 * @param styleUrl Full URL of the vector tile style to use.
 * @param showHeatmap Whether the density view is on.
 * @param zoneClusters Zones to frame when [fitAllTrigger] changes or the zones change.
 * @param fitAllTrigger Incremented to frame every zone again.
 * @param focusPosition Optional single coordinate to fly to (from a deep link).
 * @param focusTrigger Incremented alongside [focusPosition] to retrigger on re-navigation.
 * @param onLoadFailed The style could not be loaded, typically for lack of connection.
 * @param onLoadFinished The style loaded.
 */
@Composable
fun OceanGuardMap(
    cameraState: CameraState,
    geoJson: String,
    styleUrl: String,
    showHeatmap: Boolean,
    zoneClusters: List<ZoneCluster>,
    fitAllTrigger: Int,
    modifier: Modifier = Modifier,
    focusPosition: Position? = null,
    focusTrigger: Int = 0,
    onLoadFailed: () -> Unit = {},
    onLoadFinished: () -> Unit = {},
) {
    AnimateCameraToFit(cameraState, zoneClusters, fitAllTrigger)
    AnimateCameraToFocus(cameraState, focusPosition, focusTrigger)

    MaplibreMap(
        modifier = modifier,
        cameraState = cameraState,
        baseStyle = BaseStyle.Uri(styleUrl),
        options = MapOptions(
            ornamentOptions = OrnamentOptions(
                // The start corners hold the zone summary and the legend, the bottom end the map buttons.
                isLogoEnabled = true,
                logoAlignment = Alignment.BottomCenter,
                isCompassEnabled = true,
                isScaleBarEnabled = true,
                scaleBarAlignment = Alignment.TopEnd,
                isAttributionEnabled = false,
            ),
        ),
        onMapLoadFailed = { onLoadFailed() },
        onMapLoadFinished = onLoadFinished,
    ) {
        val stableData = remember(geoJson) { GeoJsonData.JsonString(geoJson) }
        val zonesSource = rememberGeoJsonSource(data = stableData)
        ZoneMapLayers(source = zonesSource, showHeatmap = showHeatmap)
    }
}

/** Flies to a single focus coordinate at street level. Fires when [focusTrigger] changes. */
@Composable
private fun AnimateCameraToFocus(
    cameraState: CameraState,
    focusPosition: Position?,
    focusTrigger: Int,
) {
    val reduced = LocalReducedMotion.current
    LaunchedEffect(focusTrigger) {
        val pos = focusPosition ?: return@LaunchedEffect
        cameraState.flyTo(pos, MapMarkerPlanner.FLY_ZOOM - 1.0, reduced)
    }
}

/** Frames every zone, again whenever the zones change or the user asks to see everything. */
@Composable
private fun AnimateCameraToFit(
    cameraState: CameraState,
    zoneClusters: List<ZoneCluster>,
    fitAllTrigger: Int,
) {
    val reduced = LocalReducedMotion.current
    LaunchedEffect(zoneClusters, fitAllTrigger) {
        val target = MapMarkerPlanner.fitTarget(zoneClusters) ?: return@LaunchedEffect
        cameraState.flyTo(Position(longitude = target.longitude, latitude = target.latitude), target.zoom, reduced)
    }
}
