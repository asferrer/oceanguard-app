package com.oceanguard.ai.inference.species

import com.oceanguard.ai.data.species.SpeciesCatalogEntry

/**
 * Biogeographic prior for species identification.
 *
 * The prior is a weight `g` on the probability of each species (it is added as
 * `ln g` to the softmax logits `cosine / T` in [SpeciesReferenceIndex.search]),
 * so geography nudges close calls without overriding the image:
 *   ECOREGION    1.0  (recorded in the photo's ecoregion)
 *   PROVINCE     0.5  (same province)
 *   REALM        0.3  (same realm)
 *   OUT_OF_RANGE 0.1  (recorded only in other realms)
 *   COSMOPOLITAN 1.0  (wide-ranging: geography says nothing)
 *   GLOBAL       1.0  (no GPS, filter OFF, or no distribution data)
 *
 * Weights chosen on the validation split with the photos' real coordinates
 * (docs/SPECIES_ID.md). The former multiplier on the cosine (cosmopolitan 0.1,
 * applied even without GPS) made the 35 cosmopolitan species unreachable.
 */
object GeoPrior {

    /** Probability weight for [level]. Always in (0, 1]. */
    fun g(level: GeoMatchLevel): Float = when (level) {
        GeoMatchLevel.ECOREGION    -> 1.0f
        GeoMatchLevel.PROVINCE     -> 0.5f
        GeoMatchLevel.REALM        -> 0.3f
        GeoMatchLevel.OUT_OF_RANGE -> 0.1f
        GeoMatchLevel.COSMOPOLITAN -> 1.0f
        GeoMatchLevel.GLOBAL       -> 1.0f
    }

    /**
     * Returns the [GeoMatchLevel] for a species given the resolved [region].
     *
     * Logic (in priority order):
     * 1. No region (no GPS / filter OFF) → [GeoMatchLevel.GLOBAL].
     * 2. Cosmopolitan → [GeoMatchLevel.COSMOPOLITAN].
     * 3. Ecoregion in species.ecoregions → [GeoMatchLevel.ECOREGION].
     * 4. Any species ecoregion in same province → [GeoMatchLevel.PROVINCE].
     * 5. Any species ecoregion in same realm → [GeoMatchLevel.REALM].
     * 6. Species has no ecoregion data → [GeoMatchLevel.GLOBAL] (no penalty).
     * 7. Otherwise → [GeoMatchLevel.OUT_OF_RANGE].
     *
     * @param species   Catalog entry with distribution data.
     * @param region    Resolved marine region, or null if no GPS.
     * @param hierarchy Function that maps ecoregionId → (provinceId, realmId).
     *                  Injected so this class stays testable without file I/O.
     */
    fun matchLevel(
        species: SpeciesCatalogEntry,
        region: MarineRegion?,
        hierarchy: (ecoregionId: Int) -> Pair<Int, Int>?,
    ): GeoMatchLevel {
        if (region == null) return GeoMatchLevel.GLOBAL
        if (species.cosmopolitan) return GeoMatchLevel.COSMOPOLITAN
        if (species.ecoregions.isEmpty()) return GeoMatchLevel.GLOBAL

        if (region.ecoregionId in species.ecoregions) return GeoMatchLevel.ECOREGION

        val speciesProvRealmPairs = species.ecoregions.mapNotNull { hierarchy(it) }
        val speciesProvinces = speciesProvRealmPairs.map { it.first }.toSet()
        val speciesRealms = speciesProvRealmPairs.map { it.second }.toSet()

        if (region.provinceId in speciesProvinces) return GeoMatchLevel.PROVINCE
        if (region.realmId in speciesRealms) return GeoMatchLevel.REALM

        // Recorded only in other realms: still possible, but unlikely here.
        return GeoMatchLevel.OUT_OF_RANGE
    }
}
