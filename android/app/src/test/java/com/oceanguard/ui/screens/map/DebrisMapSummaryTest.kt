package com.oceanguard.ai.ui.screens.map

import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.HealthTrend
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.ZoneCluster
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebrisMapSummaryTest {

    private fun debris(type: DebrisType) = Debris(BoundingBox(0f, 0f, 1f, 1f), DebrisMaterial.PLASTIC, type, 0.9f)

    private fun session(id: Long, at: Long, health: Int, vararg types: DebrisType, thumb: String? = null) = DetectionSession(
        id = id,
        imageUri = "file://photo-$id.jpg",
        thumbnailUri = thumb,
        debrisList = types.map { debris(it) },
        totalCount = types.size,
        healthScore = health,
        location = Location(38.0, -0.5),
        timestamp = Date(at),
        imageQuality = ImageQuality.GOOD,
        processingTimeMs = 100,
    )

    @Test
    fun typesAreListedMostObjectsFirstWithTheirPhotosNewestFirst() {
        val a = session(1, 10, 60, DebrisType.BOTTLE, DebrisType.BOTTLE, DebrisType.CAN)
        val b = session(2, 30, 40, DebrisType.BOTTLE, thumb = "file://thumb-2.jpg")
        val c = session(3, 20, 80, DebrisType.CAN)

        val types = DebrisMapSummary.typesOf(listOf(a, b, c))

        assertEquals(listOf(DebrisType.BOTTLE, DebrisType.CAN), types.map { it.type })
        val bottle = types.first()
        assertEquals(3, bottle.objectCount)
        assertEquals(
            listOf(DebrisPhoto(2, "file://thumb-2.jpg", 30, 1), DebrisPhoto(1, "file://photo-1.jpg", 10, 2)),
            bottle.photos,
        )
        assertEquals(30L, bottle.lastSeenMs)
        assertEquals(listOf(3L, 1L), types[1].photos.map { it.sessionId })
    }

    @Test
    fun subTypesCountUnderTheirMarineDexType() {
        val types = DebrisMapSummary.typesOf(listOf(session(1, 10, 50, DebrisType.PLASTIC_BAG, DebrisType.PLASTIC_DEBRIS)))

        assertEquals(DebrisType.PLASTIC_DEBRIS, types.single().type)
        assertEquals(2, types.single().objectCount)
    }

    @Test
    fun aPlaceWithoutDetectionsListsNothingAndAveragesItsHealth() {
        val place = DebrisPlace(listOf(session(1, 10, 60), session(2, 20, 81)), zone = null)

        assertTrue(place.types.isEmpty())
        assertEquals(70, place.healthScore)
    }

    @Test
    fun aStudyZoneMarkerResolvesToItsSessions() {
        val s1 = session(1, 10, 60, DebrisType.CAN)
        val s2 = session(2, 20, 60, DebrisType.CAN)
        val zone = ZoneCluster(38.0, -0.5, listOf(s1, s2), aggregateHealthScore = 60, trend = HealthTrend.STABLE)
        val pin = { s: DetectionSession -> PinMarker(s.id, 0, 38.0, -0.5, s.imageUri, s.healthScore) }

        val group = PinGroupMarker(listOf(pin(s2), pin(s1)), 38.0, -0.5)

        assertEquals(listOf(2L, 1L), DebrisMapSummary.sessionsOf(group, listOf(zone)).map { it.id })
    }
}
