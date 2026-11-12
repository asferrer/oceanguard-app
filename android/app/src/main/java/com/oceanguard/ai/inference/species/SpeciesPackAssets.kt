package com.oceanguard.ai.inference.species

import java.io.File

/**
 * Files of the downloadable BioDex species pack, as (file name, minimum valid
 * bytes) pairs. The minimum guards against HTML error pages saved as models.
 *
 * A pack with `species_pack.json` lists its own encoder / index / catalogue
 * ([forConfig]); without it the published v1 layout ([V1]) is assumed.
 */
object SpeciesPackAssets {
    /** Geographic filter files, shared by every pack version (optional). */
    val GEO = listOf(
        "meow_raster_v1.bin" to 1_000_000L,
        "ecoregion_hierarchy_v1.json" to 100L,
    )

    /** Pack v1 as published on Hugging Face (asferrer/oceanguard-biodex). */
    val V1 = listOf(
        "clip_vitb32.onnx" to 40_000_000L,
        "species_index_v1.bin" to 1_000L,
        "species_catalog_v1.json" to 100L,
    ) + GEO

    /** Files to download for the pack described by [config]. */
    fun forConfig(config: SpeciesPackConfig): List<Pair<String, Long>> = listOf(
        config.encoderFile to 1_000_000L,
        config.indexFile to 1_000L,
        config.catalogFile to 100L,
    ) + GEO

    /** Minimum bytes of an optional file (a sprite or the credits). */
    const val OPTIONAL_MIN_BYTES = 20L

    /** Optional files of the pack described by [config] (species artwork); a failure does not stop the pack. */
    fun optionalFor(config: SpeciesPackConfig): List<Pair<String, Long>> =
        config.optionalFiles().map { it to OPTIONAL_MIN_BYTES }

    /** Every file name that may belong to the pack installed in [dir]. */
    fun fileNames(dir: File): List<String> {
        val installed = SpeciesPackConfig.load(dir)
        val own = installed?.let { (forConfig(it) + optionalFor(it)).map { (name, _) -> name } } ?: emptyList()
        return (V1.map { it.first } + own + SpeciesPackConfig.MANIFEST).distinct()
    }
}
