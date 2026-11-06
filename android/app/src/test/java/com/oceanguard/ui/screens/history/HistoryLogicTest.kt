package com.oceanguard.ai.ui.screens.history

import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ImageQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date

class HistoryLogicTest {

    private val utc: ZoneId = ZoneOffset.UTC

    private fun millis(date: String, time: String = "12:00:00") =
        java.time.LocalDateTime.parse("${date}T$time").toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun debris(type: DebrisType, material: DebrisMaterial) = Debris(
        bbox = BoundingBox(0f, 0f, 1f, 1f),
        material = material,
        type = type,
        confidence = 0.9f,
    )

    private fun session(id: Long, at: Long, vararg found: Debris, health: Int = 70) = DetectionSession(
        id = id,
        imageUri = "file://$id.jpg",
        debrisList = found.toList(),
        totalCount = found.size,
        healthScore = health,
        timestamp = Date(at),
        imageQuality = ImageQuality.GOOD,
        processingTimeMs = 10,
    )

    private val bag = session(1, millis("2026-11-05"), debris(DebrisType.PLASTIC_BAG, DebrisMaterial.PLASTIC))
    private val can = session(2, millis("2026-11-04"), debris(DebrisType.CAN, DebrisMaterial.METAL))

    @Test
    fun anEmptyFilterKeepsEverythingAndIsNotActive() {
        val filter = HistoryFilter()

        assertFalse(filter.isActive)
        assertEquals(listOf(bag, can), filter.apply(listOf(bag, can), emptyMap()))
    }

    @Test
    fun searchReadsUnderscoresAsSpacesAndIgnoresCase() {
        assertEquals(listOf(bag), HistoryFilter(query = "Plastic Bag").apply(listOf(bag, can), emptyMap()))
    }

    @Test
    fun everyWordTypedMustMatchSomewhereInTheAnalysis() {
        val filter = HistoryFilter(query = "can metal")

        assertEquals(listOf(can), filter.apply(listOf(bag, can), emptyMap()))
        assertTrue(HistoryFilter(query = "can plastic").apply(listOf(can), emptyMap()).isEmpty())
    }

    @Test
    fun searchAlsoFindsMaterialRiskAndPlace() {
        val places = mapOf(1L to "Tabarca", 2L to "Alicante")

        assertEquals(listOf(can), HistoryFilter(query = "alicante").apply(listOf(bag, can), places))
        assertEquals(listOf(can), HistoryFilter(query = "metal").apply(listOf(bag, can), places))
    }

    @Test
    fun chipsNarrowByTypePlaceAndDateRange() {
        val places = mapOf(1L to "Tabarca", 2L to "Alicante")
        val all = listOf(bag, can)

        assertEquals(listOf(can), HistoryFilter(debrisType = DebrisType.CAN).apply(all, places))
        assertEquals(listOf(bag), HistoryFilter(placeName = "Tabarca").apply(all, places))
        assertEquals(
            listOf(can),
            HistoryFilter(dateRangeStartMs = millis("2026-11-04", "00:00:00"), dateRangeEndMs = millis("2026-11-04", "23:59:59"))
                .apply(all, places),
        )
        assertTrue(HistoryFilter(placeName = "Tabarca").isActive)
    }

    @Test
    fun anOpenEndedDateRangeKeepsEverythingFromItsStart() {
        val filter = HistoryFilter(dateRangeStartMs = millis("2026-11-05", "00:00:00"))

        assertEquals(listOf(bag), filter.apply(listOf(bag, can), emptyMap()))
    }

    @Test
    fun daysAreSplitAtLocalMidnightNewestFirstWithTodayAndYesterdayNamed() {
        val now = millis("2026-11-06", "09:00:00")
        val morning = session(3, millis("2026-11-06", "08:00:00"))
        val night = session(4, millis("2026-11-05", "23:59:00"))
        val older = session(5, millis("2026-11-01"))

        val groups = HistoryGrouping.groupByDay(listOf(older, night, morning), now, utc)

        assertEquals(listOf(DayBucket.TODAY, DayBucket.YESTERDAY, DayBucket.OTHER), groups.map { it.bucket })
        assertEquals(LocalDate.of(2026, 11, 1), groups[2].day)
        assertEquals(listOf(morning), groups[0].sessions)
    }

    @Test
    fun withinADayTheLatestAnalysisComesFirst() {
        val now = millis("2026-11-06")
        val early = session(6, millis("2026-11-06", "07:00:00"))
        val late = session(7, millis("2026-11-06", "18:00:00"))

        val groups = HistoryGrouping.groupByDay(listOf(early, late), now, utc)

        assertEquals(1, groups.size)
        assertEquals(listOf(late, early), groups[0].sessions)
    }

    @Test
    fun noSessionsMeansNoGroups() {
        assertTrue(HistoryGrouping.groupByDay(emptyList(), millis("2026-11-06"), utc).isEmpty())
    }
}
