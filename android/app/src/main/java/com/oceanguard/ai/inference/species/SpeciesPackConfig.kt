package com.oceanguard.ai.inference.species

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File

/**
 * Description of a downloadable BioDex species pack (`models/species/`).
 *
 * A pack is an image encoder (ONNX), a reference index of species embeddings
 * (SPEX file) and the catalogue, plus everything the app needs to use them
 * correctly: the encoder input contract and the decision thresholds calibrated
 * on a held-out validation split. All of it lives in `species_pack.json`, so a
 * new encoder or new reference images ship without an app update and the app
 * never guesses a normalisation or a threshold.
 *
 * Packs published before `species_pack.json` existed (v1: `clip_vitb32.onnx` +
 * `species_index_v1.bin`) are described by [legacyV1].
 *
 * @param encoderFile  ONNX image encoder file name.
 * @param indexFile    SPEX reference index file name.
 * @param catalogFile  Species catalogue JSON file name.
 * @param encoder      Input/output contract of the encoder.
 * @param calibration  Platt parameters and thresholds fitted on validation.
 * @param vlmChoiceTopK How many RAG candidates Gemma may choose from in the
 *                     tentative band; 0 (default) disables the Gemma step. Off
 *                     because Gemma 4 E2B picked the right species among the
 *                     RAG top 5 only 31.7 % of the time (docs/SPECIES_ID.md).
 * @param scoring      Pack v3: how image and text rows of the index combine into a
 *                     species score; null = maximum cosine over all rows (v1, v2).
 * @param sessionOptimization ONNX Runtime graph optimisation: "all" (default), "basic" or
 *                     "none". v3 stores fp16 weights with a Cast each and constant folding
 *                     would raise the RAM to ~2.5 GB.
 * @param cropMode     "center" (default) or "full": without an organism locator, crop the
 *                     centre square or resize the whole photo to size x size.
 * @param merged       Informative: species keys merged into another one (old -> new).
 * @param groupFallback Optional: when no species is confident, report the catalogue group whose
 *                     summed probability reaches [GroupFallback.threshold] (docs/SPECIES_ID.md, 13).
 * @param sha256       Optional file name -> SHA-256 (hex) of the pack files, used by the update to
 *                     reuse the files that did not change.
 * @param selfTest     Optional reference embedding of [SelfTest.pattern] computed
 *                     on the PC in fp32, used to detect a numerically broken
 *                     accelerator path on the device.
 */
data class SpeciesPackConfig(
    val version: Int,
    val encoderFile: String,
    val indexFile: String,
    val catalogFile: String,
    val encoder: EncoderSpec,
    val calibration: Calibration,
    val vlmChoiceTopK: Int = 0,
    val selfTest: SelfTest? = null,
    val scoring: Scoring? = null,
    val sessionOptimization: String? = null,
    val cropMode: String? = null,
    val merged: Map<String, String>? = null,
    val groupFallback: GroupFallbackSpec? = null,
    val sha256: Map<String, String>? = null,
) {
    /** `groupFallback` of the manifest. */
    data class GroupFallbackSpec(val threshold: Float = 0f)

    /** Threshold of the group fallback; null when the pack has none (or an invalid one). */
    val groupThreshold: Float?
        get() = groupFallback?.threshold?.takeIf { it.isFinite() && it > 0f && it <= 1f }

    /**
     * Species score = imageWeight * max cosine of its image rows + textWeight * cosine of its
     * text row (rows whose viewTag is [textViewTag]). A species without image rows uses
     * [missingImageScore] for the image part; one without a text row contributes 0 there.
     */
    data class Scoring(
        val imageWeight: Float = 1f,
        val textWeight: Float = 0f,
        val textViewTag: String = TEXT_TAG,
        val missingImageScore: Float = 0f,
    )

    /** Graph optimisation level for the ONNX session; unknown values mean "all". */
    val optimization: Optimization
        get() = Optimization.entries.firstOrNull { it.key == sessionOptimization } ?: Optimization.ALL

    /** True when the whole photo is resized instead of cropping the centre square. */
    val fullCrop: Boolean get() = cropMode == CROP_FULL

    /** Merged keys (old -> new); empty when the pack has none. */
    val mergedKeys: Map<String, String> get() = merged.orEmpty()

    enum class Optimization(val key: String) { ALL("all"), BASIC("basic"), NONE("none") }

    /** Input/output contract of the ONNX encoder. */
    data class EncoderSpec(
        val inputName: String = "pixel_values",
        val outputName: String = "image_features",
        val size: Int = 224,
        val mean: List<Float> = CLIP_MEAN,
        val std: List<Float> = CLIP_STD,
        val dim: Int = 512,
    )

    /** Parameters of [SpeciesConfidence]; see that class for their meaning. */
    data class Calibration(
        val plattA: Float,
        val plattB: Float,
        val tauHigh: Float,
        val tauLow: Float,
        val delta: Float,
        /** Softmax temperature of the species confidence (0 = Platt on the cosine). */
        val temperature: Float = 0f,
    ) {
        fun toConfidence() = SpeciesConfidence(plattA, plattB, tauHigh, tauLow, delta, temperature)
    }

    /**
     * @param pattern     Name of the synthetic input generator, see [SelfTestPattern].
     * @param expected    fp32 PC embedding of that input (L2-normalised).
     * @param minCosine   Minimum cosine between device and PC embeddings.
     */
    data class SelfTest(
        val pattern: String = SelfTestPattern.GRADIENT_V1,
        val expected: List<Float> = emptyList(),
        val minCosine: Float = 0.99f,
    )

    /** Files that must exist in the pack directory for the pack to be usable. */
    fun requiredFiles(): List<String> = listOf(encoderFile, indexFile, catalogFile)

    companion object {
        const val MANIFEST = "species_pack.json"
        const val TEXT_TAG = "text"
        const val CROP_FULL = "full"

        val CLIP_MEAN = listOf(0.48145466f, 0.4578275f, 0.40821073f)
        val CLIP_STD = listOf(0.26862954f, 0.26130258f, 0.27577711f)

        /**
         * Pack v1 as published (OpenCLIP ViT-B/32 fine-tuned, 203 species).
         * Its calibration was fitted on the group-split validation set of
         * `tools/species/` (see docs/SPECIES_ID.md). The previous placeholder
         * Platt values (A=10, B=-3.5) answered a species for 100 % of the test
         * photos, out-of-catalogue ones included, with 44.9 % precision.
         */
        val legacyV1 = SpeciesPackConfig(
            version = 1,
            encoderFile = "clip_vitb32.onnx",
            indexFile = "species_index_v1.bin",
            catalogFile = "species_catalog_v1.json",
            encoder = EncoderSpec(),
            calibration = Calibration(
                plattA = LegacyV1Calibration.PLATT_A,
                plattB = LegacyV1Calibration.PLATT_B,
                tauHigh = LegacyV1Calibration.TAU_HIGH,
                tauLow = LegacyV1Calibration.TAU_LOW,
                delta = LegacyV1Calibration.DELTA,
                temperature = LegacyV1Calibration.TEMPERATURE,
            ),
        )

        /**
         * Read the pack description from [dir]: `species_pack.json` when present,
         * otherwise [legacyV1] when its files are there. Null when no complete
         * pack is installed or the manifest is unreadable.
         */
        fun load(dir: File): SpeciesPackConfig? {
            val manifest = File(dir, MANIFEST)
            val config = if (manifest.exists()) parse(manifest.readText(Charsets.UTF_8)) else legacyV1
            return config?.takeIf { c -> c.requiredFiles().all { File(dir, it).exists() } }
        }

        /** Parse a manifest; null when it is not valid JSON or misses a field. */
        fun parse(json: String): SpeciesPackConfig? = try {
            Gson().fromJson(json, SpeciesPackConfig::class.java)?.takeIf { it.isValid() }?.withDefaults()
        } catch (e: JsonSyntaxException) {
            null
        } catch (e: NullPointerException) {
            null // a nested field Gson left null
        }

        /** Gson leaves absent or null optional fields null: fill them with the defaults. */
        @Suppress("SENSELESS_COMPARISON")
        private fun SpeciesPackConfig.withDefaults(): SpeciesPackConfig {
            val sc = scoring?.let { s ->
                Scoring(
                    imageWeight = s.imageWeight.takeIf { it.isFinite() } ?: 1f,
                    textWeight = s.textWeight.takeIf { it.isFinite() } ?: 0f,
                    textViewTag = s.textViewTag?.takeIf { it.isNotBlank() } ?: TEXT_TAG,
                    missingImageScore = s.missingImageScore.takeIf { it.isFinite() } ?: 0f,
                )
            }
            val clean = merged?.filter { (k, v) -> k != null && v != null && k != v }
            return copy(
                scoring = sc,
                sessionOptimization = optimization.key,
                cropMode = if (fullCrop) CROP_FULL else "center",
                merged = clean ?: emptyMap(),
                groupFallback = groupFallback?.takeIf { groupThreshold != null },
                sha256 = sha256?.filter { (k, v) -> k != null && v != null }?.mapValues { it.value.lowercase() },
            )
        }

        // Gson ignores Kotlin nullability and leaves missing fields as null: check them.
        @Suppress("SENSELESS_COMPARISON")
        private fun SpeciesPackConfig.isValid(): Boolean =
            encoderFile != null && indexFile != null && catalogFile != null &&
                encoder != null && calibration != null &&
                encoder.inputName != null && encoder.outputName != null &&
                encoder.mean != null && encoder.std != null &&
                encoder.mean.size == 3 && encoder.std.size == 3 && encoder.size > 0 && encoder.dim > 0
    }
}

/**
 * Calibration of the published v1 pack on the val split of `tools/species/`
 * (`eval_rag.py --encoders pack_onnx --galleries published`): softmax
 * temperature fitted on val; tauLow / tauHigh = lowest softmax thresholds with
 * val selective precision >= 0.80 / 0.90. Platt is unused (temperature > 0).
 */
internal object LegacyV1Calibration {
    const val TEMPERATURE = 0.02669f
    const val TAU_HIGH = 0.729f
    const val TAU_LOW = 0.598f
    const val DELTA = 0.0f
    const val PLATT_A = 10.0f
    const val PLATT_B = -3.5f
}
