package com.oceanguard.ai.inference

import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ZoneAggregator
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.data.species.entryFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a report covers: debris (the original), species, or both for the same area and period. */
enum class ReportType {
    DEBRIS, SPECIES, COMBINED;

    val hasDebris: Boolean get() = this != SPECIES
    val hasSpecies: Boolean get() = this != DEBRIS

    companion object {
        /** The type saved with a report; reports saved before v15 carry none and are debris reports. */
        fun fromKey(key: String?): ReportType = entries.firstOrNull { it.name == key } ?: DEBRIS
    }
}

/** Everything needed to write a species or a combined report of one area and period. */
data class SpeciesReportInput(
    val type: ReportType,
    val locationName: String,
    val centroidLat: Double,
    val centroidLon: Double,
    val observations: List<SpeciesObservation>,
    /** Debris sessions of the same area and period; empty for a species-only report. */
    val sessions: List<DetectionSession>,
    val dateRangeStartMs: Long?,
    val dateRangeEndMs: Long?,
)

/** One species as the tools and the report state it. */
data class SpeciesFact(
    val scientificName: String,
    val commonName: String,
    val group: String?,
    val iucn: String?,
    val observations: Int,
    val photos: Int,
    val outOfRange: Int,
    val avgConfidencePercent: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
) {
    val threatened: Boolean get() = iucn?.uppercase() in SpeciesReportContext.THREATENED
}

data class GroupFact(val group: String, val species: Int, val observations: Int)

data class LocationFact(val observationId: Long, val scientificName: String, val lat: Double, val lon: Double, val dateMs: Long)

/** A debris hotspot and the species seen close to it. Pure facts: nothing here says one explains the other. */
data class HotspotFact(
    val sessionId: Long,
    val dateMs: Long,
    val lat: Double,
    val lon: Double,
    val debrisCount: Int,
    val healthScore: Int,
    val nearbySpecies: List<Pair<String, Int>>,
)

/**
 * Pre-computed facts over the species observations (and, for a combined report, the debris
 * sessions) of one area and period. Built once; [OceanGuardSpeciesTools] and the data bundle only
 * read from it, so every figure a report states comes from here.
 */
class SpeciesReportContext(
    val type: ReportType,
    val observations: List<SpeciesObservation>,
    val sessions: List<DetectionSession>,
    val catalog: SpeciesCatalog,
    val language: String,
    val audience: ReportAudience,
    val startMs: Long? = null,
    val endMs: Long? = null,
) {
    /** Debris facts for a combined report: the same context the debris report is written from. */
    val debris: ToolReportContext? =
        if (type.hasDebris && sessions.isNotEmpty()) {
            ToolReportContext(ReportGenerator.canonicalizeSessions(sessions), language, audience)
        } else {
            null
        }

    val totalObservations: Int = observations.size
    val photoCount: Int = observations.map { it.imageUri }.distinct().size
    val uncataloguedCount: Int = observations.count { it.uncatalogued || catalog.entryFor(it) == null }

    val species: List<SpeciesFact> = observations
        .groupBy { it.scientificName }
        .map { (name, rows) -> speciesFact(name, rows) }
        .sortedWith(compareByDescending<SpeciesFact> { it.observations }.thenBy { it.scientificName })

    val groups: List<GroupFact> = species
        .groupBy { it.group ?: UNKNOWN_GROUP }
        .map { (g, rows) -> GroupFact(g, rows.size, rows.sumOf { it.observations }) }
        .sortedWith(compareByDescending<GroupFact> { it.observations }.thenBy { it.group })

    val threatened: List<SpeciesFact> = species.filter { it.threatened }
    val outOfRange: List<SpeciesFact> = species.filter { it.outOfRange > 0 }

    val locations: List<LocationFact> = observations
        .filter { it.location != null }
        .sortedByDescending { it.timestamp.time }
        .take(MAX_LOCATIONS)
        .map { LocationFact(it.id, it.scientificName, it.location!!.latitude, it.location.longitude, it.timestamp.time) }

    val locatedObservations: Int = observations.count { it.location != null }

    /** The debris sessions with the most objects, each with the species seen within [NEAR_METERS]. */
    val hotspots: List<HotspotFact> = debris?.let { d -> buildHotspots(d.sessions) } ?: emptyList()

    /** Whether there is anything to say: species for a species report, both kinds for a combined one. */
    val hasData: Boolean get() = when (type) {
        ReportType.DEBRIS -> sessions.isNotEmpty()
        ReportType.SPECIES -> observations.isNotEmpty()
        ReportType.COMBINED -> observations.isNotEmpty() && sessions.isNotEmpty()
    }

    private fun speciesFact(name: String, rows: List<SpeciesObservation>): SpeciesFact {
        val entry: SpeciesCatalogEntry? = catalog.entryFor(rows.first())
        val common = entry?.commonNames?.get(language) ?: entry?.commonNames?.get("en") ?: name
        return SpeciesFact(
            scientificName = name,
            commonName = common,
            group = entry?.group,
            iucn = entry?.iucnStatus?.uppercase(),
            observations = rows.size,
            photos = rows.map { it.imageUri }.distinct().size,
            outOfRange = rows.count { it.outOfRange },
            avgConfidencePercent = Math.round(rows.map { it.confidence }.average() * 100).toInt(),
            firstSeenMs = rows.minOf { it.timestamp.time },
            lastSeenMs = rows.maxOf { it.timestamp.time },
        )
    }

    private fun buildHotspots(canon: List<DetectionSession>): List<HotspotFact> =
        canon.filter { it.location != null && it.totalCount > 0 }
            .sortedWith(compareByDescending<DetectionSession> { it.totalCount }.thenByDescending { it.timestamp.time })
            .take(MAX_HOTSPOTS)
            .map { s ->
                val loc = s.location!!
                val near = observations
                    .filter { o ->
                        o.location != null &&
                            ZoneAggregator.haversineDistance(loc.latitude, loc.longitude, o.location.latitude, o.location.longitude) <= NEAR_METERS
                    }
                    .groupingBy { it.scientificName }.eachCount()
                    .toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                HotspotFact(s.id, s.timestamp.time, loc.latitude, loc.longitude, s.totalCount, s.healthScore, near)
            }

    companion object {
        /** IUCN categories counted as threatened: vulnerable, endangered and critically endangered. */
        val THREATENED = setOf("VU", "EN", "CR")
        const val UNKNOWN_GROUP = "unknown"
        const val MAX_LOCATIONS = 20
        const val MAX_HOTSPOTS = 3
        const val NEAR_METERS = 1000.0

        fun isoDate(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
    }
}
