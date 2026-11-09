package com.oceanguard.ai.inference.species

/**
 * Resolution level at which a species was matched during the biogeographic
 * filter in [SpeciesReferenceIndex].
 *
 * Stored as the enum name in [com.oceanguard.ai.data.species.SpeciesObservation.geoMatchLevel].
 *
 * Ordered from most specific to least specific:
 *   ECOREGION > PROVINCE > REALM > COSMOPOLITAN > GLOBAL
 */
enum class GeoMatchLevel {
    /** Species has confirmed occurrences in the exact resolved ecoregion. */
    ECOREGION,
    /** Match relaxed to province level (ecoregion had no hits). */
    PROVINCE,
    /** Match relaxed to realm level (province had no hits). */
    REALM,
    /** Species is marked cosmopolitan — present everywhere regardless of GPS. */
    COSMOPOLITAN,
    /** No GPS available, filter OFF, or the species has no distribution data: no geo weight. */
    GLOBAL,
    /** The photo's realm is outside every known range of the species. */
    OUT_OF_RANGE,
}
