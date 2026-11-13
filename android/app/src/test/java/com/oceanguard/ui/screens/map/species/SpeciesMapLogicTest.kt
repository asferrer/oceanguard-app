package com.oceanguard.ai.ui.screens.map.species

import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.species.SpeciesObservation
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeciesMapLogicTest {

    private fun obs(
        id: Long,
        name: String,
        lat: Double?,
        lon: Double = 0.0,
        at: Long = 1_000L,
    ) = SpeciesObservation(
        id = id,
        imageUri = "file://" + id + ".jpg",
        scientificName = name,
        cosineScore = 0.9f,
        confidence = 0.9f,
        idSource = "RAG_CORE",
        timestamp = Date(at),
        location = lat?.let { Location(it, lon) },
    )

    private val keys = mapOf("Sparus aurata" to "sparus_aurata", "Mola mola" to "mola_mola")
    private val keyOf: (SpeciesObservation) -> String? = { keys[it.scientificName] }

    @Test
    fun modesSayWhichKindsTheyShow() {
        assertTrue(MapDataMode.DEBRIS.showsDebris && !MapDataMode.DEBRIS.showsSpecies)
        assertTrue(!MapDataMode.SPECIES.showsDebris && MapDataMode.SPECIES.showsSpecies)
        assertTrue(MapDataMode.BOTH.showsDebris && MapDataMode.BOTH.showsSpecies)
    }

    @Test
    fun debrisControlsGoAwayWhenOnlySpeciesAreShown() {
        assertFalse(MapDataMode.SPECIES.offersDebrisControls)
        assertTrue(MapDataMode.DEBRIS.offersDebrisControls)
        assertTrue(MapDataMode.BOTH.offersDebrisControls)
    }

    @Test
    fun aSavedModeComesBackAndAnUnknownOneFallsBackToTheDefault() {
        MapDataMode.entries.forEach { assertEquals(it, MapDataMode.fromKey(it.key)) }
        assertEquals(MapDataMode.DEFAULT, MapDataMode.fromKey(null))
        assertEquals(MapDataMode.DEFAULT, MapDataMode.fromKey("future_mode"))
    }

    @Test
    fun theDateRangeAppliesToSpeciesAndObservationsWithoutPositionAreDropped() {
        val all = listOf(
            obs(1, "Mola mola", 10.0, at = 100),
            obs(2, "Mola mola", 10.0, at = 500),
            obs(3, "Mola mola", null, at = 300),
        )

        assertEquals(listOf(1L, 2L), SpeciesMapFilter.apply(all, null, null).map { it.id })
        assertEquals(listOf(2L), SpeciesMapFilter.apply(all, 200, null).map { it.id })
        assertEquals(listOf(1L), SpeciesMapFilter.apply(all, null, 200).map { it.id })
        assertTrue(SpeciesMapFilter.apply(all, 1000, 2000).isEmpty())
    }

    @Test
    fun observationsInTheSameCellFormOneSpotWithOneEntryPerSpecies() {
        val spots = SpeciesAggregator.spots(
            listOf(
                obs(1, "Sparus aurata", 38.001, -0.501, at = 10),
                obs(2, "Sparus aurata", 38.002, -0.502, at = 30),
                obs(3, "Mola mola", 38.003, -0.503, at = 20),
            ),
            keyOf,
        )

        val spot = spots.single()
        assertEquals(3, spot.observationCount)
        assertEquals(2, spot.entries.size)
        val sparus = spot.entries.first()
        assertEquals("sparus_aurata", sparus.key)
        assertEquals(2, sparus.count)
        assertEquals(30L, sparus.lastSeenMs)
    }

    @Test
    fun eachEntryKeepsItsPhotosNewestFirstPreferringTheThumbnail() {
        val spots = SpeciesAggregator.spots(
            listOf(
                obs(1, "Sparus aurata", 38.001, -0.501, at = 10),
                obs(2, "Sparus aurata", 38.002, -0.502, at = 30).copy(thumbnailUri = "file://2_thumb.jpg"),
                obs(3, "Mola mola", 38.003, -0.503, at = 20),
            ),
            keyOf,
        )

        val sparus = spots.single().entries.first { it.key == "sparus_aurata" }
        assertEquals(
            listOf(SpeciesPhoto(2, "file://2_thumb.jpg", 30, "file://2.jpg"), SpeciesPhoto(1, "file://1.jpg", 10)),
            sparus.photos,
        )
        assertEquals(listOf(3L), spots.single().entries.first { it.key == "mola_mola" }.photos.map { it.observationId })
    }

    @Test
    fun twoObservationsOfASpeciesInOnePhotoShowThePhotoOnce() {
        val spots = SpeciesAggregator.spots(
            listOf(
                obs(1, "Sparus aurata", 38.001, -0.501, at = 10),
                obs(2, "Sparus aurata", 38.001, -0.501, at = 10).copy(imageUri = "file://1.jpg"),
            ),
            keyOf,
        )

        val entry = spots.single().entries.single()
        assertEquals(2, entry.count)
        assertEquals(1, entry.photos.size)
    }

    @Test
    fun farApartObservationsFormSeparateSpots() {
        val spots = SpeciesAggregator.spots(listOf(obs(1, "Mola mola", 38.0), obs(2, "Mola mola", 40.0)), keyOf)

        assertEquals(2, spots.size)
    }

    @Test
    fun anUncataloguedSpeciesHasNoKeyAndIsCountedByName() {
        val spots = SpeciesAggregator.spots(listOf(obs(1, "Unknown fish", 5.0), obs(2, "Unknown fish", 5.0)), keyOf)

        val entry = spots.single().entries.single()
        assertNull(entry.key)
        assertEquals(2, entry.count)
        assertEquals(1, SpeciesAggregator.speciesCount(spots))
    }

    @Test
    fun countsSumObservationsAndDistinctSpeciesAcrossSpots() {
        val spots = SpeciesAggregator.spots(
            listOf(obs(1, "Mola mola", 1.0), obs(2, "Sparus aurata", 1.0), obs(3, "Mola mola", 50.0)),
            keyOf,
        )

        assertEquals(3, SpeciesAggregator.observationCount(spots))
        assertEquals(2, SpeciesAggregator.speciesCount(spots))
    }

    @Test
    fun spotsWithoutAPositionAreNeverBuilt() {
        assertTrue(SpeciesAggregator.spots(listOf(obs(1, "Mola mola", null)), keyOf).isEmpty())
    }

    @Test
    fun debrisSummaryCountsAnalysesAndZonesOnly() {
        val parts = MapSummary.parts(MapDataMode.DEBRIS, sessions = 13, zones = 1, observations = 6, species = 3)

        assertEquals(listOf(SummaryPart(SummaryKind.SESSIONS, 13), SummaryPart(SummaryKind.ZONES, 1)), parts)
    }

    @Test
    fun speciesSummaryCountsObservationsAndSpeciesOnly() {
        val parts = MapSummary.parts(MapDataMode.SPECIES, sessions = 13, zones = 1, observations = 6, species = 3)

        assertEquals(listOf(SummaryPart(SummaryKind.OBSERVATIONS, 6), SummaryPart(SummaryKind.SPECIES, 3)), parts)
    }

    @Test
    fun bothSummaryCountsEverythingAndSkipsWhatIsEmpty() {
        val both = MapSummary.parts(MapDataMode.BOTH, 13, 1, 6, 3)
        val onlySpecies = MapSummary.parts(MapDataMode.BOTH, 0, 0, 6, 3)

        val all = listOf(SummaryKind.SESSIONS, SummaryKind.ZONES, SummaryKind.OBSERVATIONS, SummaryKind.SPECIES)
        assertEquals(all, both.map { it.kind })
        assertEquals(listOf(SummaryKind.OBSERVATIONS, SummaryKind.SPECIES), onlySpecies.map { it.kind })
        assertTrue(MapSummary.parts(MapDataMode.BOTH, 0, 0, 0, 0).isEmpty())
    }
}
