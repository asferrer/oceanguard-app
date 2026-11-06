package com.oceanguard.ai.ui.screens.results

import com.oceanguard.ai.data.AnalysisModels
import kotlin.math.roundToInt

/**
 * Percentage to print next to a detection, or null when none should be shown: Gemma's
 * "confidence" is not a score its model produces. Rows saved before the model was recorded
 * (null) keep showing it.
 */
fun confidenceLabel(confidence: Float, modelName: String?): String? =
    if (AnalysisModels.hasModelConfidence(modelName)) {
        "${(confidence.coerceIn(0f, 1f) * 100f).roundToInt()}%"
    } else {
        null
    }
