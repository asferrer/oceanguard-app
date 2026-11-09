package com.oceanguard.ai.utils

import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.data.species.displayNameFor
import com.oceanguard.ai.data.species.entryFor
import java.text.SimpleDateFormat
import java.util.Locale

/** Header of the species CSV: one row per identified species in a photo. */
internal const val SPECIES_CSV_HEADER =
    "observation_id,analysis_id,image_name,date,lat,lon,scientific_name,common_name,group,iucn,confidence,out_of_range"

/**
 * One row per observation, with the names the report used: the binomial, the common name in
 * [language], the taxonomic group and the IUCN status of the catalogue. Confidence is the calibrated
 * probability of the identification, in [0, 1].
 */
internal fun observationsToCsv(
    observations: List<SpeciesObservation>,
    catalog: SpeciesCatalog,
    language: String,
): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val lines = mutableListOf(SPECIES_CSV_HEADER)
    for (o in observations) {
        val entry = catalog.entryFor(o)
        val imageName = o.imageUri.substringAfterLast('/').ifEmpty { o.imageUri }
        val lat = o.location?.latitude?.let { String.format(Locale.US, "%.6f", it) }.orEmpty()
        val lon = o.location?.longitude?.let { String.format(Locale.US, "%.6f", it) }.orEmpty()
        lines += listOf(
            o.id.toString(), o.analysisId?.toString().orEmpty(), csvCell(imageName), csvCell(dateFormat.format(o.timestamp)),
            lat, lon, csvCell(o.scientificName), csvCell(catalog.displayNameFor(o, language)),
            csvCell(entry?.group.orEmpty()), csvCell(entry?.iucnStatus.orEmpty()),
            String.format(Locale.US, "%.4f", o.confidence), o.outOfRange.toString(),
        ).joinToString(",")
    }
    return lines.joinToString("\n", postfix = "\n")
}

/** GeoJSON Point features of the located observations, marked `"kind":"species_observation"`. */
internal fun observationFeatures(observations: List<SpeciesObservation>, catalog: SpeciesCatalog, language: String): List<String> {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    return observations.mapNotNull { o ->
        val loc = o.location ?: return@mapNotNull null
        val coordinates = String.format(Locale.US, "%.6f,%.6f", loc.longitude, loc.latitude)
        val properties = listOf(
            "\"kind\":\"species_observation\"",
            "\"observationId\":${o.id}",
            "\"scientificName\":${jsonString(o.scientificName)}",
            "\"commonName\":${jsonString(catalog.displayNameFor(o, language))}",
            "\"timestamp\":${jsonString(dateFormat.format(o.timestamp))}",
            "\"confidence\":${String.format(Locale.US, "%.4f", o.confidence)}",
            "\"outOfRange\":${o.outOfRange}",
        ).joinToString(",")
        "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[$coordinates]},\"properties\":{$properties}}"
    }
}

/** FeatureCollection of species observations only. */
internal fun observationsToGeoJson(observations: List<SpeciesObservation>, catalog: SpeciesCatalog, language: String): String =
    "{\"type\":\"FeatureCollection\",\"features\":[${observationFeatures(observations, catalog, language).joinToString(",")}]}"

/**
 * FeatureCollection of a combined report: the debris sessions exactly as [sessionsToExportGeoJson]
 * writes them, followed by the species observations (told apart by their `kind` property).
 */
internal fun combinedToGeoJson(
    sessions: List<com.oceanguard.ai.data.DetectionSession>,
    observations: List<SpeciesObservation>,
    catalog: SpeciesCatalog,
    language: String,
): String {
    val sessionFeatures = sessionsToExportGeoJson(sessions).substringAfter("\"features\":[").removeSuffix("]}")
    val all = listOf(sessionFeatures).filter { it.isNotEmpty() } + observationFeatures(observations, catalog, language)
    return "{\"type\":\"FeatureCollection\",\"features\":[${all.joinToString(",")}]}"
}
