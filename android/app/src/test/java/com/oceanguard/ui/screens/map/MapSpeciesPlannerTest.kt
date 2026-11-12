package com.oceanguard.ai.ui.screens.map

import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.HealthTrend
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.ZoneCluster
import com.oceanguard.ai.ui.screens.map.species.SpeciesEntry
import com.oceanguard.ai.ui.screens.map.species.SpeciesSpot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSpeciesPlannerTest {

    private fun zone(lat: Double, lon: Double): ZoneCluster {
        val s = DetectionSession(
            id = 1,
            imageUri = "file://1.jpg",
            thumbnailUri = null,
            debrisList = emptyList(),
            totalCount = 1,
            healthScore = 70,
            location = Location(lat, lon),
            imageQuality = ImageQuality.GOOD,
            processingTimeMs = 1,
        )
        return ZoneCluster(lat, lon, listOf(s), aggregateHealthScore = 70, trend = HealthTrend.STABLE)
    }

    private fun entry(key: String, count: Int) = SpeciesEntry(key, key, 10.0, 20.0, count, 5L)

    private val spots = listOf(
        SpeciesSpot(10.0, 20.0, listOf(entry("a", 3), entry("b", 1))),
        SpeciesSpot(30.0, 40.0, listOf(entry("c", 2))),
    )

    @Test
    fun farOutEachSpeciesSpotIsOneClusterWithItsCounts() {
        val markers = MapMarkerPlanner.planSpecies(spots, expanded = false)

        assertEquals(2, markers.size)
        val first = markers[0] as SpeciesClusterMarker
        assertEquals(4, first.observationCount)
        assertEquals(2, first.speciesCount)
    }

    @Test
    fun closeUpEachSpeciesOfASpotIsItsOwnPin() {
        val markers = MapMarkerPlanner.planSpecies(spots, expanded = true)

        assertEquals(3, markers.size)
        assertTrue(markers.all { it is SpeciesPinMarker })
        assertEquals(3, (markers[0] as SpeciesPinMarker).observationCount)
    }

    @Test
    fun debrisAndSpeciesAreMergedWithDistinctKeys() {
        val markers = MapMarkerPlanner.planAll(listOf(zone(1.0, 2.0)), spots, expanded = false)

        assertEquals(3, markers.size)
        assertTrue(markers[0] is ClusterMarker)
        assertEquals(markers.size, markers.map { it.key }.toSet().size)
    }

    @Test
    fun speciesOnlyAndDebrisOnlyPlansDoNotMixKinds() {
        assertTrue(MapMarkerPlanner.planAll(emptyList(), spots, false).all { it is SpeciesClusterMarker })
        assertTrue(MapMarkerPlanner.planAll(listOf(zone(1.0, 2.0)), emptyList(), false).all { it is ClusterMarker })
    }

    @Test
    fun theMapFramesZonesAndSpeciesTogether() {
        val target = MapMarkerPlanner.fitTarget(listOf(zone(0.0, 0.0)), listOf(10.0 to 10.0))

        assertNotNull(target)
        assertEquals(5.0, target!!.latitude, 1e-9)
        assertEquals(5.0, target.longitude, 1e-9)
    }

    @Test
    fun speciesAloneCanBeFramedAndNothingFramesToNull() {
        assertNotNull(MapMarkerPlanner.fitTarget(emptyList(), listOf(1.0 to 2.0)))
        assertNull(MapMarkerPlanner.fitTarget(emptyList(), emptyList()))
    }
}
