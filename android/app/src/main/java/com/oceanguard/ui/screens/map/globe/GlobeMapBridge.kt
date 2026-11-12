package com.oceanguard.ai.ui.screens.map.globe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.Position

/**
 * Hands the camera back and forth between the globe and the flat map, and opens the screen on the
 * globe.
 *
 * - Zooming the globe in past [GlobeTransition.FADE_START_ZOOM] makes the map follow its camera
 *   underneath while the globe fades out; at [GlobeTransition.ENTER_MAP_ZOOM] the map takes over.
 * - Zooming the map out below [GlobeTransition.EXIT_MAP_ZOOM] brings the globe back, centred on
 *   the map's centre.
 * - Unless the user has already touched the globe, it opens by spinning, or by flying to
 *   [introTarget] when there is one; the map then frames the zones as it always did.
 *
 * @param introTarget Where the opening flight lands, already at the zoom where the map takes over.
 * @param fitAllTrigger Changes when the user asks to see everything again; repeats the flight.
 */
@Composable
fun GlobeMapBridge(
    controller: GlobeController,
    camera: CameraState,
    introTarget: GlobeCamera?,
    fitAllTrigger: Int,
) {
    val reduced = LocalReducedMotion.current
    GlobeToMapEffect(controller, camera)
    MapToGlobeEffect(controller, camera, reduced)
    val handled = remember { IntArray(1) }
    LaunchedEffect(controller, introTarget, fitAllTrigger, reduced) {
        val asked = fitAllTrigger != handled[0]
        handled[0] = fitAllTrigger
        if (controller.mode != GlobeMode.GLOBE || (controller.userTookOver && !asked)) return@LaunchedEffect
        if (introTarget != null) controller.flyTo(introTarget, reduced, intro = true) else if (!reduced) controller.spin()
    }
}

@Composable
private fun GlobeToMapEffect(controller: GlobeController, camera: CameraState) {
    LaunchedEffect(controller, camera) {
        snapshotFlow { controller.view }.collect {
            if (controller.mode == GlobeMode.GLOBE) controller.handOverIfDeepEnough(camera)
        }
    }
}

@Composable
private fun MapToGlobeEffect(controller: GlobeController, camera: CameraState, reduced: Boolean) {
    LaunchedEffect(controller, camera, reduced) {
        snapshotFlow { camera.position }.collect { p ->
            if (controller.mode == GlobeMode.MAP && GlobeTransition.shouldEnterGlobe(p.zoom)) {
                val lat = p.target.latitude
                controller.enterGlobe(GlobeCamera(lat, p.target.longitude, GlobeMath.globeZoomForMap(p.zoom, lat)), reduced)
            }
        }
    }
}

/** Keeps the map under the globe at the same place while the globe fades, and takes over once it is gone. */
private fun GlobeController.handOverIfDeepEnough(camera: CameraState) {
    val zoom = GlobeMath.mapZoom(view)
    if (GlobeTransition.mapShouldFollow(zoom)) syncMap(camera)
    if (GlobeTransition.shouldEnterMap(zoom)) enterMap()
}

private fun GlobeController.syncMap(camera: CameraState) {
    camera.position = CameraPosition(
        target = Position(longitude = view.longitude, latitude = view.latitude),
        zoom = GlobeMath.mapZoom(view),
    )
}

/**
 * Flies the globe in on [target] until the map takes over, then leaves the map there. Safe to call
 * under reduced motion, where the globe cuts straight to the handover zoom.
 */
suspend fun GlobeController.diveInto(target: Position, camera: CameraState, reducedMotion: Boolean) {
    flyTo(divingCamera(target.latitude, target.longitude), reducedMotion, seconds = DIVE_SECONDS)
    if (mode == GlobeMode.GLOBE) {
        syncMap(camera)
        enterMap()
    }
}

/** Camera over a coordinate at the zoom where the flat map takes over, a hair past it. */
fun divingCamera(latitude: Double, longitude: Double): GlobeCamera =
    GlobeCamera(latitude, longitude, GlobeMath.globeZoomForMap(GlobeTransition.ENTER_MAP_ZOOM + DIVE_MARGIN, latitude))

private const val DIVE_MARGIN = 0.05
private const val DIVE_SECONDS = 1.3
