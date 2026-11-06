package com.oceanguard.ai.ui.screens.onboarding

import androidx.compose.runtime.Immutable
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.data.AnalysisModeAvailability
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.inference.TextModelVariant

/** What the person decided on the welcome pages. */
@Immutable
data class WelcomeResult(val mode: AnalysisMode, val startTour: Boolean)

/** One model the welcome offers, with what it costs to get it. */
@Immutable
data class WelcomeOption(
    val mode: AnalysisMode,
    val availability: AnalysisModeAvailability,
) {
    val enabled: Boolean get() = availability != AnalysisModeAvailability.NOT_PUBLISHED
    val needsDownload: Boolean get() = availability == AnalysisModeAvailability.NEEDS_DOWNLOAD
}

/** What finishing the welcome has to do. Kept pure so the first run can be tested. */
data class WelcomePlan(
    val applyMode: Boolean,
    val downloadMode: AnalysisMode?,
    val startTour: Boolean,
)

object WelcomeLogic {

    /** Modes shown while setting up. Adding type and material is an extra for later, in Home. */
    val OFFERED_MODES = listOf(AnalysisMode.FAST, AnalysisMode.DETAILED, AnalysisMode.TUNED)

    fun options(
        isDownloaded: (TextModelTier, TextModelVariant) -> Boolean,
        finetunedPublished: Boolean,
    ): List<WelcomeOption> = OFFERED_MODES.map { mode ->
        WelcomeOption(mode, mode.availability(isDownloaded, finetunedPublished))
    }

    /** Fast needs nothing, so it is the choice for anyone who skips the page. */
    val DEFAULT_MODE = AnalysisMode.FAST

    fun plan(
        current: AnalysisMode,
        result: WelcomeResult,
        isDownloaded: (TextModelTier, TextModelVariant) -> Boolean,
        finetunedPublished: Boolean,
    ): WelcomePlan {
        val availability = result.mode.availability(isDownloaded, finetunedPublished)
        return WelcomePlan(
            applyMode = result.mode != current && availability != AnalysisModeAvailability.NOT_PUBLISHED,
            downloadMode = result.mode.takeIf { availability == AnalysisModeAvailability.NEEDS_DOWNLOAD },
            startTour = result.startTour,
        )
    }

    /** Total size of what the chosen option downloads; zero when nothing is needed. */
    fun downloadGb(option: WelcomeOption): Float =
        if (option.needsDownload) option.mode.approxDownloadGb ?: 0f else 0f
}
