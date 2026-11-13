package com.oceanguard.ai.ui.screens.map.species

import com.oceanguard.ai.data.species.SpeciesObservation
import kotlin.math.floor

/** Which kinds of data the main map shows. */
enum class MapDataMode(val key: String) {
    DEBRIS("debris"),
    SPECIES("species"),
    BOTH("both"),
    ;

    val showsDebris: Boolean get() = this != SPECIES
    val showsSpecies: Boolean get() = this != DEBRIS

    /** The debris-type filter and the heat view only mean something for debris. */
    val offersDebrisControls: Boolean get() = showsDebris

    companion object {
        val DEFAULT = BOTH

        /** The mode stored under [key]; [DEFAULT] for nothing stored or a value this version does not know. */
        fun fromKey(key: String?): MapDataMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** The date range is the only filter that applies to species; the debris-type chip is for debris. */
object SpeciesMapFilter {
    fun apply(observations: List<SpeciesObservation>, startMs: Long?, endMs: Long?): List<SpeciesObservation> =
        observations.filter { obs ->
            val at = obs.timestamp.time
            obs.location != null && (startMs == null || at >= startMs) && (endMs == null || at <= endMs)
        }
}

/**
 * One species seen around one place.
 *
 * @param key Catalogue key, which is the BioDex route argument; null for an uncatalogued
 * identification, which has no BioDex page.
 * @param photos The photos it was identified in, newest first, each photo once.
 */
data class SpeciesEntry(
    val key: String?,
    val scientificName: String,
    val latitude: Double,
    val longitude: Double,
    val count: Int,
    val lastSeenMs: Long,
    val photos: List<SpeciesPhoto> = emptyList(),
)

/**
 * A photo in which a species was identified: the observation it opens, the picture to show and the
 * photo it comes from ([imageUri]), so the same photo is shown and counted once.
 */
data class SpeciesPhoto(val observationId: Long, val uri: String, val takenMs: Long, val imageUri: String = uri)

/** Every species seen inside one grid cell of about a kilometre, drawn as one cluster. */
data class SpeciesSpot(
    val latitude: Double,
    val longitude: Double,
    val entries: List<SpeciesEntry>,
) {
    val observationCount: Int get() = entries.sumOf { it.count }
}

/** Groups species observations into [SpeciesSpot]s, the way debris is grouped into zones. */
object SpeciesAggregator {
    /** About 1.1 km, the size of a debris zone. */
    const val CELL_DEGREES = 0.01

    /**
     * Groups the located [observations] by grid cell, and inside each cell by species.
     *
     * @param keyOf Catalogue key of an observation, or null when it has no catalogue entry.
     */
    fun spots(observations: List<SpeciesObservation>, keyOf: (SpeciesObservation) -> String?): List<SpeciesSpot> =
        observations
            .filter { it.location != null }
            .groupBy { cellOf(it) }
            .values
            .map { inCell -> spotOf(inCell, keyOf) }

    private fun cellOf(obs: SpeciesObservation): Pair<Long, Long> {
        val loc = checkNotNull(obs.location)
        return floor(loc.latitude / CELL_DEGREES).toLong() to floor(loc.longitude / CELL_DEGREES).toLong()
    }

    private fun spotOf(inCell: List<SpeciesObservation>, keyOf: (SpeciesObservation) -> String?): SpeciesSpot {
        val entries = inCell
            .groupBy { keyOf(it) ?: "name:" + it.scientificName }
            .values
            .map { rows -> entryOf(rows, keyOf(rows.first())) }
            .sortedByDescending { it.count }
        val lats = inCell.map { checkNotNull(it.location).latitude }
        val lons = inCell.map { checkNotNull(it.location).longitude }
        return SpeciesSpot(lats.average(), lons.average(), entries)
    }

    private fun entryOf(rows: List<SpeciesObservation>, key: String?): SpeciesEntry = SpeciesEntry(
        key = key,
        scientificName = rows.first().scientificName,
        latitude = rows.map { checkNotNull(it.location).latitude }.average(),
        longitude = rows.map { checkNotNull(it.location).longitude }.average(),
        count = rows.size,
        lastSeenMs = rows.maxOf { it.timestamp.time },
        photos = rows
            .sortedByDescending { it.timestamp.time }
            .distinctBy { it.imageUri }
            .map { SpeciesPhoto(it.id, it.thumbnailUri ?: it.imageUri, it.timestamp.time, it.imageUri) },
    )

    /** Distinct species across [spots], uncatalogued ones counted by name. */
    fun speciesCount(spots: List<SpeciesSpot>): Int =
        spots.flatMap { it.entries }.map { it.key ?: "name:" + it.scientificName }.distinct().size

    fun observationCount(spots: List<SpeciesSpot>): Int = spots.sumOf { it.observationCount }
}

/** What the map header counts. */
enum class SummaryKind { SESSIONS, ZONES, OBSERVATIONS, SPECIES }

data class SummaryPart(val kind: SummaryKind, val count: Int)

/** The header summary of the map, which counts what the chosen [MapDataMode] shows. */
object MapSummary {

    /**
     * The counts to write in the header, in order, left out when zero: analyses and zones for
     * debris, observations and species for species, both runs for both.
     */
    fun parts(mode: MapDataMode, sessions: Int, zones: Int, observations: Int, species: Int): List<SummaryPart> =
        buildList {
            if (mode.showsDebris && sessions > 0) {
                add(SummaryPart(SummaryKind.SESSIONS, sessions))
                if (zones > 0) add(SummaryPart(SummaryKind.ZONES, zones))
            }
            if (mode.showsSpecies && observations > 0) {
                add(SummaryPart(SummaryKind.OBSERVATIONS, observations))
                if (species > 0) add(SummaryPart(SummaryKind.SPECIES, species))
            }
        }
}
