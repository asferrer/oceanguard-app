package com.oceanguard.ai.ui.screens.history

import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.data.species.SpeciesObservation
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Species identifications in the history: grouping by analysis, old rows by day, and the kind filter. */
class SpeciesHistoryTest {

    private val utc: ZoneId = ZoneOffset.UTC

    private fun millis(date: String, time: String = "12:00:00") =
        LocalDateTime.parse("${date}T$time").toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun obs(id: Long, name: String, at: Long, analysis: Long?, image: String = "content://$id") =
        SpeciesObservation(
            id = id, imageUri = image, scientificName = name, analysisId = analysis,
            cosineScore = 0.9f, confidence = 0.9f, idSource = "RAG_CORE", timestamp = Date(at),
        )

    private fun session(id: Long, at: Long) = DetectionSession(
        id = id, imageUri = "file://$id.jpg",
        debrisList = listOf(Debris(BoundingBox(0f, 0f, 1f, 1f), DebrisMaterial.PLASTIC, DebrisType.BOTTLE, 0.9f)),
        totalCount = 1, healthScore = 70, timestamp = Date(at), imageQuality = ImageQuality.GOOD, processingTimeMs = 10,
    )

    private val catalog = SpeciesCatalog(File("unused.json")).apply {
        seedInMemory(
            listOf(
                SpeciesCatalogEntry(
                    "caretta_caretta", 1L, "Caretta caretta",
                    mapOf("en" to "Loggerhead turtle", "es" to "Tortuga boba"), null, emptyList(),
                ),
                SpeciesCatalogEntry("mola_mola", 2L, "Mola mola", mapOf("en" to "Ocean sunfish"), null, emptyList()),
            ),
        )
    }

    @Test
    fun aBatchIsOneGroupWithItsPhotosAndSpecies() {
        val t = millis("2026-11-05")
        val rows = listOf(
            obs(1, "Caretta caretta", t, analysis = 100, image = "content://a"),
            obs(2, "Mola mola", t + 1, analysis = 100, image = "content://b"),
            obs(3, "Mola mola", t + 2, analysis = 100, image = "content://b"),
            obs(4, "Mola mola", t + 900_000, analysis = 200, image = "content://c"),
        )
        val groups = SpeciesHistoryGrouping.group(rows, utc)

        assertEquals(listOf("a200", "a100"), groups.map { it.key })
        val batch = groups.last()
        assertEquals(3, batch.observations.size)
        assertEquals(2, batch.photoCount)
        assertEquals(2, batch.speciesCount)
        assertEquals(100L, batch.analysisId)
    }

    @Test
    fun oldObservationsWithoutAnalysisAreGroupedByDay() {
        val rows = listOf(
            obs(1, "Mola mola", millis("2026-11-05", "09:00:00"), null),
            obs(2, "Caretta caretta", millis("2026-11-05", "18:00:00"), null),
            obs(3, "Mola mola", millis("2026-11-03"), null),
        )
        val groups = SpeciesHistoryGrouping.group(rows, utc)

        assertEquals(listOf("d2026-11-05", "d2026-11-03"), groups.map { it.key })
        assertEquals(2, groups.first().observations.size)
        assertNull(groups.first().analysisId)
    }

    @Test
    fun observationsOfReturnsTheRowsOfOneGroupNewestFirst() {
        val rows = listOf(
            obs(1, "Mola mola", millis("2026-11-05", "09:00:00"), 100),
            obs(2, "Caretta caretta", millis("2026-11-05", "09:00:05"), 100),
            obs(3, "Mola mola", millis("2026-11-05", "10:00:00"), 200),
        )
        assertEquals(listOf(2L, 1L), SpeciesHistoryGrouping.observationsOf("a100", rows, utc).map { it.id })
    }

    @Test
    fun theDayGroupsInterleaveDebrisAndSpeciesNewestFirst() {
        val sessions = listOf(session(1, millis("2026-11-05", "08:00:00")))
        val species = SpeciesHistoryGrouping.group(
            listOf(
                obs(1, "Mola mola", millis("2026-11-05", "10:00:00"), 100),
                obs(2, "Mola mola", millis("2026-11-04", "10:00:00"), 200),
            ),
            utc,
        )
        val days = HistoryGrouping.groupByDay(sessions, millis("2026-11-05"), utc, species)

        assertEquals(2, days.size)
        assertEquals(listOf("spa100", "s1"), days[0].entries.map { it.key })
        assertTrue(days[1].sessions.isEmpty())
        assertEquals(listOf("spa200"), days[1].entries.map { it.key })
    }

    @Test
    fun aDayWithOnlySpeciesStillAppears() {
        val species = SpeciesHistoryGrouping.group(listOf(obs(1, "Mola mola", millis("2026-11-05"), 1)), utc)
        val days = HistoryGrouping.groupByDay(emptyList(), millis("2026-11-05"), utc, species)
        assertEquals(1, days.size)
        assertEquals(DayBucket.TODAY, days.single().bucket)
    }

    @Test
    fun theKindFilterSeparatesDebrisFromSpecies() {
        val group = SpeciesHistoryGrouping.group(listOf(obs(1, "Mola mola", millis("2026-11-05"), 1)), utc).single()
        val sessions = listOf(session(1, millis("2026-11-05")))

        assertTrue(HistoryFilter().matchesSpecies(group, catalog, "en"))
        assertFalse(HistoryFilter(kind = HistoryKind.DEBRIS).matchesSpecies(group, catalog, "en"))
        assertTrue(HistoryFilter(kind = HistoryKind.SPECIES).matchesSpecies(group, catalog, "en"))
        assertEquals(1, HistoryFilter(kind = HistoryKind.DEBRIS).apply(sessions, emptyMap()).size)
        assertEquals(0, HistoryFilter(kind = HistoryKind.SPECIES).apply(sessions, emptyMap()).size)
    }

    @Test
    fun theSearchFindsSpeciesByAnyCatalogueName() {
        val group = SpeciesHistoryGrouping.group(listOf(obs(1, "Caretta caretta", millis("2026-11-05"), 1)), utc).single()

        assertTrue(HistoryFilter(query = "tortuga").matchesSpecies(group, catalog, "en"))
        assertTrue(HistoryFilter(query = "loggerhead").matchesSpecies(group, catalog, "en"))
        assertTrue(HistoryFilter(query = "caretta").matchesSpecies(group, catalog, "es"))
        assertFalse(HistoryFilter(query = "sunfish").matchesSpecies(group, catalog, "en"))
    }

    @Test
    fun aDebrisTypeOrPlaceFilterHidesSpecies() {
        val group = SpeciesHistoryGrouping.group(listOf(obs(1, "Mola mola", millis("2026-11-05"), 1)), utc).single()
        assertFalse(HistoryFilter(debrisType = DebrisType.BOTTLE).matchesSpecies(group, catalog, "en"))
        assertFalse(HistoryFilter(placeName = "Cadiz").matchesSpecies(group, catalog, "en"))
    }

    @Test
    fun theDateRangeAppliesToSpecies() {
        val group = SpeciesHistoryGrouping.group(listOf(obs(1, "Mola mola", millis("2026-11-05"), 1)), utc).single()
        val inside = HistoryFilter(dateRangeStartMs = millis("2026-11-01"), dateRangeEndMs = millis("2026-11-30"))
        val outside = HistoryFilter(dateRangeStartMs = millis("2026-12-01"))
        assertTrue(inside.matchesSpecies(group, catalog, "en"))
        assertFalse(outside.matchesSpecies(group, catalog, "en"))
    }
}
