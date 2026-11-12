package com.oceanguard.ai.ui.screens.map.globe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.screens.map.MapMarkerPlanner
import com.oceanguard.ai.ui.screens.map.flyTo
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.spatialk.geojson.Position

/**
 * A flat map with the globe in front of it: the building block of the small per-species and
 * per-debris-type maps. It opens on the globe, flies to [frame], crosses into the flat map and
 * returns to the globe when the map is zoomed out, exactly as the main map does.
 *
 * Lives in a tab, so it is self-contained: the controller and the camera belong to it, and its GL
 * surface goes away with the composition (see [GlobeView]).
 *
 * @param dots Points drawn on the globe.
 * @param frame Camera the flat map ends on once the globe hands over; also where the globe flies.
 * @param pointZoom Zoom of the flat map after tapping a dot on the globe.
 * @param flatMap The flat map. It must use [CameraState] given to it and should hold its own
 * markers; it is drawn under the globe.
 */
@Composable
fun GlobeMapStage(
    dots: List<GlobeDot>,
    frame: CameraPosition,
    modifier: Modifier = Modifier,
    pointZoom: Double = MapMarkerPlanner.FLY_ZOOM,
    flatMap: @Composable (camera: CameraState) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val controller = remember { GlobeController() }
    val camera = rememberCameraState(firstPosition = frame)
    val introTarget = remember(frame) { divingCamera(frame.target.latitude, frame.target.longitude) }
    Box(modifier) {
        flatMap(camera)
        GlobeMapBridge(controller, camera, introTarget, fitAllTrigger = 0)
        FrameOnHandover(controller, camera, frame, reduced)
        GlobeView(
            controller = controller,
            dots = dots,
            onDotClick = { index ->
                val dot = dots.getOrNull(index) ?: return@GlobeView
                scope.launch {
                    val at = Position(longitude = dot.longitude, latitude = dot.latitude)
                    controller.diveInto(at, camera, reduced)
                    camera.flyTo(at, pointZoom, reduced)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** After the opening flight the map frames the points, as the standalone map always did. */
@Composable
private fun FrameOnHandover(controller: GlobeController, camera: CameraState, frame: CameraPosition, reduced: Boolean) {
    LaunchedEffect(controller.introEpoch) {
        if (controller.introEpoch == 0) return@LaunchedEffect
        camera.flyTo(frame.target, frame.zoom, reduced)
    }
}
