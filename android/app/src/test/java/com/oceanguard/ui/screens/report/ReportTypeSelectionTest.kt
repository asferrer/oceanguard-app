package com.oceanguard.ai.ui.screens.report

import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.settings.ReportPeriod
import com.oceanguard.ai.data.settings.ReportPeriodSelection
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.inference.ReportType
import com.oceanguard.ai.ui.screens.results.ExportBlock
import com.oceanguard.ai.ui.screens.results.ReportExportFormat
import com.oceanguard.ai.ui.screens.results.reportExportBlock
import com.oceanguard.ai.utils.combinedToGeoJson
import com.oceanguard.ai.utils.observationsToCsv
import com.oceanguard.ai.utils.observationsToGeoJson
import java.io.File
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Choosing the type of report: which areas, which data, when it can be written, and what it exports. */
class ReportTypeSelectionTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun session(id: Long, lat: Double, lon: Double, at: Long = now - day) = DetectionSession(
        id = id, imageUri = "file://$id.jpg",
        debrisList = listOf(Debris(BoundingBox(0f, 0f, 1f, 1f), DebrisMaterial.PLASTIC, DebrisType.BOTTLE, 0.9f)),
        totalCount = 1, healthScore = 70, timestamp = Date(at), imageQuality = ImageQuality.GOOD,
        processingTimeMs = 1, location = Location(lat, lon),
    )

    private fun obs(id: Long, lat: Double?, lon: Double?, at: Long = now - day, name: String = "Mola mola") = SpeciesObservation(
        id = id, imageUri = "content://$id", scientificName = name, cosineScore = 0.9f, confidence = 0.9f,
        idSource = "RAG_CORE", timestamp = Date(at), location = if (lat != null && lon != null) Location(lat, lon) else null,
    )

    private val catalog = SpeciesCatalog(File("unused.json")).apply {
        seedInMemory(listOf(SpeciesCatalogEntry("mola", 1L, "Mola mola", mapOf("en" to "Ocean sunfish", "es" to "Pez luna"), null, emptyList(), group = "bony_fish", iucnStatus = "LC")))
    }

    private val sessions = listOf(session(1, 36.0, -6.0), session(2, 40.0, 3.0))
    private val observations = listOf(obs(1, 36.0005, -6.0), obs(2, 36.0, -6.001), obs(3, null, null), obs(4, 10.0, 10.0))

    @Test
    fun `a debris report keeps the original zones`() {
        val areas = ReportAreas.build(ReportType.DEBRIS, sessions, observations)
        assertEquals(2, areas.size)
        assertTrue(areas.all { it.observations.isEmpty() })
    }

    @Test
    fun `a species report has the areas that hold located observations`() {
        val areas = ReportAreas.build(ReportType.SPECIES, sessions, observations)
        assertEquals(2, areas.size)
        assertEquals(listOf(2, 1), areas.map { it.observations.size }.sortedDescending())
        assertTrue(areas.all { it.zone.sessions.isEmpty() })
    }

    @Test
    fun `a combined report puts debris and species of the same place in one area`() {
        val areas = ReportAreas.build(ReportType.COMBINED, sessions, observations)
        val cadiz = areas.single { it.zone.sessions.any { s -> s.id == 1L } }
        assertEquals(2, cadiz.observations.size)
        assertEquals(1, cadiz.zone.sessions.size)
        assertEquals(3, areas.size)
    }

    @Test
    fun `each kind of report is ready only with the data it needs`() {
        val area = ReportAreas.build(ReportType.COMBINED, sessions, observations).single { it.zone.sessions.any { s -> s.id == 1L } }
        val all = ReportPeriodSelection(ReportPeriod.ALL)

        assertTrue(ReportSelection.ofArea(area, all, ReportType.DEBRIS, now).ready)
        assertTrue(ReportSelection.ofArea(area, all, ReportType.SPECIES, now).ready)
        assertTrue(ReportSelection.ofArea(area, all, ReportType.COMBINED, now).ready)

        val speciesOnly = ReportAreas.build(ReportType.SPECIES, sessions, observations).first { it.observations.size == 1 }
        assertTrue(ReportSelection.ofArea(speciesOnly, all, ReportType.SPECIES, now).ready)
        assertFalse(ReportSelection.ofArea(speciesOnly, all, ReportType.COMBINED, now).ready)
        assertFalse(ReportSelection.ofArea(null, all, ReportType.SPECIES, now).ready)
    }

    @Test
    fun `the period limits the observations like it limits the sessions`() {
        val old = listOf(obs(1, 36.0, -6.0, at = now - 60 * day), obs(2, 36.0, -6.0, at = now - 2 * day))
        val area = ReportAreas.build(ReportType.SPECIES, emptyList(), old).single()
        val week = ReportSelection.ofArea(area, ReportPeriodSelection(ReportPeriod.LAST_7_DAYS), ReportType.SPECIES, now)
        assertEquals(listOf(2L), week.observations.map { it.id })
        val all = ReportSelection.ofArea(area, ReportPeriodSelection(ReportPeriod.ALL), ReportType.SPECIES, now)
        assertEquals(2, all.observations.size)
    }

    @Test
    fun `a species selection leaves the debris sessions out`() {
        val area = ReportAreas.build(ReportType.COMBINED, sessions, observations).first { it.zone.sessions.isNotEmpty() && it.observations.isNotEmpty() }
        val species = ReportSelection.ofArea(area, ReportPeriodSelection(ReportPeriod.ALL), ReportType.SPECIES, now)
        assertTrue(species.sessions.isEmpty())
        assertTrue(species.observations.isNotEmpty())
    }

    @Test
    fun `the export of a species report is its observations`() {
        val located = listOf(obs(1, 36.0, -6.0))
        val unlocated = listOf(obs(2, null, null))
        assertEquals(ExportBlock.NO_SESSIONS, reportExportBlock(ReportType.SPECIES, ReportExportFormat.CSV, emptyList(), emptyList()))
        assertNull(reportExportBlock(ReportType.SPECIES, ReportExportFormat.CSV, emptyList(), located))
        assertEquals(ExportBlock.NO_LOCATION, reportExportBlock(ReportType.SPECIES, ReportExportFormat.GEOJSON, emptyList(), unlocated))
        assertNull(reportExportBlock(ReportType.SPECIES, ReportExportFormat.GEOJSON, emptyList(), located))
        assertNull(reportExportBlock(ReportType.COMBINED, ReportExportFormat.GEOJSON, emptyList(), located))
        assertNull(reportExportBlock(ReportType.SPECIES, ReportExportFormat.PDF, emptyList(), emptyList()))
    }

    @Test
    fun `the species CSV names each observation with the catalogue`() {
        val lines = observationsToCsv(listOf(obs(7, 36.0, -6.0)), catalog, "es").lines().filter { it.isNotEmpty() }
        assertEquals("observation_id,analysis_id,image_name,date,lat,lon,scientific_name,common_name,group,iucn,confidence,out_of_range", lines[0])
        assertTrue(lines[1], lines[1].startsWith("7,,7,"))
        assertTrue(lines[1], lines[1].endsWith("Mola mola,Pez luna,bony_fish,LC,0.9000,false"))
    }

    @Test
    fun `the GeoJSON of species and of a combined report`() {
        val species = observationsToGeoJson(listOf(obs(1, 36.0, -6.0), obs(2, null, null)), catalog, "en")
        assertEquals(1, Regex("\"kind\":\"species_observation\"").findAll(species).count())
        assertTrue(species.contains("\"commonName\":\"Ocean sunfish\""))
        val combined = combinedToGeoJson(sessions, listOf(obs(1, 36.0, -6.0)), catalog, "en")
        assertEquals(3, Regex("\"type\":\"Feature\"").findAll(combined).count())
        assertTrue(combined.contains("\"sessionId\":1"))
        assertEquals(1, Regex("species_observation").findAll(combined).count())
    }
}
