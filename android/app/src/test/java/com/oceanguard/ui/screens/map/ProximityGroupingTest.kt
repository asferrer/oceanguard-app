package com.oceanguard.ai.ui.screens.map

import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.HealthTrend
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.ZoneCluster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityGroupingTest {

    private val lat = 38.0
    private val lon = -0.5

    /** [n] points spread over about 20 m: 0.00018 degrees of latitude is 20 m. */
    private fun dive(n: Int) = List(n) { i ->
        GeoPoint(lat + 0.00018 * (i % 4) / 3.0, lon + 0.00018 * (i / 4) / 3.0 / 0.788)
    }

    @Test
    fun metresPerDpHalvesWithEachZoomAndShrinksTowardThePoles() {
        val z15 = ProximityGrouping.metresPerDp(15.0, 0.0)
        assertEquals(z15 / 2, ProximityGrouping.metresPerDp(16.0, 0.0), 1e-9)
        assertTrue(ProximityGrouping.metresPerDp(15.0, 60.0) < z15)
        assertEquals(2.39, z15, 0.01)
    }

    @Test
    fun thirteenPhotosWithinTwentyMetresAreOneGroupAtZoom15() {
        val groups = ProximityGrouping.group(dive(13), zoom = 15.0, pinDp = MapMarkerPlanner.PIN_DP)

        assertEquals(1, groups.size)
        assertEquals((0 until 13).toList(), groups.single().members)
    }

    @Test
    fun theSamePhotosSplitAtZoom20WhenTheyAreMoreThanAPinApart() {
        val groups = ProximityGrouping.group(dive(13), zoom = 20.0, pinDp = MapMarkerPlanner.PIN_DP)

        assertTrue("expected several groups, got ${groups.size}", groups.size > 1)
        assertEquals(13, groups.sumOf { it.members.size })
    }

    @Test
    fun zoomingInNeverMergesWhatWasApart() {
        val points = dive(13)
        var previous = 0
        for (zoom in listOf(13.0, 15.0, 17.0, 18.0, 19.0, 20.0, 21.0)) {
            val count = ProximityGrouping.group(points, zoom, MapMarkerPlanner.PIN_DP).size
            assertTrue("zoom $zoom regrouped", count >= previous)
            previous = count
        }
    }

    @Test
    fun aPhotoWithNoNeighbourStaysAloneAndFarOnesStaySeparate() {
        val points = listOf(GeoPoint(38.0, -0.5), GeoPoint(38.0001, -0.5), GeoPoint(38.01, -0.5))

        val groups = ProximityGrouping.group(points, 17.0, MapMarkerPlanner.PIN_DP)

        assertEquals(listOf(listOf(0, 1), listOf(2)), groups.map { it.members })
    }

    @Test
    fun chainsOfNeighboursJoinOneGroup() {
        // Each step is 40 m, under the 1.5 pins (about 80 m) at zoom 16, but the ends are 160 m apart.
        val step = 40.0 / 111_320.0
        val chain = List(5) { GeoPoint(lat + step * it, lon) }

        val groups = ProximityGrouping.group(chain, 16.0, MapMarkerPlanner.PIN_DP)

        assertEquals(1, groups.size)
    }

    @Test
    fun everyPointIsInExactlyOneGroupAndTheGroupSitsAtTheirMean() {
        val points = dive(13) + GeoPoint(39.0, 1.0)

        val groups = ProximityGrouping.group(points, 15.0, MapMarkerPlanner.PIN_DP)

        assertEquals(points.indices.toList(), groups.flatMap { it.members }.sorted())
        val first = groups.first()
        assertEquals(first.members.map { points[it].latitude }.average(), first.latitude, 1e-12)
    }

    @Test
    fun nothingToGroupGivesNothing() {
        assertTrue(ProximityGrouping.group(emptyList(), 15.0, MapMarkerPlanner.PIN_DP).isEmpty())
    }

    @Test
    fun zoomIsQuantisedSoAPinchDoesNotRegroupEveryFrame() {
        assertEquals(15.25, MapMarkerPlanner.quantizeZoom(15.3), 1e-9)
        assertEquals(15.25, MapMarkerPlanner.quantizeZoom(15.2), 1e-9)
        assertEquals(MapMarkerPlanner.quantizeZoom(15.26), MapMarkerPlanner.quantizeZoom(15.3), 0.0)
    }

    // ---- the markers built from it ----

    private fun session(id: Long, la: Double, lo: Double, health: Int) = DetectionSession(
        id = id,
        imageUri = "file://" + id + ".jpg",
        thumbnailUri = null,
        debrisList = emptyList(),
        totalCount = 1,
        healthScore = health,
        location = Location(la, lo),
        imageQuality = ImageQuality.GOOD,
        processingTimeMs = 1,
    )

    private fun zoneOf(sessions: List<DetectionSession>) =
        ZoneCluster(lat, lon, sessions, aggregateHealthScore = 60, trend = HealthTrend.STABLE)

    private fun pins(zone: ZoneCluster, index: Int = 0) = MapMarkerPlanner.plan(listOf(zone), expanded = true)
        .filterIsInstance<PinMarker>().map { it.copy(zoneIndex = index) }

    @Test
    fun stackedPhotosBecomeOneStudyZoneWithTheirAverageHealth() {
        val sessions = dive(13).mapIndexed { i, p -> session(i + 1L, p.latitude, p.longitude, 40 + i) }
        val zone = zoneOf(sessions)

        val markers = MapMarkerPlanner.groupStacked(pins(zone), 15.0)

        val group = markers.single() as PinGroupMarker
        assertEquals(13, group.members.size)
        assertEquals(46, group.healthScore)
    }

    @Test
    fun isolatedPhotosKeepTheirOwnPin() {
        val zone = zoneOf(listOf(session(1, 38.0, -0.5, 50), session(2, 38.02, -0.5, 50)))

        val markers = MapMarkerPlanner.groupStacked(pins(zone), 15.0)

        assertEquals(2, markers.size)
        assertTrue(markers.all { it is PinMarker })
    }

    @Test
    fun aStudyZoneOfAWholeZoneOpensThatZoneAndAPartOpensJustThosePhotos() {
        val sessions = dive(13).mapIndexed { i, p -> session(i + 1L, p.latitude, p.longitude, 50) } +
            session(99, 38.0, -0.4, 50)
        val zone = zoneOf(sessions)
        val markers = MapMarkerPlanner.groupStacked(pins(zone), 15.0)
        val group = markers.filterIsInstance<PinGroupMarker>().single()

        val part = MapMarkerPlanner.zoneFor(group, listOf(zone))

        assertNotNull(part)
        assertEquals(13, part!!.sessions.size)

        val whole = zoneOf(sessions.take(13))
        val all = MapMarkerPlanner.groupStacked(pins(whole), 15.0).single() as PinGroupMarker
        assertEquals(whole, MapMarkerPlanner.zoneFor(all, listOf(whole)))
    }

    @Test
    fun speciesAndPhotosNeverMergeWithEachOther() {
        val entry = com.oceanguard.ai.ui.screens.map.species.SpeciesEntry("a", "a", lat, lon, 2, 1L)
        val other = com.oceanguard.ai.ui.screens.map.species.SpeciesEntry("b", "b", lat + 0.00005, lon, 1, 2L)
        val spot = com.oceanguard.ai.ui.screens.map.species.SpeciesSpot(lat, lon, listOf(entry, other))
        val photo = session(1, lat, lon, 50)

        val markers = MapMarkerPlanner.groupStacked(
            MapMarkerPlanner.planAll(listOf(zoneOf(listOf(photo))), listOf(spot), expanded = true),
            15.0,
        )

        assertEquals(1, markers.filterIsInstance<PinMarker>().size)
        val group = markers.filterIsInstance<SpeciesGroupMarker>().single()
        assertEquals(3, group.observationCount)
        assertEquals(2, group.members.size)
    }
}
