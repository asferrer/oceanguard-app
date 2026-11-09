package com.oceanguard.ai.ui.screens.history

import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.data.species.displayNameFor
import com.oceanguard.ai.data.species.entryFor
import java.time.ZoneId

/**
 * One identification of the history: a photo or a gallery batch. Observations saved before the
 * analysis id existed carry none, so those are grouped by the day they were taken.
 */
data class SpeciesHistoryGroup(
    /** "a<analysisId>" for a run, "d<yyyy-MM-dd>" for old observations of one day. */
    val key: String,
    val analysisId: Long?,
    /** Moment of the newest observation of the group. */
    val timeMs: Long,
    val observations: List<SpeciesObservation>,
) {
    val photoCount: Int get() = observations.map { it.imageUri }.distinct().size
    val speciesCount: Int get() = observations.map { it.scientificName }.distinct().size

    /** The observation whose photo stands for the group on its card: the newest. */
    val cover: SpeciesObservation get() = observations.first()
}

/** The kind of analysis the history lists. */
enum class HistoryKind { ALL, DEBRIS, SPECIES }

/** Turns the flat list of species observations into the analyses the history shows. */
object SpeciesHistoryGrouping {

    fun group(observations: List<SpeciesObservation>, zone: ZoneId = ZoneId.systemDefault()): List<SpeciesHistoryGroup> =
        observations
            .sortedByDescending { it.timestamp.time }
            .groupBy { keyOf(it, zone) }
            .map { (key, rows) ->
                SpeciesHistoryGroup(
                    key = key,
                    analysisId = rows.first().analysisId,
                    timeMs = rows.maxOf { it.timestamp.time },
                    observations = rows,
                )
            }
            .sortedByDescending { it.timeMs }

    /** The observations of the group [key], newest first, for the detail screen. */
    fun observationsOf(
        key: String,
        observations: List<SpeciesObservation>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SpeciesObservation> =
        observations.filter { keyOf(it, zone) == key }.sortedByDescending { it.timestamp.time }

    fun keyOf(observation: SpeciesObservation, zone: ZoneId = ZoneId.systemDefault()): String =
        observation.analysisId?.let { "a$it" } ?: "d${dayOf(observation.timestamp.time, zone)}"
}

private val SEARCH_WORDS = Regex("\\s+")

/** Whether a species group passes the kind, the search words, the date range and the other chips. */
internal fun HistoryFilter.matchesSpecies(group: SpeciesHistoryGroup, catalog: SpeciesCatalog, language: String): Boolean {
    if (kind == HistoryKind.DEBRIS || debrisType != null || placeName != null) return false
    if (hasDateRange && (group.timeMs < (dateRangeStartMs ?: 0L) || group.timeMs > (dateRangeEndMs ?: Long.MAX_VALUE))) {
        return false
    }
    val words = query.trim().lowercase().split(SEARCH_WORDS).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = buildString {
        group.observations.forEach { o ->
            append(o.scientificName).append(' ').append(catalog.displayNameFor(o, language)).append(' ')
            catalog.entryFor(o)?.commonNames?.values?.forEach { append(it).append(' ') }
        }
    }.lowercase()
    return words.all { it in haystack }
}
