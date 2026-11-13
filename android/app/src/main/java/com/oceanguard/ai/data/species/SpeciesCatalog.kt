package com.oceanguard.ai.data.species

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * In-memory catalog parsed lazily from `species_catalog_vN.json`.
 *
 * The JSON file is downloaded from HuggingFace via VlmModelManager and stored
 * at `getExternalFilesDir/models/species/species_catalog_v1.json`. The catalog
 * is loaded once on first access and kept in-process; it is typically 50-200 KB.
 *
 * Thread-safety: [load] is idempotent and the catalog file is read-only after
 * download. Callers should call [load] from a background dispatcher before the
 * first [byKey] call if they want to avoid the blocking I/O on the first call.
 *
 * @param catalogFile  Path to the downloaded JSON catalog file.
 */
class SpeciesCatalog(@Volatile private var catalogFile: File) {

    private val gson = Gson()
    private val catalogFileType = object : TypeToken<CatalogFile>() {}.type

    @Volatile
    private var entries: Map<String, SpeciesCatalogEntry>? = null

    /**
     * Top-level wrapper of `species_catalog_vN.json`. The Python asset-builder
     * (`build_distribution_filter.py`) emits an object carrying provenance
     * metadata (`version`, `sources`, `license_note`) plus the `species` array,
     * not a bare array — so the catalog is parsed through this wrapper.
     */
    private data class CatalogFile(
        val species: List<SpeciesCatalogEntry> = emptyList(),
    )

    /**
     * Parses the catalog JSON and caches it. Safe to call multiple times;
     * subsequent calls are no-ops if the catalog is already loaded.
     *
     * @throws IllegalStateException if the file does not exist.
     */
    fun load() {
        if (entries != null) return
        entries = parse(catalogFile)
    }

    /**
     * Parse [file] (the species pack changed) and swap it in at once. The old entries stay readable until the new
     * ones are ready, so a screen reading the catalogue meanwhile never sees it empty; if [file] cannot be read the
     * old entries are kept and the error is thrown.
     */
    @Synchronized
    fun reload(file: File = catalogFile) {
        val parsed = parse(file)
        catalogFile = file
        entries = parsed
    }

    private fun parse(file: File): Map<String, SpeciesCatalogEntry> {
        check(file.exists()) { "Species catalog not found at ${file.absolutePath}. Download it first." }
        val parsed: CatalogFile = gson.fromJson(file.readText(Charsets.UTF_8), catalogFileType)
        return parsed.species.associateBy { it.speciesKey }
    }

    /**
     * Returns the [SpeciesCatalogEntry] for [speciesKey], or null if not found
     * or the catalog has not been loaded yet.
     */
    fun byKey(speciesKey: String): SpeciesCatalogEntry? = entries?.get(speciesKey)

    @Volatile
    private var byName: Pair<Map<String, SpeciesCatalogEntry>, Map<String, SpeciesCatalogEntry>>? = null

    /**
     * Entry whose binomial is [scientificName] (case-insensitive), or null. Observations carry the
     * name, not the key, so the history and the reports resolve the catalogue through this.
     */
    fun byScientificName(scientificName: String): SpeciesCatalogEntry? {
        val current = entries ?: return null
        val index = byName?.takeIf { it.first === current }
            ?: (current to current.values.associateBy { it.scientificName.lowercase() }).also { byName = it }
        return index.second[scientificName.trim().lowercase()]
    }

    /** Returns all entries in the loaded catalog. Empty list if not loaded. */
    fun all(): List<SpeciesCatalogEntry> = entries?.values?.toList() ?: emptyList()

    /** Total number of catalogued species. 0 if not loaded yet. */
    fun size(): Int = entries?.size ?: 0

    /** Every speciesKey in the loaded catalog. Empty list if not loaded. */
    fun allKeys(): List<String> = entries?.keys?.toList() ?: emptyList()

    /** Alias of [size]; used by the BioDex UI for the "N / total" counter. */
    fun totalCount(): Int = size()

    /** True if the catalog has been loaded into memory. */
    fun isLoaded(): Boolean = entries != null

    /**
     * Populate the catalog from [entries] without reading the JSON file on disk.
     *
     * Only takes effect when the catalog has not already been loaded from file
     * (i.e. [isLoaded] == false).  Idempotent: calling it multiple times with
     * the same data is safe.
     *
     * Use this in tests (e.g. with `SampleSpeciesData` from the test sources)
     * where the real downloaded asset is not available.
     *
     * @param entries List of [SpeciesCatalogEntry]; entries are keyed by
     *                [SpeciesCatalogEntry.speciesKey].
     */
    fun seedInMemory(entries: List<SpeciesCatalogEntry>) {
        if (this.entries != null) return
        this.entries = entries.associateBy { it.speciesKey }
    }
}
