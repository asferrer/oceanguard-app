package com.oceanguard.ai.ui.screens.map.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeMathTest {

    private val minDim = 360.0

    @Test
    fun latLonRoundTripsThroughTheUnitSphere() {
        val (lat, lon) = GlobeMath.toLatLon(GlobeMath.toVector(38.5, -122.25))

        assertEquals(38.5, lat, 1e-9)
        assertEquals(-122.25, lon, 1e-9)
    }

    @Test
    fun theCentreOfTheViewLandsOnThePlusZAxis() {
        val p = GlobeMath.rotateToView(40.0, -30.0, 40.0, -30.0)

        assertEquals(0.0, p.x, 1e-9)
        assertEquals(0.0, p.y, 1e-9)
        assertEquals(1.0, p.z, 1e-9)
    }

    @Test
    fun northAndEastOfTheCentreAreUpAndRight() {
        val north = GlobeMath.rotateToView(20.0, 0.0, 10.0, 0.0)
        val east = GlobeMath.rotateToView(10.0, 20.0, 10.0, 0.0)

        assertTrue(north.y > 0 && Math.abs(north.x) < 1e-9)
        assertTrue(east.x > 0)
    }

    @Test
    fun rotationPreservesDistanceFromTheCentre() {
        val p = GlobeMath.rotateToView(-12.0, 75.0, 33.0, -140.0)

        assertEquals(1.0, p.x * p.x + p.y * p.y + p.z * p.z, 1e-9)
    }

    @Test
    fun rotationMatrixAgreesWithRotateToView() {
        val (cLat, cLon) = 52.0 to 17.0
        val m = GlobeMath.rotationMatrix(cLat, cLon)
        val v = GlobeMath.toVector(-8.0, 100.0)

        val x = m[0] * v.x + m[3] * v.y + m[6] * v.z
        val y = m[1] * v.x + m[4] * v.y + m[7] * v.z
        val z = m[2] * v.x + m[5] * v.y + m[8] * v.z
        val expected = GlobeMath.rotateToView(-8.0, 100.0, cLat, cLon)

        assertEquals(expected.x, x, 1e-5)
        assertEquals(expected.y, y, 1e-5)
        assertEquals(expected.z, z, 1e-5)
    }

    @Test
    fun pointsOnTheFarSideAreHidden() {
        val view = GlobeCamera(0.0, 0.0, 1.5)

        assertNotNull(GlobeMath.project(0.0, 10.0, view, 360.0, 640.0))
        assertNull(GlobeMath.project(0.0, 180.0, view, 360.0, 640.0))
        assertNull(GlobeMath.project(0.0, 100.0, view, 360.0, 640.0))
    }

    @Test
    fun theCentrePointProjectsToTheMiddleOfTheViewport() {
        val at = GlobeMath.project(12.0, 34.0, GlobeCamera(12.0, 34.0, 1.5), 360.0, 640.0)!!

        assertEquals(180f, at.x, 1e-3f)
        assertEquals(320f, at.y, 1e-3f)
    }

    @Test
    fun theHorizonSitsOnTheEdgeOfTheDiscOnScreen() {
        val view = GlobeCamera(0.0, 0.0, 1.5)
        val distance = GlobeMath.distanceForZoom(view.zoom, minDim)
        // The visible limb is at the angle whose cosine is 1 / distance from the centre.
        val limbLon = Math.toDegrees(Math.acos(1.0 / distance)) - 0.01
        val at = GlobeMath.project(0.0, limbLon, view, minDim, minDim)!!

        val radius = GlobeMath.screenRadius(view, minDim)
        assertEquals(radius, (at.x - minDim / 2), radius * 0.02)
    }

    @Test
    fun zoomAndDistanceAreInverse() {
        for (zoom in listOf(1.2, 2.0, 3.0, 4.5)) {
            val distance = GlobeMath.distanceForZoom(zoom, minDim)
            assertEquals(zoom, GlobeMath.zoomForDistance(distance, minDim), 1e-9)
        }
    }

    @Test
    fun zoomingInBringsTheCameraCloserButNeverInsideTheSphere() {
        val far = GlobeMath.distanceForZoom(1.0, minDim)
        val near = GlobeMath.distanceForZoom(4.5, minDim)

        assertTrue(far > near)
        assertTrue(near > 1.0)
    }

    @Test
    fun minimumZoomKeepsTheWholeGlobeInView() {
        val view = GlobeCamera(0.0, 0.0, GlobeMath.minZoom(minDim))

        assertTrue(GlobeMath.screenRadius(view, minDim) < minDim / 2)
        assertEquals(GlobeMath.MAX_DISTANCE, GlobeMath.distanceForZoom(view.zoom, minDim), 1e-9)
    }

    @Test
    fun mapZoomAccountsForMercatorStretchAwayFromTheEquator() {
        assertEquals(3.0, GlobeMath.mapZoomForGlobe(3.0, 0.0), 1e-9)
        assertEquals(2.0, GlobeMath.mapZoomForGlobe(3.0, 60.0), 1e-9)
        assertEquals(3.0, GlobeMath.globeZoomForMap(GlobeMath.mapZoomForGlobe(3.0, 42.0), 42.0), 1e-9)
    }

    @Test
    fun clampKeepsThePolesFromFlippingAndWrapsLongitude() {
        val clamped = GlobeMath.clamp(GlobeCamera(120.0, 190.0, 2.0), minDim)

        assertEquals(GlobeMath.MAX_LATITUDE, clamped.latitude, 1e-9)
        assertEquals(-170.0, clamped.longitude, 1e-9)
    }

    @Test
    fun clampLimitsZoomToWhatTheCameraCanDo() {
        val low = GlobeMath.clamp(GlobeCamera(0.0, 0.0, -5.0), minDim)
        val high = GlobeMath.clamp(GlobeCamera(0.0, 0.0, 30.0), minDim)

        assertEquals(GlobeMath.minZoom(minDim), low.zoom, 1e-9)
        assertEquals(GlobeMath.maxZoom(0.0), high.zoom, 1e-9)
    }

    @Test
    fun maxZoomReachesTheHandoverAtAnyLatitude() {
        for (lat in listOf(0.0, 45.0, 70.0, 85.0)) {
            val mapZoom = GlobeMath.mapZoomForGlobe(GlobeMath.maxZoom(lat), lat)
            assertTrue(GlobeTransition.shouldEnterMap(mapZoom))
        }
    }

    @Test
    fun wrapLongitudeFoldsIntoHalfOpenRange() {
        assertEquals(-180.0, GlobeMath.wrapLongitude(180.0), 1e-9)
        assertEquals(10.0, GlobeMath.wrapLongitude(370.0), 1e-9)
        assertEquals(-10.0, GlobeMath.wrapLongitude(-370.0), 1e-9)
    }

    @Test
    fun shortestLongitudeDeltaCrossesTheAntimeridian() {
        assertEquals(20.0, GlobeMath.shortestLongitudeDelta(170.0, -170.0), 1e-9)
        assertEquals(-20.0, GlobeMath.shortestLongitudeDelta(-170.0, 170.0), 1e-9)
    }

    @Test
    fun dragRightMovesTheCentreWestAndDragDownMovesItNorth() {
        val view = GlobeCamera(0.0, 0.0, 2.0)

        val panned = GlobeMath.pan(view, dx = 30.0, dy = 30.0, minDimension = minDim)

        assertTrue(panned.longitude < 0)
        assertTrue(panned.latitude > 0)
    }

    @Test
    fun dragCannotPushLatitudePastTheClamp() {
        val view = GlobeCamera(80.0, 0.0, 2.0)

        val panned = GlobeMath.pan(view, 0.0, 10_000.0, minDim)

        assertEquals(GlobeMath.MAX_LATITUDE, panned.latitude, 1e-9)
    }

    @Test
    fun pinchOutZoomsInAndPinchInZoomsOut() {
        val view = GlobeCamera(0.0, 0.0, 2.0)

        assertEquals(3.0, GlobeMath.zoomBy(view, 2.0, minDim).zoom, 1e-9)
        assertEquals(1.5, GlobeMath.zoomBy(view, 0.7071067811865476, minDim).zoom, 1e-6)
    }

    @Test
    fun flingSlowsDownAndNeverReverses() {
        val speed = 120.0

        val later = GlobeMath.decay(speed, 0.5)

        assertTrue(later in 0.0..speed)
        assertTrue(GlobeMath.decay(speed, 5.0) < 0.01 * speed)
    }

    @Test
    fun interpolateTakesTheShortWayRoundTheAntimeridian() {
        val mid = GlobeMath.interpolate(GlobeCamera(0.0, 170.0, 1.5), GlobeCamera(10.0, -170.0, 3.0), 0.5)

        assertEquals(-180.0, mid.longitude, 1e-9)
        assertEquals(5.0, mid.latitude, 1e-9)
        assertEquals(2.25, mid.zoom, 1e-9)
    }

    @Test
    fun limbOpacityFadesFromZeroAtTheHorizon() {
        val distance = 3.0
        val atHorizon = Vec3(0.94, 0.0, 1.0 / distance)
        val inside = Vec3(0.0, 0.0, 1.0)

        assertEquals(0f, GlobeMath.limbOpacity(atHorizon, distance), 1e-6f)
        assertEquals(1f, GlobeMath.limbOpacity(inside, distance), 1e-6f)
        assertFalse(GlobeMath.isVisible(atHorizon, distance))
    }

    @Test
    fun projectionMatrixScalesTheShorterSideToTheFieldOfView() {
        val m = GlobeMath.projectionMatrix(3.0, 1080f, 1920f)
        val f = 1.0 / Math.tan(Math.toRadians(GlobeMath.FOV_DEGREES / 2))

        assertEquals(f, m[0].toDouble(), 1e-5)
        assertEquals(f * 1080 / 1920, m[5].toDouble(), 1e-5)
        assertEquals(-1f, m[11], 0f)
    }

    @Test
    fun globeIsFullyOpaqueFarAndGoneAtTheHandover() {
        assertEquals(1f, GlobeTransition.globeOpacity(1.0), 0f)
        assertEquals(1f, GlobeTransition.globeOpacity(GlobeTransition.FADE_START_ZOOM), 1e-6f)
        assertEquals(0f, GlobeTransition.globeOpacity(GlobeTransition.ENTER_MAP_ZOOM), 1e-6f)
        assertEquals(0f, GlobeTransition.globeOpacity(5.0), 0f)
        val middle = GlobeTransition.globeOpacity((GlobeTransition.FADE_START_ZOOM + GlobeTransition.ENTER_MAP_ZOOM) / 2)
        assertEquals(0.5f, middle, 1e-6f)
    }

    @Test
    fun transitionThresholdsHaveHysteresis() {
        assertTrue(GlobeTransition.shouldEnterMap(3.0))
        assertFalse(GlobeTransition.shouldEnterMap(2.9))
        assertTrue(GlobeTransition.shouldEnterGlobe(1.99))
        assertFalse(GlobeTransition.shouldEnterGlobe(2.0))
        // A map just handed over to must not bounce straight back to the globe.
        assertFalse(GlobeTransition.shouldEnterGlobe(GlobeTransition.ENTER_MAP_ZOOM))
        assertTrue(GlobeTransition.mapShouldFollow(2.5))
        assertFalse(GlobeTransition.mapShouldFollow(2.4))
    }
}
