package com.oceanguard.ai.ui.screens.onboarding

import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.data.AnalysisModeAvailability
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.inference.TextModelVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomeLogicTest {

    private val nothing: (TextModelTier, TextModelVariant) -> Boolean = { _, _ -> false }
    private val everything: (TextModelTier, TextModelVariant) -> Boolean = { _, _ -> true }

    @Test fun theWelcomeOffersThreeModesAndFastIsTheDefault() {
        assertEquals(
            listOf(AnalysisMode.FAST, AnalysisMode.DETAILED, AnalysisMode.TUNED),
            WelcomeLogic.options(nothing, finetunedPublished = true).map { it.mode },
        )
        assertEquals(AnalysisMode.FAST, WelcomeLogic.DEFAULT_MODE)
    }

    @Test fun fastCostsNothingAndTheOthersShowTheirSize() {
        val options = WelcomeLogic.options(nothing, finetunedPublished = true).associateBy { it.mode }
        assertEquals(0f, WelcomeLogic.downloadGb(options.getValue(AnalysisMode.FAST)), 0f)
        assertEquals(2.6f, WelcomeLogic.downloadGb(options.getValue(AnalysisMode.DETAILED)), 0f)
        assertEquals(5.2f, WelcomeLogic.downloadGb(options.getValue(AnalysisMode.TUNED)), 0f)
    }

    @Test fun anInstalledModelCostsNothing() {
        val detailed = WelcomeLogic.options(everything, true).first { it.mode == AnalysisMode.DETAILED }
        assertFalse(detailed.needsDownload)
        assertEquals(0f, WelcomeLogic.downloadGb(detailed), 0f)
    }

    @Test fun theUnpublishedTunedModeCannotBePicked() {
        val tuned = WelcomeLogic.options(nothing, finetunedPublished = false)
            .first { it.mode == AnalysisMode.TUNED }
        assertEquals(AnalysisModeAvailability.NOT_PUBLISHED, tuned.availability)
        assertFalse(tuned.enabled)
    }

    @Test fun keepingFastChangesNothingAndDownloadsNothing() {
        val plan = WelcomeLogic.plan(
            AnalysisMode.FAST, WelcomeResult(AnalysisMode.FAST, startTour = false), nothing, true,
        )
        assertFalse(plan.applyMode)
        assertNull(plan.downloadMode)
        assertFalse(plan.startTour)
    }

    @Test fun pickingAMissingModelAppliesItAndStartsItsDownload() {
        val plan = WelcomeLogic.plan(
            AnalysisMode.FAST, WelcomeResult(AnalysisMode.DETAILED, startTour = true), nothing, true,
        )
        assertTrue(plan.applyMode)
        assertEquals(AnalysisMode.DETAILED, plan.downloadMode)
        assertTrue(plan.startTour)
    }

    @Test fun anInstalledModelIsAppliedWithoutDownloading() {
        val plan = WelcomeLogic.plan(
            AnalysisMode.FAST, WelcomeResult(AnalysisMode.DETAILED, startTour = false), everything, true,
        )
        assertTrue(plan.applyMode)
        assertNull(plan.downloadMode)
    }

    @Test fun anUnpublishedModeIsNeverAppliedNorDownloaded() {
        val plan = WelcomeLogic.plan(
            AnalysisMode.FAST, WelcomeResult(AnalysisMode.TUNED, startTour = false), nothing, false,
        )
        assertFalse(plan.applyMode)
        assertNull(plan.downloadMode)
    }
}
