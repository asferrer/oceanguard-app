package com.oceanguard.ai.inference.species

import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * In-memory brute-force cosine similarity index over species prototype embeddings.
 *
 * ## Binary format of `species_index_vN.bin`
 *
 * The Python builder (an external Python builder, not included in this repository) MUST emit this
 * exact layout — document here so both sides stay in sync:
 *
 * ```
 * Header (32 bytes, little-endian):
 *   [0..3]   magic   = 0x53504558  ("SPEX" in ASCII, uint32)
 *   [4..7]   version = 1           (uint32)
 *   [8..11]  n       = number of prototype rows  (uint32)
 *   [12..15] dim     = embedding dimension (512 for the v1 pack; any value
 *                      > 0, it must match the pack encoder output)  (uint32)
 *   [16..31] reserved (zero-padded)
 *
 * Prototype rows (n × dim × 2 bytes each, float16, little-endian):
 *   Each row is a L2-normalised float16 vector of length [dim].
 *
 * Metadata rows (n entries, UTF-8 JSON lines):
 *   Each line is a JSON object:
 *   {"speciesKey":"126436","scientificName":"Caretta caretta","viewTag":"lateral","nRefs":6}
 *   Lines follow immediately after the binary prototype block.
 * ```
 *
 * Total file size: 32 + n*dim*2 + (metadata text) bytes.
 *
 * ## Search algorithm
 *
 * 1. Deserialize prototypes to float32 (upcast from float16 at load time).
 * 2. For a query embedding q (L2-normalised, length [dimension]):
 *    cosine(p_i, q) = dot(p_i, q)   (both L2-norm → cosine = dot product)
 * 3. Aggregate scores per species by taking the max across its prototypes; with a
 *    [SpeciesPackConfig.Scoring] (pack v3) the image rows (max) and the text row of each
 *    species are combined with the pack weights instead.
 * 4. Apply geo-filter + soft-prior (multiply by [GeoPrior.g]).
 * 5. Sort descending, return top-[topK] as [SpeciesMatch].
 *
 * @param catalogEntries  Map from speciesKey → [SpeciesCatalogEntry] (loaded from catalog JSON).
 * @param confidence      Calibration parameters (Platt sigmoid + thresholds).
 * @param scoring         Image + text row combination of pack v3; null = max over all rows.
 */
class SpeciesReferenceIndex(
    private val catalogEntries: Map<String, SpeciesCatalogEntry>,
    private val confidence: SpeciesConfidence = SpeciesConfidence(),
    private val scoring: SpeciesPackConfig.Scoring? = null,
) {
    companion object {
        private const val MAGIC = 0x53504558.toInt()
        private const val EXPECTED_VERSION = 1

        /** Dimension of the v1 pack and of the in-memory test fixtures. */
        const val EMBEDDING_DIM = 512

        /**
         * Seed data for an in-memory prototype used by [inMemory].
         *
         * @param speciesKey     Key that matches a [SpeciesCatalogEntry].
         * @param scientificName Binomial name copied to index metadata.
         * @param vector         L2-normalised float32 embedding; all seeds share one length.
         *                       Callers are responsible for normalisation before passing.
         * @param viewTag        View of the row; "text" marks a text row in a v3 pack.
         */
        data class ProtoSeed(
            val speciesKey: String,
            val scientificName: String,
            val vector: FloatArray,
            val viewTag: String = "in_memory",
        )

        /**
         * Build a [SpeciesReferenceIndex] already populated from in-memory data.
         *
         * Intended for demo / integration testing where no binary asset file is
         * available on disk.  The returned instance has [loaded] = true and is
         * ready for [search] immediately.
         *
         * @param catalogEntries Map from speciesKey → [SpeciesCatalogEntry].
         * @param prototypes     One or more [ProtoSeed]s (each vector must be
         *                       L2-normalised and of length [dim]).
         * @param dim            Embedding dimension (default [EMBEDDING_DIM]).
         * @return A fully initialised [SpeciesReferenceIndex].
         * @throws IllegalArgumentException if any vector has the wrong dimension.
         */
        fun inMemory(
            catalogEntries: Map<String, SpeciesCatalogEntry>,
            prototypes: List<ProtoSeed>,
            dim: Int = EMBEDDING_DIM,
            confidence: SpeciesConfidence = SpeciesConfidence(),
            scoring: SpeciesPackConfig.Scoring? = null,
        ): SpeciesReferenceIndex {
            val index = SpeciesReferenceIndex(catalogEntries, confidence, scoring)
            index.loadFromSeeds(prototypes, dim)
            return index
        }
    }

    // Parallel arrays: prototypes[i] is a float32 L2-norm vector; meta[i] is its metadata.
    private var prototypes: Array<FloatArray> = emptyArray()
    private var meta: Array<ProtoMeta> = emptyArray()

    @Volatile
    private var loaded = false

    /** Embedding dimension of the loaded prototypes (0 until loaded). */
    var dimension: Int = 0
        private set

    /** Number of distinct species with at least one prototype. */
    val speciesCount: Int
        get() = meta.map { it.speciesKey }.toSet().size

    // -----------------------------------------------------------------------
    // Loading
    // -----------------------------------------------------------------------

    /**
     * Load prototypes from [indexFile]. Must be called before [search].
     * Idempotent: subsequent calls are no-ops if already loaded.
     *
     * Reads the binary header, float16 prototype block, and JSON metadata lines.
     *
     * @throws IllegalStateException if magic/version mismatch or dim <= 0.
     * @throws java.io.IOException   on any I/O error.
     */
    fun load(indexFile: File) {
        if (loaded) return

        val bytes = indexFile.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buf.int
        check(magic == MAGIC) { "Bad magic 0x${magic.toString(16)}, expected 0x${MAGIC.toString(16)}" }
        val version = buf.int
        check(version == EXPECTED_VERSION) { "Unsupported index version $version" }
        val n = buf.int
        val dim = buf.int
        check(dim > 0) { "Index dim=$dim must be positive" }
        buf.position(32) // skip reserved bytes

        // Read float16 prototype block
        val protoBytes = n * dim * 2
        val protoBlock = ByteArray(protoBytes)
        buf.get(protoBlock)
        val protoProtos = Array(n) { i ->
            val row = FloatArray(dim)
            val base = i * dim * 2
            for (d in 0 until dim) {
                row[d] = float16ToFloat32(protoBlock, base + d * 2)
            }
            row
        }

        // Read metadata lines (UTF-8 JSON, remainder of the file)
        val metaText = bytes.copyOfRange(32 + protoBytes, bytes.size).toString(Charsets.UTF_8)
        val metaLines = metaText.trim().lines()
        check(metaLines.size == n) {
            "Metadata line count ${metaLines.size} != prototype count $n"
        }
        val parsedMeta = Array(n) { i -> parseMetaLine(metaLines[i]) }

        prototypes = protoProtos
        meta = parsedMeta
        dimension = dim
        loaded = true
    }

    /**
     * Populate the index directly from [ProtoSeed] objects without file I/O.
     *
     * Called exclusively by [Companion.inMemory]; idempotent (no-op if already
     * loaded from file or from a prior [loadFromSeeds] call).
     */
    private fun loadFromSeeds(seeds: List<ProtoSeed>, dim: Int) {
        if (loaded) return
        require(seeds.all { it.vector.size == dim }) {
            "All ProtoSeed vectors must have dimension $dim"
        }
        dimension = dim
        prototypes = Array(seeds.size) { i -> seeds[i].vector.copyOf() }
        meta = Array(seeds.size) { i ->
            ProtoMeta(
                speciesKey     = seeds[i].speciesKey,
                scientificName = seeds[i].scientificName,
                viewTag        = seeds[i].viewTag,
                nRefs          = 1,
            )
        }
        loaded = true
    }

    /** English common name of [speciesKey] from the catalogue, or null. */
    fun commonName(speciesKey: String): String? =
        catalogEntries[speciesKey]?.commonNames?.get("en")?.takeIf { it.isNotBlank() }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    /**
     * Search the index for the [topK] best-matching species given query [emb].
     *
     * [emb] must be a L2-normalised float32 vector of length [dimension].
     *
     * With a [region] (and the filter not OFF) the geographic prior [GeoPrior.g]
     * multiplies each species probability: logits cosine / T + ln g, ranking and
     * confidence included. STRICT also drops species known only from other
     * provinces or realms (never cosmopolitan or no-data ones; if that leaves
     * nothing, nothing is dropped). Without a region every species has g = 1.
     *
     * @param sensitivity BALANCED (default): prior only; STRICT: prior + filter; OFF: no geography.
     * @return            Top-[topK] matches sorted by adjusted score descending.
     *                    May be shorter than [topK] if fewer species exist in
     *                    the index after filtering.
     */
    fun search(
        emb: FloatArray,
        region: MarineRegion?,
        topK: Int = 5,
        sensitivity: GeoFilterSensitivity = GeoFilterSensitivity.BALANCED,
        hierarchy: (Int) -> Pair<Int, Int>? = { null },
    ): List<SpeciesMatch> = searchDetailed(emb, region, topK, sensitivity, hierarchy).matches

    /**
     * Same ranking and confidences as [search], plus the probability mass of every catalogue
     * `group` ([SpeciesSearchOutcome.groupProbabilities]): the sum of the calibrated softmax
     * probabilities (geo prior included) over all species of the group, not only the top-k.
     * Empty when the pack uses the legacy Platt confidence (no softmax to sum).
     */
    fun searchDetailed(
        emb: FloatArray,
        region: MarineRegion?,
        topK: Int = 5,
        sensitivity: GeoFilterSensitivity = GeoFilterSensitivity.BALANCED,
        hierarchy: (Int) -> Pair<Int, Int>? = { null },
    ): SpeciesSearchOutcome {
        if (!loaded || prototypes.isEmpty()) return SpeciesSearchOutcome(emptyList(), emptyMap())
        require(emb.size == dimension) {
            "Query embedding dim=${emb.size}, expected $dimension"
        }

        // Score each prototype
        val rawScores = FloatArray(prototypes.size) { i -> dotProduct(prototypes[i], emb) }

        val speciesScores = aggregate(rawScores)
        val t = confidence.temperature
        val useGeo = region != null && sensitivity != GeoFilterSensitivity.OFF
        val candidates = speciesScores.entries.mapNotNull { (key, cosine) ->
            val entry = catalogEntries[key] ?: return@mapNotNull null
            val level = if (useGeo) GeoPrior.matchLevel(entry, region, hierarchy) else GeoMatchLevel.GLOBAL
            // Cosine units: cosine + T ln g, i.e. logits cosine / T + ln g (prior on the probability).
            val rank = if (t > 0f) cosine + t * kotlin.math.ln(GeoPrior.g(level)) else cosine * GeoPrior.g(level)
            SearchCandidate(key, cosine, rank, level)
        }
        // STRICT drops species only seen in other realms or provinces; never cosmopolitan or no-data ones.
        val strict = if (useGeo && sensitivity == GeoFilterSensitivity.STRICT) {
            candidates.filter { it.level != GeoMatchLevel.REALM && it.level != GeoMatchLevel.OUT_OF_RANGE }
        } else candidates
        val effective = strict.ifEmpty { candidates }
        val confOf = confidenceFunction(effective.associate { it.key to it.adjusted })

        val top = effective.sortedByDescending { it.adjusted }.take(topK)
        val matches = top.map { c ->
            val entry = catalogEntries[c.key]
            SpeciesMatch(
                speciesKey     = c.key,
                scientificName = entry?.scientificName ?: c.key,
                cosineScore    = c.cosine,
                confidence     = confOf(c.key, c.cosine),
                geoMatchLevel  = c.level,
                rankScore      = c.adjusted,
            )
        }
        val groups = if (t > 0f) GroupFallback.groupSums(effective.map { it.key to confOf(it.key, it.cosine) }) { catalogEntries[it]?.group }
        else emptyMap()
        return SpeciesSearchOutcome(matches, groups)
    }

    /** Per-species score from the row cosines: max over all rows, or image + text with [scoring]. */
    private fun aggregate(rawScores: FloatArray): Map<String, Float> {
        val sc = scoring ?: return maxPerSpecies(rawScores) { true }
        val image = maxPerSpecies(rawScores) { it.viewTag != sc.textViewTag }
        val text = maxPerSpecies(rawScores) { it.viewTag == sc.textViewTag }
        return (image.keys + text.keys).associateWith { key ->
            sc.imageWeight * (image[key] ?: sc.missingImageScore) + sc.textWeight * (text[key] ?: 0f)
        }
    }

    private fun maxPerSpecies(rawScores: FloatArray, accept: (ProtoMeta) -> Boolean): Map<String, Float> {
        val out = mutableMapOf<String, Float>()
        for (i in prototypes.indices) {
            if (!accept(meta[i])) continue
            val key = meta[i].speciesKey
            val current = out[key] ?: -1f
            if (rawScores[i] > current) out[key] = rawScores[i]
        }
        return out
    }

    /**
     * Confidence of a species given all per-species scores: the softmax over the
     * species cosines at the pack temperature ([SpeciesConfidence.temperature] > 0),
     * i.e. how much the best species stands out from the rest, which separates
     * right from wrong answers much better than the raw cosine (docs/SPECIES_ID.md);
     * the legacy Platt sigmoid of the cosine otherwise.
     */
    private fun confidenceFunction(speciesScores: Map<String, Float>): (String, Float) -> Float {
        val t = confidence.temperature
        if (t <= 0f || speciesScores.isEmpty()) return { _, cosine -> confidence.calibrate(cosine) }
        val max = speciesScores.values.max()
        val exp = speciesScores.mapValues { (_, s) -> kotlin.math.exp(((s - max) / t).toDouble()) }
        val sum = exp.values.sum()
        return { key, _ -> ((exp[key] ?: 0.0) / sum).toFloat() }
    }

    // -----------------------------------------------------------------------
    // Internal data class for search candidates
    // -----------------------------------------------------------------------

    private data class SearchCandidate(
        val key: String,
        val cosine: Float,
        val adjusted: Float,
        val level: GeoMatchLevel,
    )

    // -----------------------------------------------------------------------
    // Internal helpers — pure math, no I/O
    // -----------------------------------------------------------------------

    /** Dot product of two equal-length float arrays. O(dim). */
    internal fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /**
     * IEEE 754 float16 → float32 conversion.
     *
     * Layout: 1 sign | 5 exponent | 10 mantissa (little-endian in [buf]).
     * Handles denormals, infinities, and NaN faithfully.
     */
    internal fun float16ToFloat32(buf: ByteArray, offset: Int): Float {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt() and 0xFF
        val h = (hi shl 8) or lo

        val sign     = (h ushr 15) and 0x1
        val exponent = (h ushr 10) and 0x1F
        val mantissa =  h          and 0x3FF

        val bits: Int = when (exponent) {
            0    -> if (mantissa == 0) sign shl 31
                    else { // denormal
                        var m = mantissa
                        var e = -14
                        while (m and 0x400 == 0) { m = m shl 1; e-- }
                        (sign shl 31) or ((e + 127) shl 23) or ((m and 0x3FF) shl 13)
                    }
            0x1F -> if (mantissa == 0) (sign shl 31) or (0xFF shl 23) // ±Inf
                    else               (sign shl 31) or (0xFF shl 23) or (mantissa shl 13) // NaN
            else -> (sign shl 31) or ((exponent + 112) shl 23) or (mantissa shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }

    // -----------------------------------------------------------------------
    // Internal data class for prototype metadata
    // -----------------------------------------------------------------------

    private data class ProtoMeta(
        val speciesKey: String,
        val scientificName: String,
        val viewTag: String,
        val nRefs: Int,
    )

    /** Minimal JSON parse for a single metadata line (avoids a Gson dependency here). */
    private fun parseMetaLine(line: String): ProtoMeta {
        fun extract(key: String): String {
            val marker = "\"$key\":"
            val start = line.indexOf(marker)
            if (start < 0) return ""
            val valueStart = start + marker.length
            return if (line[valueStart] == '"') {
                val end = line.indexOf('"', valueStart + 1)
                if (end < 0) "" else line.substring(valueStart + 1, end)
            } else {
                var end = valueStart
                while (end < line.length && line[end] != ',' && line[end] != '}') end++
                line.substring(valueStart, end).trim()
            }
        }
        return ProtoMeta(
            speciesKey    = extract("speciesKey"),
            scientificName = extract("scientificName"),
            viewTag       = extract("viewTag"),
            nRefs         = extract("nRefs").toIntOrNull() ?: 0,
        )
    }
}
