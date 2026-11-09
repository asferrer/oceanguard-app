package com.oceanguard.ai.data.species

/** The catalogue entry of the species this observation identified, or null when uncatalogued. */
fun SpeciesCatalog.entryFor(observation: SpeciesObservation): SpeciesCatalogEntry? =
    observation.commonNameKey?.let(::byKey) ?: byScientificName(observation.scientificName)

/** Localised common name of the observed species, falling back to English and then the binomial. */
fun SpeciesCatalog.displayNameFor(observation: SpeciesObservation, language: String): String {
    val names = entryFor(observation)?.commonNames
    return names?.get(language) ?: names?.get("en") ?: observation.scientificName
}

/** Catalogue key that opens the BioDex sheet of the species, or null when there is none. */
fun SpeciesCatalog.speciesKeyFor(observation: SpeciesObservation): String? =
    entryFor(observation)?.speciesKey ?: observation.aphiaId?.toString()
