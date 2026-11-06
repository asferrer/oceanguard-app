package com.oceanguard.ai.ui.screens.history

import com.oceanguard.ai.data.DetectionSession
import java.time.LocalDate
import java.time.ZoneId

/** How a day group is titled: relative for the last two days, by date otherwise. */
enum class DayBucket { TODAY, YESTERDAY, OTHER }

/** The analyses of one calendar day, most recent first. */
data class HistoryDayGroup(
    val day: LocalDate,
    val bucket: DayBucket,
    val sessions: List<DetectionSession>,
)

/** Splits a history into calendar days, newest day first. */
object HistoryGrouping {

    /**
     * @param nowMs Instant that decides which day is "today"; injected so the grouping is testable.
     * @param zone Time zone whose midnight separates the days: the device's, as the user reads it.
     */
    fun groupByDay(
        sessions: List<DetectionSession>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<HistoryDayGroup> {
        val today = dayOf(nowMs, zone)
        return sessions
            .sortedByDescending { it.timestamp.time }
            .groupBy { dayOf(it.timestamp.time, zone) }
            .map { (day, daySessions) -> HistoryDayGroup(day, bucketOf(day, today), daySessions) }
    }

    private fun bucketOf(day: LocalDate, today: LocalDate): DayBucket = when (day) {
        today -> DayBucket.TODAY
        today.minusDays(1) -> DayBucket.YESTERDAY
        else -> DayBucket.OTHER
    }
}
