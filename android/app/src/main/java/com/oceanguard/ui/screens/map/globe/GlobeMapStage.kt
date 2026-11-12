package com.oceanguard.ai.ui.screens.map.globe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.screens.map.flyTo
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.spatialk.geojson.Position

/**
 * A flat map with the globe in front of it: the building block of the small per-species and
 * per-debris-type maps. It opens on the globe, turned to face [frame] without zooming; tapping a
 * dot flies into the points it groups and crosses into the flat map, and zooming the map out
 * returns to the globe, as on the main map.
 *
 * Lives in a tab, so it is self-contained: the controller and the camera belong to it, and its GL
 * surface goes away with the composition (see [GlobeView]).
 *
 * @param dots Points drawn on the globe.
 * @param frame Where the globe turns to face on opening, and the camera the flat map starts on.
 * @param flatMap The flat map. It must use [CameraState] given to it and should hold its own
 * markers; it is drawn under the globe.
 */
@Composable
fun GlobeMapStage(
    dots: List<GlobeDot>,
    frame: CameraPosition,
    modifier: Modifier = Modifier,
    flatMap: @Composable (camera: CameraState) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val controller = remember { GlobeController() }
    val camera = rememberCameraState(firstPosition = frame)
    val face = remember(frame) { GlobeCamera(frame.target.latitude, frame.target.longitude, 0.0) }
    Box(modifier) {
        flatMap(camera)
        GlobeMapBridge(controller, camera, face, diveTarget = null, hasFocus = false, fitAllTrigger = 0)
        GlobeView(
            controller = controller,
            dots = dots,
            onDotClick = { index ->
                val dot = dots.getOrNull(index) ?: return@GlobeView
                scope.launch {
                    val at = Position(longitude = dot.longitude, latitude = dot.latitude)
                    controller.diveInto(at, camera, reduced)
                    camera.flyTo(at, GlobeClusters.frameZoom(dot.spanDegrees, controller.minDimension), reduced)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
