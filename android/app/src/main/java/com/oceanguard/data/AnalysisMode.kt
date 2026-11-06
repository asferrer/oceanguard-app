package com.oceanguard.ai.data

import androidx.annotation.StringRes
import com.oceanguard.ai.R
import com.oceanguard.ai.inference.DetectorType
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.inference.TextModelVariant
import com.oceanguard.ai.inference.VlmModelManager
import com.oceanguard.ai.inference.VlmProvider

/**
 * The one decision a user makes about models. Each mode fixes `detector_mode`, `refine_with_gemma`,
 * `vlm_provider`, `vlm_model_tier` and `vlm_model_variant` at once.
 *
 * The mode is not stored: it is derived from those keys with [fromSettings]. That is the migration
 * of existing installs, and it keeps a single source of truth, because inference keeps reading the
 * same keys and advanced options can still change the report model on their own.
 *
 * Declaration order is the display order.
 *
 * @param approxSecondsPerPhoto Measured on a Galaxy S22 Ultra; null when no measurement exists.
 * @param approxDownloadGb Size of what must be downloaded; null when it ships in the APK.
 * @param radarRadPerSec Sweep speed of the radar: the slower the model, the slower the sweep.
 */
enum class AnalysisMode(
    val key: String,
    val detector: DetectorType,
    val refineWithGemma: Boolean,
    val provider: VlmProvider,
    val tier: TextModelTier,
    val variant: TextModelVariant,
    val approxSecondsPerPhoto: Int?,
    val approxDownloadGb: Float?,
    val radarRadPerSec: Float,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
) {
    FAST(
        key = "fast",
        detector = DetectorType.RT_DETR_V2,
        refineWithGemma = false,
        provider = VlmProvider.GEMMA4,
        tier = TextModelTier.GEMMA4_E2B,
        variant = TextModelVariant.BASE,
        approxSecondsPerPhoto = 4,
        approxDownloadGb = null,
        radarRadPerSec = 1.1f,
        labelRes = R.string.analysis_mode_fast,
        descriptionRes = R.string.analysis_mode_fast_desc,
    ),
    DETAILED(
        key = "detailed",
        detector = DetectorType.GEMMA4_VISION,
        refineWithGemma = false,
        provider = VlmProvider.GEMMA4,
        tier = TextModelTier.GEMMA4_E2B,
        variant = TextModelVariant.BASE,
        approxSecondsPerPhoto = 7,
        approxDownloadGb = 2.6f,
        radarRadPerSec = 0.8f,
        labelRes = R.string.analysis_mode_detailed,
        descriptionRes = R.string.analysis_mode_detailed_desc,
    ),
    TUNED(
        key = "tuned",
        detector = DetectorType.GEMMA4_VISION,
        refineWithGemma = false,
        provider = VlmProvider.GEMMA4,
        tier = TextModelTier.GEMMA4_E2B,
        variant = TextModelVariant.FINETUNED,
        approxSecondsPerPhoto = 9,
        approxDownloadGb = 5.2f,
        radarRadPerSec = 0.55f,
        labelRes = R.string.analysis_mode_tuned,
        descriptionRes = R.string.analysis_mode_tuned_desc,
    ),
    REFINED(
        key = "refined",
        detector = DetectorType.RT_DETR_V2,
        refineWithGemma = true,
        provider = VlmProvider.GEMMA4,
        tier = TextModelTier.GEMMA4_E2B,
        variant = TextModelVariant.BASE,
        approxSecondsPerPhoto = null,
        approxDownloadGb = 2.6f,
        radarRadPerSec = 0.8f,
        labelRes = R.string.analysis_mode_refined,
        descriptionRes = R.string.analysis_mode_refined_desc,
    );

    /** True when this mode cannot run on the bundled RT-DETRv2 alone. */
    val needsGemma: Boolean
        get() = detector == DetectorType.GEMMA4_VISION || refineWithGemma

    /** What the UI can offer right now for this mode. */
    fun availability(
        isDownloaded: (TextModelTier, TextModelVariant) -> Boolean,
        finetunedPublished: Boolean = VlmModelManager.FINETUNED_LITERTLM_PUBLISHED,
    ): AnalysisModeAvailability = when {
        !needsGemma -> AnalysisModeAvailability.READY
        variant == TextModelVariant.FINETUNED && !finetunedPublished ->
            AnalysisModeAvailability.NOT_PUBLISHED
        isDownloaded(tier, variant) -> AnalysisModeAvailability.READY
        else -> AnalysisModeAvailability.NEEDS_DOWNLOAD
    }

    /**
     * The mode that will really run. Mirrors `OceanGuardApp.getActiveDetector`, `getRefiner` and
     * `TextModelTier.variantFallback`: the UI must never claim a model that is not going to work.
     */
    fun effective(
        isDownloaded: (TextModelTier, TextModelVariant) -> Boolean,
        finetunedPublished: Boolean = VlmModelManager.FINETUNED_LITERTLM_PUBLISHED,
    ): AnalysisMode {
        val base = isDownloaded(tier, TextModelVariant.BASE)
        return when (this) {
            FAST -> FAST
            DETAILED -> if (base) DETAILED else FAST
            REFINED -> if (base) REFINED else FAST
            TUNED -> when {
                finetunedPublished && isDownloaded(tier, TextModelVariant.FINETUNED) -> TUNED
                base -> DETAILED
                else -> FAST
            }
        }
    }

    companion object {
        /**
         * Derives the mode from the keys saved by any previous version. Unknown or stale values
         * degrade the same way inference does: `picodet` runs RT-DETRv2, `refine_with_gemma` is
         * ignored with the Gemma detector and an unpublished fine-tuned variant loads the base.
         */
        fun fromSettings(
            detectorMode: String,
            refineWithGemma: Boolean,
            variant: String,
            finetunedPublished: Boolean = VlmModelManager.FINETUNED_LITERTLM_PUBLISHED,
        ): AnalysisMode {
            val gemmaDetector = DetectorType.fromKey(detectorMode) == DetectorType.GEMMA4_VISION
            val tuned = TextModelVariant.fromKey(variant) == TextModelVariant.FINETUNED
            return when {
                gemmaDetector && tuned && finetunedPublished -> TUNED
                gemmaDetector -> DETAILED
                refineWithGemma -> REFINED
                else -> FAST
            }
        }

        fun fromKey(key: String?): AnalysisMode = entries.firstOrNull { it.key == key } ?: FAST
    }
}

/** How a mode can be offered in the mode sheet. */
enum class AnalysisModeAvailability {
    READY,
    /** The model is published but this phone does not have it yet: offer the download. */
    NEEDS_DOWNLOAD,
    /** The fine-tuned `.litertlm` is not published: show the mode disabled, never fall back silently. */
    NOT_PUBLISHED,
}

/**
 * Names saved with every analysis and report. They are product names, not user-facing prose, so
 * they stay untranslated and are stable enough to compare.
 */
object AnalysisModels {
    const val RT_DETR_V2 = "RT-DETRv2"
    const val GEMMA4_BASE = "Gemma 4 E2B"
    const val GEMMA4_OCEANGUARD = "Gemma 4 E2B OceanGuard"
    private const val GEMMA4_OCEANGUARD_GGUF = "Gemma 4 E2B OceanGuard (GGUF)"
    private const val GEMMA_PREFIX = "Gemma 4"
    private const val QWEN_FAST = "Qwen 3.5 0.8B"
    private const val QWEN_BALANCED = "Qwen 3.5 2B"
    private const val QWEN_QUALITY = "Qwen 3.5 4B"

    /** Chooses by the file actually loaded, not by the setting, which may have been degraded. */
    fun forGemmaFile(modelPath: String?): String =
        if (modelPath?.substringAfterLast('/')?.contains("oceanguard") == true) {
            GEMMA4_OCEANGUARD
        } else {
            GEMMA4_BASE
        }

    /** RT-DETRv2 boxes with sub-type and material added by a second model. */
    fun refined(detector: String, refiner: String): String = "$detector + $refiner"

    fun forReportTier(tier: TextModelTier, loadedPath: String?): String = when (tier) {
        TextModelTier.FAST -> QWEN_FAST
        TextModelTier.BALANCED -> QWEN_BALANCED
        TextModelTier.QUALITY -> QWEN_QUALITY
        TextModelTier.GEMMA4_E2B -> forGemmaFile(loadedPath)
        TextModelTier.OCEANGUARD_FT -> GEMMA4_OCEANGUARD_GGUF
    }

    /**
     * False when the detector was Gemma: its "confidence" is not a model score, so the UI must not
     * print a percentage. Sessions saved before the model was recorded (null) keep showing it.
     */
    fun hasModelConfidence(modelName: String?): Boolean =
        modelName == null || !modelName.startsWith(GEMMA_PREFIX)
}
