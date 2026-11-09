package com.oceanguard.ai.inference

import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolSet

/**
 * [ToolSet] of the species report: every method returns facts pre-computed in
 * [SpeciesReportContext] from the Room observations and the catalogue. The report may only state
 * the species and figures these return; nothing is looked up by the model.
 *
 * Not a singleton: one instance per report. Methods are synchronous (LiteRT-LM calls them by
 * reflection); R8 keeps them through the `ToolSet` rule in `proguard-rules.pro`.
 */
@OptIn(ExperimentalApi::class)
class OceanGuardSpeciesTools(private val ctx: SpeciesReportContext) : ToolSet {

    @Tool(description = "Get the totals of the species observations of this area and period: observations, photos, distinct species, taxonomic groups, species with a threatened IUCN status (VU, EN, CR), species seen outside their usual range and how many observations carry a GPS position. Always call this first.")
    fun getSpeciesSummary(): Map<String, Any> = mapOf(
        "totalObservations" to ctx.totalObservations,
        "photos" to ctx.photoCount,
        "distinctSpecies" to ctx.species.size,
        "taxonomicGroups" to ctx.groups.size,
        "threatenedSpecies" to ctx.threatened.size,
        "outOfRangeSpecies" to ctx.outOfRange.size,
        "uncatalogued" to ctx.uncataloguedCount,
        "locatedObservations" to ctx.locatedObservations,
        "periodStart" to (ctx.startMs?.let { SpeciesReportContext.isoDate(it) } ?: ""),
        "periodEnd" to (ctx.endMs?.let { SpeciesReportContext.isoDate(it) } ?: ""),
    )

    @Tool(description = "Get the complete list of species observed in this area and period with scientific and common name, taxonomic group, IUCN status, number of observations and photos, observations outside the usual range, mean confidence and first and last sighting. The items array is the authoritative and complete list: never mention a species that is not in it.")
    fun getSpeciesList(): Map<String, Any> {
        val items = ctx.species.take(MAX_LISTED).map { s -> speciesRow(s) }
        return mapOf("count" to ctx.species.size, "items" to items)
    }

    @Tool(description = "Get the number of species and observations per taxonomic group (fish, sharks and rays, turtles, mammals, molluscs, corals...) actually observed. The items array is complete.")
    fun getGroupBreakdown(): Map<String, Any> = mapOf(
        "items" to ctx.groups.map { mapOf("group" to it.group, "species" to it.species, "observations" to it.observations) },
    )

    @Tool(description = "Get the observed species whose IUCN Red List status is threatened (VU vulnerable, EN endangered, CR critically endangered). Returns an empty list when none was observed; do not name any other species as threatened.")
    fun getThreatenedSpecies(): Map<String, Any> =
        mapOf("count" to ctx.threatened.size, "items" to ctx.threatened.map { speciesRow(it) })

    @Tool(description = "Get the observed species that were identified outside their usual distribution range. Returns an empty list when none was; do not name any other species as out of range.")
    fun getOutOfRangeSpecies(): Map<String, Any> =
        mapOf("count" to ctx.outOfRange.size, "items" to ctx.outOfRange.map { speciesRow(it) })

    @Tool(description = "Get up to 20 georeferenced sightings (observation id, species, latitude, longitude, date), newest first. The items array is the only source of positions.")
    fun getSpeciesLocations(): Map<String, Any> = mapOf(
        "count" to ctx.locations.size,
        "items" to ctx.locations.map {
            mapOf(
                "observationId" to it.observationId,
                "scientificName" to it.scientificName,
                "lat" to it.lat,
                "lon" to it.lon,
                "date" to SpeciesReportContext.isoDate(it.dateMs),
            )
        },
    )

    internal fun speciesRow(s: SpeciesFact): Map<String, Any> = mapOf(
        "scientificName" to s.scientificName,
        "commonName" to s.commonName,
        "group" to (s.group ?: ""),
        "iucn" to (s.iucn ?: ""),
        "observations" to s.observations,
        "photos" to s.photos,
        "outOfRangeObservations" to s.outOfRange,
        "meanConfidencePercent" to s.avgConfidencePercent,
        "firstSeen" to SpeciesReportContext.isoDate(s.firstSeenMs),
        "lastSeen" to SpeciesReportContext.isoDate(s.lastSeenMs),
    )

    companion object {
        /** Rows of the species list given to the model and rendered in the table. */
        const val MAX_LISTED = 25

        internal fun cooccurrence(ctx: SpeciesReportContext): Map<String, Any> = mapOf(
            "count" to ctx.hotspots.size,
            "items" to ctx.hotspots.map { h ->
                mapOf(
                    "sessionId" to h.sessionId,
                    "date" to SpeciesReportContext.isoDate(h.dateMs),
                    "lat" to h.lat,
                    "lon" to h.lon,
                    "debrisCount" to h.debrisCount,
                    "healthScore" to h.healthScore,
                    "speciesNearby" to h.nearbySpecies.map { (n, c) -> mapOf("scientificName" to n, "observations" to c) },
                )
            },
        )
    }
}

/**
 * [ToolSet] of the combined report: the debris facts of the area next to the species facts and
 * their co-occurrence. The methods delegate, so the numbers are the ones each kind of report
 * already uses. Declared explicitly (not inherited) so LiteRT-LM and R8 see each `@Tool`.
 */
@OptIn(ExperimentalApi::class)
class OceanGuardCombinedTools(private val ctx: SpeciesReportContext) : ToolSet {
    private val species = OceanGuardSpeciesTools(ctx)
    private val debris = OceanGuardTools(requireNotNull(ctx.debris) { "A combined report needs debris sessions" })

    @Tool(description = "Get aggregate debris counts, average health score, dominant material/type and risk breakdown of the debris surveys in this area and period. Call this first for the debris part.")
    fun getDebrisSummary(): Map<String, Any> = debris.getDebrisSummary()

    @Tool(description = "Get counts and percentages for each debris MATERIAL detected in this area and period. The items array is the complete list.")
    fun getMaterialBreakdown(): Map<String, Any> = debris.getMaterialBreakdown()

    @Tool(description = "Get counts and percentages for each debris TYPE detected in this area and period. The items array is the complete list.")
    fun getTypeBreakdown(): Map<String, Any> = debris.getTypeBreakdown()

    @Tool(description = "Get the risk score, primary risk and urgency of each detected debris type, ranked.")
    fun getRiskAssessment(): Map<String, Any> = debris.getRiskAssessment()

    @Tool(description = "Get the totals of the species observations of this area and period: observations, photos, distinct species, groups, threatened and out-of-range species. Call this first for the biodiversity part.")
    fun getSpeciesSummary(): Map<String, Any> = species.getSpeciesSummary()

    @Tool(description = "Get the complete list of species observed in this area and period with names, group, IUCN status and counts. Never mention a species that is not in it.")
    fun getSpeciesList(): Map<String, Any> = species.getSpeciesList()

    @Tool(description = "Get the number of species and observations per taxonomic group actually observed.")
    fun getGroupBreakdown(): Map<String, Any> = species.getGroupBreakdown()

    @Tool(description = "Get the observed species with a threatened IUCN status (VU, EN, CR); empty when none.")
    fun getThreatenedSpecies(): Map<String, Any> = species.getThreatenedSpecies()

    @Tool(description = "Get the observed species identified outside their usual range; empty when none.")
    fun getOutOfRangeSpecies(): Map<String, Any> = species.getOutOfRangeSpecies()

    @Tool(description = "Get the debris sessions with the most objects and the species observed within 1 km of each. These are co-located facts, not causes: never state that debris explains the presence, absence or condition of a species.")
    fun getDebrisSpeciesCooccurrence(): Map<String, Any> = OceanGuardSpeciesTools.cooccurrence(ctx)
}
