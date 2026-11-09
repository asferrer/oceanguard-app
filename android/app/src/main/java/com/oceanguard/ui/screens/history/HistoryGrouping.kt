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
    val species: List<SpeciesHistoryGroup> = emptyList(),
) {
    /** Debris analyses and species identifications of the day, newest first. */
    val entries: List<HistoryEntry> by lazy {
        (sessions.map { HistoryEntry.Debris(it) } + species.map { HistoryEntry.Species(it) })
            .sortedByDescending { it.timeMs }
    }
}

/** A card of the history: a debris analysis or a species identification. */
sealed class HistoryEntry {
    abstract val key: String
    abstract val timeMs: Long

    data class Debris(val session: DetectionSession) : HistoryEntry() {
        override val key: String get() = "s${session.id}"
        override val timeMs: Long get() = session.timestamp.time
    }

    data class Species(val group: SpeciesHistoryGroup) : HistoryEntry() {
        override val key: String get() = "sp${group.key}"
        override val timeMs: Long get() = group.timeMs
    }
}

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
        species: List<SpeciesHistoryGroup> = emptyList(),
    ): List<HistoryDayGroup> {
        val today = dayOf(nowMs, zone)
        val sessionsByDay = sessions.sortedByDescending { it.timestamp.time }.groupBy { dayOf(it.timestamp.time, zone) }
        val speciesByDay = species.sortedByDescending { it.timeMs }.groupBy { dayOf(it.timeMs, zone) }
        return (sessionsByDay.keys + speciesByDay.keys)
            .sortedDescending()
            .map { day ->
                HistoryDayGroup(day, bucketOf(day, today), sessionsByDay[day].orEmpty(), speciesByDay[day].orEmpty())
            }
    }

    private fun bucketOf(day: LocalDate, today: LocalDate): DayBucket = when (day) {
        today -> DayBucket.TODAY
        today.minusDays(1) -> DayBucket.YESTERDAY
        else -> DayBucket.OTHER
    }
}
