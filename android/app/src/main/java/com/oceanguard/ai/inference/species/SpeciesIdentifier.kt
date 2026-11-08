package com.oceanguard.ai.inference.species

import android.graphics.Bitmap
import android.util.Log
import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.species.SpeciesIdSource

/**
 * Immutable result of one organism identification within a single image.
 *
 * @param speciesKey      Matched catalogue key, or [UNIDENTIFIED_KEY] when the
 *                        organism could not be identified.
 * @param scientificName  Scientific name; empty when unidentified.
 * @param idSource        How the identification was produced.
 * @param cosineScore     Top-1 cosine similarity of the visual search.
 * @param confidence      Calibrated probability that the top-1 is right, in [0, 1].
 * @param vlmDescription  Text from the VLM, when it was invoked. Null otherwise.
 * @param bbox            Organism bounding box in original image pixels.
 * @param geoMatchLevel   Name of [GeoMatchLevel] entry; null = no GPS.
 * @param outOfRange      True when matched outside the known distribution range.
 * @param uncatalogued    True for results that are not a catalogue species.
 * @param freeLabel       VLM-suggested organism name for legacy open-vocab rows.
 * @param candidates      Best catalogue candidates of the visual search (top-k),
 *                        also kept for unidentified results as "possible" hints.
 * @param vlmChosen       True when Gemma chose this species among [candidates].
 */
data class IdentificationResult(
    val speciesKey: String,
    val scientificName: String,
    val idSource: SpeciesIdSource,
    val cosineScore: Float,
    val confidence: Float,
    val vlmDescription: String? = null,
    val bbox: BoundingBox? = null,
    val geoMatchLevel: String? = null,
    val outOfRange: Boolean = false,
    val uncatalogued: Boolean = false,
    val freeLabel: String? = null,
    val candidates: List<SpeciesMatch> = emptyList(),
    val vlmChosen: Boolean = false,
) {
    /** True when the pipeline gave no species (honest "not identified" answer). */
    val isUnidentified: Boolean get() = idSource == SpeciesIdSource.UNIDENTIFIED

    companion object {
        const val UNIDENTIFIED_KEY = "unidentified"
    }
}

/**
 * Orchestrates the BioDex identification pipeline for a single image.
 *
 * Per crop from [locator]:
 * 1. Embed the crop with [embedder] and search the reference [index] (visual
 *    RAG: k-nearest reference photos, best score per species, geo prior).
 * 2. Decide with the calibrated [confidence] of the top-1 and its margin:
 *    - confident (>= tauHigh and margin >= delta) -> [SpeciesIdSource.RAG_CORE];
 *    - tentative (>= tauLow) -> the RAG top-1 as [SpeciesIdSource.RAG_TENTATIVE].
 *      Optionally (pack `vlmChoiceTopK` > 0, off by default because Gemma 4 E2B
 *      measured worse than the RAG ranking) Gemma ([describer]) chooses among
 *      the top candidates or answers "none" ([SpeciesIdSource.UNIDENTIFIED]);
 *    - below tauLow -> [SpeciesIdSource.UNIDENTIFIED]. The app never forces a
 *      species on a photo the index does not recognise.
 *
 * The thresholds are calibrated per species pack (species_pack.json). All
 * dependencies are injected, so the class is testable on plain JVM.
 *
 * @param vlmChoiceTopK Candidates offered to Gemma in the tentative band (0 = off).
 * @param vlmCropper    Builds the (larger) crop shown to Gemma from the full frame.
 */
class SpeciesIdentifier(
    private val embedder: SpeciesEmbedder,
    private val index: SpeciesReferenceIndex,
    private val resolver: RegionResolverPort? = null,
    private val confidence: SpeciesConfidence = SpeciesConfidence(),
    private val describer: DescriberPort = SpeciesDescriber(),
    private val locator: CropProvider = OrganismLocator(),
    private val isBlankFrame: (Bitmap) -> Boolean = ImageContentCheck::isBlank,
    private val vlmChoiceTopK: Int = DEFAULT_CHOICE_TOP_K,
    private val vlmCropper: (Bitmap, OrganismCrop) -> Bitmap = ::defaultVlmCrop,
) {
    companion object {
        private const val TAG = "SpeciesIdentifier"
        private const val TOP_K = 5
        /** Off by default: Gemma 4 E2B did not improve on the RAG ranking (docs/SPECIES_ID.md). */
        const val DEFAULT_CHOICE_TOP_K = 0

        /** Long edge of the crop shown to Gemma (vision_140 path, see OrganismLocator). */
        const val VLM_CROP_SIZE = 448

        private fun defaultVlmCrop(full: Bitmap, crop: OrganismCrop): Bitmap =
            RagCropPreparer.cropForRag(full, crop.bbox, targetSize = VLM_CROP_SIZE)
    }

    /** Per-stage latency, logged as p50 so it can be read from logcat. */
    private val latency = StageLatency(TAG)

    /**
     * Identify all marine organisms found in [bitmap].
     *
     * @return One [IdentificationResult] per located organism (possibly
     *         unidentified). Empty when the embedder is not ready or the frame
     *         is blank.
     */
    suspend fun identify(
        bitmap: Bitmap,
        location: Location? = null,
        sensitivity: GeoFilterSensitivity = GeoFilterSensitivity.BALANCED,
        language: String = "en",
    ): List<IdentificationResult> {
        if (!embedder.isReady) {
            Log.w(TAG, "Embedder not ready — skipping identification")
            return emptyList()
        }
        if (isBlankFrame(bitmap)) {
            Log.d(TAG, "Frame has no content (black / flat) — skipping identification")
            return emptyList()
        }
        val totalStart = System.currentTimeMillis()
        val region = resolveRegion(location)
        val locateStart = System.currentTimeMillis()
        val crops = locator.locate(bitmap)
        latency.record("locate", System.currentTimeMillis() - locateStart)
        Log.d(TAG, "Identifying ${crops.size} crop(s), region=${region?.ecoregionName}")

        val results = crops.mapNotNull { crop ->
            runCatching { identifyCrop(bitmap, crop, region, sensitivity, language) }
                .onFailure { e -> Log.w(TAG, "Crop identification failed: ${e.message}") }
                .getOrNull()
        }
        latency.record("total", System.currentTimeMillis() - totalStart)
        latency.logSummary()
        return results
    }

    private suspend fun identifyCrop(
        full: Bitmap,
        crop: OrganismCrop,
        region: MarineRegion?,
        sensitivity: GeoFilterSensitivity,
        language: String,
    ): IdentificationResult {
        val encodeStart = System.currentTimeMillis()
        val emb = embedder.embed(crop.crop)
        latency.record("encode", System.currentTimeMillis() - encodeStart)
        val searchStart = System.currentTimeMillis()
        val matches = index.search(
            emb = emb,
            region = region,
            topK = maxOf(TOP_K, vlmChoiceTopK),
            sensitivity = sensitivity,
            hierarchy = hierarchyFn(),
        )
        latency.record("search", System.currentTimeMillis() - searchStart)
        if (matches.isEmpty()) return unidentified(crop, null, emptyList())

        val top1 = matches[0]
        val margin = top1.cosineScore - (matches.getOrNull(1)?.cosineScore ?: 0f)
        val outOfRange = top1.geoMatchLevel == GeoMatchLevel.GLOBAL && region != null
        val shown = matches.take(TOP_K)
        return when (confidence.idSource(top1.confidence, margin)) {
            "RAG_CORE" -> matchResult(top1, crop, SpeciesIdSource.RAG_CORE, outOfRange, shown)
            "RAG_TENTATIVE" -> tentative(full, crop, matches, region, language, outOfRange)
            else -> unidentified(crop, top1, shown)
        }
    }

    /** Tentative band: let Gemma choose among the candidates when it is available. */
    private suspend fun tentative(
        full: Bitmap,
        crop: OrganismCrop,
        matches: List<SpeciesMatch>,
        region: MarineRegion?,
        language: String,
        outOfRange: Boolean,
    ): IdentificationResult {
        val shown = matches.take(TOP_K)
        val offered = matches.take(vlmChoiceTopK)
        val choice = if (offered.isEmpty() || !describer.canChoose) null else {
            val start = System.currentTimeMillis()
            val candidates = offered.map { SpeciesCandidate(it.speciesKey, it.scientificName, index.commonName(it.speciesKey)) }
            describer.chooseAmong(vlmCropper(full, crop), candidates, region, language)
                .also { latency.record("choose", System.currentTimeMillis() - start) }
        }
        return when (choice) {
            null -> matchResult(matches[0], crop, SpeciesIdSource.RAG_TENTATIVE, outOfRange, shown)
            is VlmChoice.NoneOfThem -> unidentified(crop, matches[0], shown)
            is VlmChoice.Picked -> {
                val pick = offered[choice.index]
                matchResult(pick, crop, SpeciesIdSource.RAG_TENTATIVE,
                    outOfRange && pick.geoMatchLevel == GeoMatchLevel.GLOBAL, shown, vlmChosen = true)
            }
        }
    }

    private fun matchResult(
        m: SpeciesMatch,
        crop: OrganismCrop,
        source: SpeciesIdSource,
        outOfRange: Boolean,
        candidates: List<SpeciesMatch>,
        vlmChosen: Boolean = false,
    ) = IdentificationResult(
        speciesKey = m.speciesKey,
        scientificName = m.scientificName,
        idSource = source,
        cosineScore = m.cosineScore,
        confidence = m.confidence,
        bbox = crop.bbox,
        geoMatchLevel = m.geoMatchLevel.name,
        outOfRange = outOfRange,
        candidates = candidates,
        vlmChosen = vlmChosen,
    )

    private fun unidentified(crop: OrganismCrop, top1: SpeciesMatch?, candidates: List<SpeciesMatch>) =
        IdentificationResult(
            speciesKey = IdentificationResult.UNIDENTIFIED_KEY,
            scientificName = "",
            idSource = SpeciesIdSource.UNIDENTIFIED,
            cosineScore = top1?.cosineScore ?: 0f,
            confidence = top1?.confidence ?: 0f,
            bbox = crop.bbox,
            uncatalogued = true,
            candidates = candidates,
        )

    private fun resolveRegion(location: Location?): MarineRegion? {
        if (location == null || resolver == null) return null
        return try {
            resolver.resolve(location.latitude, location.longitude)
        } catch (e: Exception) {
            Log.w(TAG, "Region resolution failed: ${e.message}")
            null
        }
    }

    private fun hierarchyFn(): (Int) -> Pair<Int, Int>? =
        if (resolver != null) resolver::hierarchy else { _ -> null }
}
