package com.oceanguard.ai.data.species

/**
 * A POJO representation of one entry in `species_catalog_vN.json`.
 *
 * This is NOT a Room entity; it is parsed in-memory by [SpeciesCatalog] at
 * first access. The JSON schema must match exactly — the Python asset-builder
 * (an external Python builder, not included in this repository) owns the canonical field names.
 *
 * @param speciesKey       Primary key matching [SpeciesDexEntry.speciesKey].
 *                         Format: AphiaID string for catalogued species.
 * @param aphiaId          WoRMS AphiaID (null if unavailable).
 * @param scientificName   Binomial name, e.g. "Caretta caretta".
 * @param commonNames      Map of BCP-47 language tag → common name,
 *                         e.g. {"en":"Loggerhead turtle","es":"Tortuga boba"}.
 * @param spritePath       Path of the sprite inside the species pack directory
 *                         (e.g. "sprites/caretta_caretta.webp"); null = `sprites/<speciesKey>.webp`, see [sprite].
 * @param ecoregions       List of MEOW ecoregion IDs (Spalding 2007 coding)
 *                         where this species has confirmed occurrences.
 *                         Empty list = no distribution data → treated like cosmopolitan.
 * @param cosmopolitan     True for wide-ranging species (cetaceans, sea turtles,
 *                         many sharks) that must never be excluded by the geo-filter.
 * @param descriptions     Map of BCP-47 language tag → short species description
 *                         (shown on the BioDex detail screen).
 * @param iucnStatus       IUCN Red List category code (e.g. "VU", "EN", "LC"),
 *                         null if not assessed or unknown.
 * @param group            Optional (catalog v2): stable English key of the taxonomic group
 *                         (e.g. "bony_fish", "sharks_rays"), null in v1 catalogs.
 */
data class SpeciesCatalogEntry(
    val speciesKey: String,
    val aphiaId: Long?,
    val scientificName: String,
    val commonNames: Map<String, String>,
    val spritePath: String?,
    val ecoregions: List<Int>,
    val cosmopolitan: Boolean = false,
    val descriptions: Map<String, String> = emptyMap(),
    val iucnStatus: String? = null,
    val group: String? = null,
) {
    /**
     * Sprite of the species inside the pack directory: [spritePath], or `sprites/<speciesKey>.webp` when the
     * catalogue leaves it out (the 30 species added in pack v4 have no `spritePath`).
     */
    val sprite: String
        get() = spritePath?.takeIf { it.isNotBlank() } ?: "sprites/$speciesKey.webp"
}
