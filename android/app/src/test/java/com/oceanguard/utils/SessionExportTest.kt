package com.oceanguard.ai.utils

import com.oceanguard.ai.data.AnalysisModels
import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class SessionExportTest {

    private val bottle = Debris(
        bbox = BoundingBox(0f, 0f, 10f, 10f),
        material = DebrisMaterial.PLASTIC,
        type = DebrisType.BOTTLE,
        confidence = 0.8765f,
    )

    private fun session(
        id: Long = 1,
        uri: String = "content://media/IMG_1.jpg",
        debris: List<Debris> = listOf(bottle),
        location: Location? = Location(39.5, 2.6),
        model: String? = AnalysisModels.RT_DETR_V2,
        health: Int = 70,
    ) = DetectionSession(
        id = id,
        imageUri = uri,
        debrisList = debris,
        totalCount = debris.size,
        healthScore = health,
        location = location,
        timestamp = Date(0),
        imageQuality = ImageQuality.GOOD,
        processingTimeMs = 4_000,
        modelName = model,
    )

    private fun rows(csv: String) = csv.trimEnd().lines()

    @Test
    fun csvHasHeaderAndOneRowPerObject() {
        val lines = rows(sessionsToCsv(listOf(session(debris = listOf(bottle, bottle)))))
        assertEquals(SESSIONS_CSV_HEADER, lines[0])
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains(",BOTTLE,PLASTIC,0.8765,70,RT-DETRv2"))
    }

    @Test
    fun csvLeavesGemmaConfidenceEmpty() {
        val line = rows(sessionsToCsv(listOf(session(model = AnalysisModels.GEMMA4_BASE))))[1]
        assertTrue(line.contains(",BOTTLE,PLASTIC,,70,Gemma 4 E2B"))
        assertFalse(line.contains("0.8765"))
    }

    @Test
    fun csvKeepsConfidenceWhenTheModelWasNotRecorded() {
        val line = rows(sessionsToCsv(listOf(session(model = null))))[1]
        assertTrue(line.contains("0.8765"))
    }

    @Test
    fun csvPhotoWithoutDetectionsHasNoHealth() {
        val line = rows(sessionsToCsv(listOf(session(debris = emptyList(), health = 100))))[1]
        assertTrue(line.endsWith(",,,,,RT-DETRv2"))
    }

    @Test
    fun csvQuotesCellsThatNeedIt() {
        assertEquals("plain", csvCell("plain"))
        assertEquals("\"a,b\"", csvCell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", csvCell("say \"hi\""))
        val line = rows(sessionsToCsv(listOf(session(uri = "content://x/photo,1.jpg"))))[1]
        assertTrue(line.contains("\"photo,1.jpg\""))
    }

    @Test
    fun csvWritesCoordinatesWithAPointWhateverTheLocale() {
        val line = rows(sessionsToCsv(listOf(session(location = Location(39.5, -2.25)))))[1]
        assertTrue(line.contains(",39.500000,-2.250000,"))
    }

    @Test
    fun geoJsonSkipsPhotosWithoutLocation() {
        val json = sessionsToExportGeoJson(listOf(session(id = 1), session(id = 2, location = null)))
        assertTrue(json.contains("\"sessionId\":1"))
        assertFalse(json.contains("\"sessionId\":2"))
    }

    @Test
    fun geoJsonPutsLongitudeFirst() {
        val json = sessionsToExportGeoJson(listOf(session(location = Location(39.5, 2.6))))
        assertTrue(json.contains("\"coordinates\":[2.600000,39.500000]"))
    }

    @Test
    fun geoJsonCarriesModelAndOmitsHealthWhenNothingWasFound() {
        val json = sessionsToExportGeoJson(listOf(session(debris = emptyList(), health = 100)))
        assertTrue(json.contains("\"model\":\"RT-DETRv2\""))
        assertFalse(json.contains("healthScore"))
        assertTrue(json.contains("\"totalCount\":0"))
    }

    @Test
    fun geoJsonWithNoLocatedPhotoIsAnEmptyCollection() {
        assertEquals(
            "{\"type\":\"FeatureCollection\",\"features\":[]}",
            sessionsToExportGeoJson(listOf(session(location = null))),
        )
    }

    @Test
    fun jsonStringsAreEscaped() {
        assertEquals("\"a\\\"b\\\\c\\n\"", jsonString("a\"b\\c\n"))
        assertEquals("\"\\u0001\"", jsonString("\u0001"))
    }
}
