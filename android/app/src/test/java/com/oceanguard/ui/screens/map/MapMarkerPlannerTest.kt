package com.oceanguard.ai.ui.screens.map

import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.HealthTrend
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.ZoneCluster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMarkerPlannerTest {

    private fun session(id: Long, lat: Double, lon: Double, thumb: String? = null) = DetectionSession(
        id = id,
        imageUri = "file://photo-$id.jpg",
        thumbnailUri = thumb,
        debrisList = emptyList(),
        totalCount = 2,
        healthScore = 70,
        location = Location(lat, lon),
        imageQuality = ImageQuality.GOOD,
        processingTimeMs = 100,
    )

    private fun zone(vararg sessions: DetectionSession, lat: Double = 38.0, lon: Double = -0.5) =
        ZoneCluster(lat, lon, sessions.toList(), aggregateHealthScore = 70, trend = HealthTrend.STABLE)

    @Test
    fun collapsedMapShowsOneClusterPerZoneWithItsPhotoCount() {
        val zones = listOf(zone(session(1, 38.0, -0.5), session(2, 38.0, -0.5)), zone(session(3, 39.0, 1.0)))

        val markers = MapMarkerPlanner.plan(zones, expanded = false)

        assertEquals(2, markers.size)
        val first = markers[0] as ClusterMarker
        assertEquals(2, first.sessionCount)
        assertEquals(0, first.zoneIndex)
        assertEquals(1, (markers[1] as ClusterMarker).sessionCount)
    }

    @Test
    fun expandedMapShowsOnePinPerSessionAtItsOwnCoordinate() {
        val zones = listOf(zone(session(1, 38.001, -0.502), session(2, 38.003, -0.498)))

        val pins = MapMarkerPlanner.plan(zones, expanded = true).map { it as PinMarker }

        assertEquals(listOf(1L, 2L), pins.map { it.sessionId })
        assertEquals(38.001, pins[0].latitude, 0.0)
        assertEquals(-0.498, pins[1].longitude, 0.0)
    }

    @Test
    fun pinPrefersTheAnnotatedThumbnailOverTheOriginal() {
        val pins = MapMarkerPlanner.plan(
            listOf(zone(session(1, 38.0, -0.5, thumb = "file://thumb.jpg"), session(2, 38.0, -0.5))),
            expanded = true,
        ).map { it as PinMarker }

        assertEquals("file://thumb.jpg", pins[0].imageUri)
        assertEquals("file://photo-2.jpg", pins[1].imageUri)
    }

    @Test
    fun noZonesMeansNoMarkersAndNothingToFit() {
        assertTrue(MapMarkerPlanner.plan(emptyList(), expanded = false).isEmpty())
        assertTrue(MapMarkerPlanner.plan(emptyList(), expanded = true).isEmpty())
        assertNull(MapMarkerPlanner.fitTarget(emptyList()))
    }

    @Test
    fun zonesOpenExactlyAtTheExpandZoom() {
        assertFalse(MapMarkerPlanner.isExpanded(MapMarkerPlanner.EXPAND_ZOOM - 0.01))
        assertTrue(MapMarkerPlanner.isExpanded(MapMarkerPlanner.EXPAND_ZOOM))
        assertTrue(MapMarkerPlanner.FLY_ZOOM > MapMarkerPlanner.EXPAND_ZOOM)
    }

    @Test
    fun clusterGrowsOneStepPerDigitAndNeverBelowTheTouchTarget() {
        assertEquals(48, MapMarkerPlanner.clusterDiameterDp(0))
        assertEquals(48, MapMarkerPlanner.clusterDiameterDp(9))
        assertEquals(56, MapMarkerPlanner.clusterDiameterDp(15))
        assertEquals(64, MapMarkerPlanner.clusterDiameterDp(150))
        assertEquals(72, MapMarkerPlanner.clusterDiameterDp(1_000_000))
    }

    @Test
    fun onlyMarkersInsideTheMapPlusMarginAreKept() {
        assertTrue(MapMarkerPlanner.isOnScreen(100f, 100f, 400f, 800f, 40f))
        assertTrue(MapMarkerPlanner.isOnScreen(-30f, 820f, 400f, 800f, 40f))
        assertFalse(MapMarkerPlanner.isOnScreen(-60f, 100f, 400f, 800f, 40f))
        assertFalse(MapMarkerPlanner.isOnScreen(100f, 900f, 400f, 800f, 40f))
    }

    @Test
    fun coordinatesUseAPointAndFiveDecimalsWhateverTheDeviceLanguage() {
        assertEquals("38.12345, -0.50000", formatCoordinates(38.12345, -0.5))
    }

    @Test
    fun fitTargetCentresOnTheZonesAndPicksZoomFromTheirSpread() {
        val single = MapMarkerPlanner.fitTarget(listOf(zone(session(1, 38.0, -0.5))))!!
        assertEquals(14.0, single.zoom, 0.0)

        val close = listOf(zone(session(1, 38.0, -0.5), lat = 38.0, lon = -0.5), zone(session(2, 38.0, -0.4), lat = 38.0, lon = -0.4))
        val closeTarget = MapMarkerPlanner.fitTarget(close)!!
        assertEquals(12.0, closeTarget.zoom, 0.0)
        assertEquals(-0.45, closeTarget.longitude, 1e-9)

        val far = listOf(zone(session(1, 0.0, 0.0), lat = 0.0, lon = 0.0), zone(session(2, 0.0, 40.0), lat = 0.0, lon = 40.0))
        assertEquals(3.0, MapMarkerPlanner.fitTarget(far)!!.zoom, 0.0)
    }

    @Test
    fun `debris and species markers on the same spot are drawn side by side`() {
        val half = MapMarkerPlanner.SIDE_BY_SIDE_DP / 2
        val debris = listOf(100f to 200f)
        assertEquals(half, MapMarkerPlanner.sideBySideShift(105f, 203f, true, debris), 0f)
        assertEquals(-half, MapMarkerPlanner.sideBySideShift(100f, 200f, false, listOf(105f to 203f)), 0f)
        assertEquals(0f, MapMarkerPlanner.sideBySideShift(300f, 200f, true, debris), 0f)
        assertEquals(0f, MapMarkerPlanner.sideBySideShift(100f, 200f, false, emptyList()), 0f)
        assertTrue(SpeciesClusterMarker(0, 0.0, 0.0, 1, 1).isSpecies)
        assertFalse(ClusterMarker(0, 0.0, 0.0, 1, 50).isSpecies)
    }
}
