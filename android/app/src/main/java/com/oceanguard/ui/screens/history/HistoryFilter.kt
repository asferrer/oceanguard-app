package com.oceanguard.ai.ui.screens.history

import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the history search box and its chips ask for. Every part that is set must match. */
data class HistoryFilter(
    val query: String = "",
    val debrisType: DebrisType? = null,
    val placeName: String? = null,
    val dateRangeStartMs: Long? = null,
    val dateRangeEndMs: Long? = null,
) {
    /** True when anything narrows the list: what the "clear filters" action undoes. */
    val isActive: Boolean
        get() = query.isNotBlank() || debrisType != null || placeName != null || hasDateRange

    val hasDateRange: Boolean
        get() = dateRangeStartMs != null || dateRangeEndMs != null

    fun matches(session: DetectionSession, place: String?): Boolean =
        matchesText(session, place) &&
            (debrisType == null || session.debrisList.any { it.type == debrisType }) &&
            (placeName == null || place == placeName) &&
            matchesDate(session)

    /** Each word typed must appear somewhere in the analysis, in any order. */
    private fun matchesText(session: DetectionSession, place: String?): Boolean {
        val words = query.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = searchableText(session, place)
        return words.all { it in haystack }
    }

    private fun matchesDate(session: DetectionSession): Boolean {
        if (!hasDateRange) return true
        val time = session.timestamp.time
        return time >= (dateRangeStartMs ?: 0L) && time <= (dateRangeEndMs ?: Long.MAX_VALUE)
    }

    fun apply(sessions: List<DetectionSession>, placeNames: Map<Long, String>): List<DetectionSession> =
        sessions.filter { matches(it, placeNames[it.id]) }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Lower-case text a search can hit: type and material names (underscores read as spaces, so
 * "plastic bag" finds PLASTIC_BAG), the risk level, the raw label the model emitted and the place.
 */
internal fun searchableText(session: DetectionSession, place: String?): String = buildString {
    session.debrisList.forEach { debris ->
        append(debris.type.name.replace('_', ' ')).append(' ')
        append(debris.material.name.replace('_', ' ')).append(' ')
        debris.subType?.let { append(it.name.replace('_', ' ')).append(' ') }
        debris.rawLabel?.let { append(it.replace('_', ' ')).append(' ') }
    }
    append(session.getRiskLevel().name).append(' ')
    place?.let { append(it) }
}.lowercase()

/** Calendar day of [timeMs] in [zone]. */
internal fun dayOf(timeMs: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()
