package com.oceanguard.ai.inference.species

import android.graphics.Bitmap

/**
 * Minimal interface for species description / confirmation used by
 * [SpeciesIdentifier].
 *
 * Extracted so that the orchestrator can be unit-tested on plain JVM without
 * a running VLM. The production implementation is [SpeciesDescriber];
 * test doubles can implement this interface without Android SDK constraints.
 */
interface DescriberPort {

    /**
     * Ask the VLM to confirm or refute a RAG top-1 hypothesis.
     *
     * @see SpeciesDescriber.confirmOrDescribe
     */
    suspend fun confirmOrDescribe(
        crop: Bitmap,
        hypothesisKey: String,
        hypothesisScientificName: String,
        region: MarineRegion? = null,
        language: String = "en",
    ): SpeciesIdResult

    /**
     * Open-vocabulary description for an uncatalogued organism.
     *
     * @see SpeciesDescriber.describeOpenVocab
     */
    suspend fun describeOpenVocab(
        crop: Bitmap,
        region: MarineRegion? = null,
        language: String = "en",
    ): SpeciesIdResult

    /** True when [chooseAmong] can run now (VLM loaded). */
    val canChoose: Boolean get() = false

    /**
     * Ask the VLM which of the visual-RAG [candidates] is the organism in
     * [crop], or whether none of them is.
     *
     * @return the choice, or null when the VLM is unavailable or its answer
     *         could not be parsed (the caller then keeps the RAG ranking).
     * @see SpeciesDescriber.chooseAmong
     */
    suspend fun chooseAmong(
        crop: Bitmap,
        candidates: List<SpeciesCandidate>,
        region: MarineRegion? = null,
        language: String = "en",
    ): VlmChoice? = null
}

/** A catalogue species offered to the VLM as one of the options. */
data class SpeciesCandidate(
    val speciesKey: String,
    val scientificName: String,
    val commonName: String?,
)

/** Answer of [DescriberPort.chooseAmong]. */
sealed interface VlmChoice {
    /** The VLM picked candidate number [index] (0-based). */
    data class Picked(val index: Int) : VlmChoice

    /** The VLM answered that none of the candidates is the organism. */
    data object NoneOfThem : VlmChoice
}
