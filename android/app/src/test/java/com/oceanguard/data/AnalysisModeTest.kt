package com.oceanguard.ai.data

import com.oceanguard.ai.inference.DetectorType
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.inference.TextModelVariant
import com.oceanguard.ai.inference.VlmProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisModeTest {

    private val nothing: (TextModelTier, TextModelVariant) -> Boolean = { _, _ -> false }
    private val baseOnly: (TextModelTier, TextModelVariant) -> Boolean =
        { _, v -> v == TextModelVariant.BASE }
    private val tunedOnly: (TextModelTier, TextModelVariant) -> Boolean =
        { _, v -> v == TextModelVariant.FINETUNED }
    private val everything: (TextModelTier, TextModelVariant) -> Boolean = { _, _ -> true }

    private fun derive(detector: String, refine: Boolean, variant: String, published: Boolean) =
        AnalysisMode.fromSettings(detector, refine, variant, published)

    @Test fun eachModeFixesItsDetectorAndVariant() {
        with(AnalysisMode.FAST) {
            assertEquals("rtdetr", detector.key)
            assertFalse(refineWithGemma)
        }
        with(AnalysisMode.DETAILED) {
            assertEquals("gemma4", detector.key)
            assertEquals(TextModelVariant.BASE, variant)
        }
        with(AnalysisMode.TUNED) {
            assertEquals("gemma4", detector.key)
            assertEquals(TextModelVariant.FINETUNED, variant)
        }
        with(AnalysisMode.REFINED) {
            assertEquals("rtdetr", detector.key)
            assertTrue(refineWithGemma)
        }
    }

    @Test fun everyModeWritesAProviderAndTierThatAgree() {
        AnalysisMode.entries.forEach { mode ->
            assertEquals(mode.provider, mode.tier.provider)
            assertEquals(VlmProvider.GEMMA4, mode.provider)
            assertEquals(TextModelTier.GEMMA4_E2B, TextModelTier.fromKey(mode.tier.name.lowercase()))
            assertEquals(mode.detector, DetectorType.fromKey(mode.detector.key))
        }
    }

    @Test fun displayOrderIsFastFirstAndRefinedLast() {
        assertEquals(
            listOf("fast", "detailed", "tuned", "refined"),
            AnalysisMode.entries.map { it.key },
        )
    }

    @Test fun savedSecondsAreOnlyMeasuredOnes() {
        assertEquals(4, AnalysisMode.FAST.approxSecondsPerPhoto)
        assertEquals(7, AnalysisMode.DETAILED.approxSecondsPerPhoto)
        assertEquals(9, AnalysisMode.TUNED.approxSecondsPerPhoto)
        assertNull(AnalysisMode.REFINED.approxSecondsPerPhoto)
    }

    @Test fun radarIsSlowerOnSlowerModels() {
        assertTrue(AnalysisMode.FAST.radarRadPerSec > AnalysisMode.DETAILED.radarRadPerSec)
        assertTrue(AnalysisMode.DETAILED.radarRadPerSec > AnalysisMode.TUNED.radarRadPerSec)
    }

    @Test fun defaultInstallMigratesToFast() {
        assertEquals(AnalysisMode.FAST, derive("rtdetr", false, "base", false))
    }

    @Test fun oldGemmaDetectorMigratesToDetailed() {
        assertEquals(AnalysisMode.DETAILED, derive("gemma4", false, "base", false))
    }

    @Test fun oldRefineSwitchMigratesToRefined() {
        assertEquals(AnalysisMode.REFINED, derive("rtdetr", true, "base", false))
    }

    @Test fun fineTunedMigratesToTunedOnlyOncePublished() {
        assertEquals(AnalysisMode.TUNED, derive("gemma4", false, "finetuned", true))
        assertEquals(AnalysisMode.DETAILED, derive("gemma4", false, "finetuned", false))
    }

    @Test fun refineIsIgnoredWithTheGemmaDetectorAsInference() {
        assertEquals(AnalysisMode.DETAILED, derive("gemma4", true, "base", false))
    }

    @Test fun staleOrUnknownValuesDegradeLikeInference() {
        assertEquals(AnalysisMode.FAST, derive("picodet", false, "base", true))
        assertEquals(AnalysisMode.FAST, derive("", false, "", true))
        assertEquals(AnalysisMode.FAST, derive("rtdetr", false, "finetuned", true))
    }

    @Test fun roundTripThroughTheSavedKeys() {
        AnalysisMode.entries.forEach { mode ->
            val back = derive(mode.detector.key, mode.refineWithGemma, mode.variant.key, true)
            assertEquals(mode, back)
        }
    }

    @Test fun keysRoundTripAndDefaultToFast() {
        AnalysisMode.entries.forEach { assertEquals(it, AnalysisMode.fromKey(it.key)) }
        assertEquals(AnalysisMode.FAST, AnalysisMode.fromKey(null))
        assertEquals(AnalysisMode.FAST, AnalysisMode.fromKey("nope"))
    }

    @Test fun fastNeedsNothing() {
        assertFalse(AnalysisMode.FAST.needsGemma)
        assertEquals(AnalysisModeAvailability.READY, AnalysisMode.FAST.availability(nothing, false))
    }

    @Test fun missingGemmaAsksForTheDownload() {
        assertEquals(
            AnalysisModeAvailability.NEEDS_DOWNLOAD,
            AnalysisMode.DETAILED.availability(nothing, false),
        )
        assertEquals(
            AnalysisModeAvailability.READY,
            AnalysisMode.REFINED.availability(baseOnly, false),
        )
    }

    @Test fun unpublishedTunedIsNeverOffered() {
        assertEquals(
            AnalysisModeAvailability.NOT_PUBLISHED,
            AnalysisMode.TUNED.availability(everything, false),
        )
        assertEquals(
            AnalysisModeAvailability.NEEDS_DOWNLOAD,
            AnalysisMode.TUNED.availability(baseOnly, true),
        )
    }

    @Test fun effectiveModeIsWhatReallyRuns() {
        assertEquals(AnalysisMode.FAST, AnalysisMode.DETAILED.effective(nothing, true))
        assertEquals(AnalysisMode.FAST, AnalysisMode.REFINED.effective(nothing, true))
        assertEquals(AnalysisMode.DETAILED, AnalysisMode.TUNED.effective(baseOnly, true))
        assertEquals(AnalysisMode.DETAILED, AnalysisMode.TUNED.effective(everything, false))
        assertEquals(AnalysisMode.FAST, AnalysisMode.TUNED.effective(tunedOnly, false))
        assertEquals(AnalysisMode.TUNED, AnalysisMode.TUNED.effective(tunedOnly, true))
    }

    @Test fun modelNameComesFromTheLoadedFile() {
        assertEquals(
            AnalysisModels.GEMMA4_BASE,
            AnalysisModels.forGemmaFile("/storage/models/gemma-4-E2B-it.litertlm"),
        )
        assertEquals(
            AnalysisModels.GEMMA4_OCEANGUARD,
            AnalysisModels.forGemmaFile("/storage/models/gemma-4-E2B-it-oceanguard.litertlm"),
        )
        assertEquals(AnalysisModels.GEMMA4_BASE, AnalysisModels.forGemmaFile(null))
    }

    @Test fun reportsNameTheTierThatWorked() {
        assertEquals("Qwen 3.5 0.8B", AnalysisModels.forReportTier(TextModelTier.FAST, null))
        assertEquals(
            AnalysisModels.GEMMA4_OCEANGUARD,
            AnalysisModels.forReportTier(TextModelTier.GEMMA4_E2B, "a/x-oceanguard.litertlm"),
        )
        assertTrue(
            AnalysisModels.forReportTier(TextModelTier.OCEANGUARD_FT, null).contains("GGUF"),
        )
    }

    @Test fun gemmaDetectionsHaveNoPercentageButRefinedOnesDo() {
        assertFalse(AnalysisModels.hasModelConfidence(AnalysisModels.GEMMA4_BASE))
        assertFalse(AnalysisModels.hasModelConfidence(AnalysisModels.GEMMA4_OCEANGUARD))
        assertTrue(AnalysisModels.hasModelConfidence(AnalysisModels.RT_DETR_V2))
        assertTrue(
            AnalysisModels.hasModelConfidence(
                AnalysisModels.refined(AnalysisModels.RT_DETR_V2, AnalysisModels.GEMMA4_BASE),
            ),
        )
        assertTrue(AnalysisModels.hasModelConfidence(null))
    }
}
