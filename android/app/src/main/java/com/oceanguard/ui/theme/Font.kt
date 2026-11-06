package com.oceanguard.ai.ui.theme

import androidx.annotation.FontRes
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.oceanguard.ai.R

/**
 * Bundled variable fonts (SIL OFL 1.1, licences in `assets/licenses/`). Nothing is fetched from
 * the network: the typography must look the same offline.
 */
@OptIn(ExperimentalTextApi::class)
private fun variable(@FontRes resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun family(@FontRes resId: Int, vararg weights: FontWeight) =
    FontFamily(weights.map { variable(resId, it) })

/** Titles. */
val SoraFontFamily = family(
    R.font.sora_variable,
    FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold,
)

/** Body text and labels. */
val HankenFontFamily = family(
    R.font.hanken_grotesk_variable,
    FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold,
)

/** Figures and measurements only. */
val JetBrainsMonoFontFamily = family(
    R.font.jetbrains_mono_variable,
    FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold,
)
