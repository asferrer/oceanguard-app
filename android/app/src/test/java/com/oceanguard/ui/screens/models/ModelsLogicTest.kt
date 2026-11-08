package com.oceanguard.ai.ui.screens.models

import com.oceanguard.ai.ModelLoadingState
import com.oceanguard.ai.ModelStatus
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.data.AnalysisModels
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.inference.TextModelVariant
import com.oceanguard.ai.inference.VlmDownloadState
import com.oceanguard.ai.ui.components.sonar.ButtonProgress
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsLogicTest {

    private val nothing: (TextModelTier, TextModelVariant) -> Boolean = { _, _ -> false }
    private val baseOnly: (TextModelTier, TextModelVariant) -> Boolean =
        { _, v -> v == TextModelVariant.BASE }
    private val everything: (TextModelTier, TextModelVariant) -> Boolean = { _, _ -> true }

    // --- before downloading -------------------------------------------------

    @Test fun enoughSpaceOnWifiJustStarts() {
        val plan = planDownload(2_580_000_000L, 10_000_000_000L, NetworkKind.UNMETERED)
        assertEquals(DownloadPlan.Proceed, plan)
    }

    @Test fun missingSpaceReportsHowMuchIsMissingIncludingTheMargin() {
        val plan = planDownload(2_000_000_000L, 2_100_000_000L, NetworkKind.UNMETERED)
        assertEquals(DownloadPlan.NoSpace(2_300_000_000L - 2_100_000_000L), plan)
    }

    @Test fun spaceIsCheckedBeforeTheNetwork() {
        val plan = planDownload(2_000_000_000L, 100L, NetworkKind.NONE)
        assertTrue(plan is DownloadPlan.NoSpace)
    }

    @Test fun noNetworkAndMeteredNetworkAreReportedApart() {
        assertEquals(DownloadPlan.NoNetwork, planDownload(1L, 5_000_000_000L, NetworkKind.NONE))
        assertEquals(DownloadPlan.Metered, planDownload(1L, 5_000_000_000L, NetworkKind.METERED))
    }

    @Test fun exactlyTheNeededSpaceIsEnough() {
        val plan = planDownload(1_000L, 1_000L + SPACE_MARGIN_BYTES, NetworkKind.UNMETERED)
        assertEquals(DownloadPlan.Proceed, plan)
    }

    @Test fun sizesUseDecimalUnits() {
        assertEquals("2.6 GB", formatBytes(2_580_000_000L, Locale.US))
        assertEquals("533 MB", formatBytes(533_000_000L, Locale.US))
        assertEquals("5.2 GB", formatBytes(5_245_718_144L, Locale.US))
        assertEquals("12 KB", formatBytes(12_000L, Locale.US))
        assertEquals("2,6 GB", formatBytes(2_580_000_000L, Locale.GERMANY))
    }

    // --- catalog ------------------------------------------------------------

    @Test fun plainListOffersOnlyWhatIsNotAdvancedAndNotBoundToAMode() {
        val split = splitCatalog({ false }, advanced = false)
        assertEquals(listOf(CatalogModel.SPECIES_PACK), split.downloadable)
        assertTrue(split.installed.isEmpty())
    }

    @Test fun advancedListAddsQwenAndTheGguf() {
        val split = splitCatalog({ false }, advanced = true)
        assertEquals(
            listOf(
                CatalogModel.SPECIES_PACK, CatalogModel.QWEN_FAST, CatalogModel.QWEN_BALANCED,
                CatalogModel.QWEN_QUALITY, CatalogModel.GEMMA_GGUF,
            ),
            split.downloadable,
        )
    }

    @Test fun anInstalledAdvancedModelIsListedEvenWithAdvancedOff() {
        val split = splitCatalog({ it == CatalogModel.QWEN_BALANCED }, advanced = false)
        assertEquals(listOf(CatalogModel.QWEN_BALANCED), split.installed)
        assertFalse(CatalogModel.QWEN_BALANCED in split.downloadable)
    }

    @Test fun onlyTextModelsCanBeDeleted() {
        assertFalse(CatalogModel.SPECIES_PACK.deletable)
        assertTrue(CatalogModel.entries.filter { it != CatalogModel.SPECIES_PACK }.all { it.deletable })
    }

    @Test fun modesMapToTheirModelFile() {
        assertNull(CatalogModel.forMode(AnalysisMode.FAST))
        assertEquals(CatalogModel.GEMMA_BASE, CatalogModel.forMode(AnalysisMode.DETAILED))
        assertEquals(CatalogModel.GEMMA_TUNED, CatalogModel.forMode(AnalysisMode.TUNED))
        assertEquals(CatalogModel.GEMMA_BASE, CatalogModel.forMode(AnalysisMode.REFINED))
    }

    @Test fun aDownloadedFileIdentifiesItsModel() {
        val species = listOf("clip_vitb32.onnx", "species_index_v1.bin")
        val tuned = "gemma-4-E2B-it-oceanguard.litertlm"
        assertEquals(CatalogModel.GEMMA_BASE, modelForFile("gemma-4-E2B-it.litertlm", tuned, species))
        assertEquals(CatalogModel.GEMMA_TUNED, modelForFile(tuned, tuned, species))
        assertEquals(CatalogModel.QWEN_QUALITY, modelForFile("Qwen3.5-4B-Q4_K_M.gguf", tuned, species))
        assertEquals(
            CatalogModel.GEMMA_GGUF,
            modelForFile("gemma-4-E2B-it-oceanguard-Q4_K_M.gguf", tuned, species),
        )
        assertEquals(CatalogModel.SPECIES_PACK, modelForFile("clip_vitb32.onnx", tuned, species))
        assertNull(modelForFile("unknown.bin", tuned, species))
    }

    @Test fun aManifestFilenameForTheTunedModelIsFollowed() {
        val renamed = "gemma-4-E2B-it-oceanguard-v2.litertlm"
        assertEquals(CatalogModel.GEMMA_TUNED, modelForFile(renamed, renamed, emptyList()))
    }

    // --- in use now ---------------------------------------------------------

    @Test fun theEffectiveModeIsTheLitRing() {
        val rings = ringStates(AnalysisMode.DETAILED, baseOnly, finetunedPublished = true)
        assertEquals(
            listOf(RingState.READY, RingState.ACTIVE, RingState.MISSING, RingState.READY),
            rings.map { it.state },
        )
        assertEquals(AnalysisMode.entries.toList(), rings.map { it.mode })
    }

    @Test fun anUnpublishedTunedModelIsUnavailableNotMissing() {
        val rings = ringStates(AnalysisMode.FAST, everything, finetunedPublished = false)
        assertEquals(RingState.UNAVAILABLE, rings.first { it.mode == AnalysisMode.TUNED }.state)
        assertEquals(RingState.ACTIVE, rings.first { it.mode == AnalysisMode.FAST }.state)
    }

    @Test fun withNothingDownloadedOnlyFastIsReady() {
        val rings = ringStates(AnalysisMode.FAST, nothing, finetunedPublished = true)
        assertEquals(RingState.ACTIVE, rings[0].state)
        assertTrue(rings.drop(1).all { it.state == RingState.MISSING })
    }

    @Test fun ringsGrowFromTheMiddleToTheEdge() {
        val radii = (0 until 4).map { ringRadius(it, 4, 100f) }
        assertEquals(100f, radii.last(), 0.001f)
        assertTrue(radii.zipWithNext().all { (a, b) -> a < b })
        assertEquals(100f, ringRadius(0, 1, 100f), 0.001f)
    }

    @Test fun detectionNamesAreTheOnesSavedWithSessions() {
        assertEquals(AnalysisModels.RT_DETR_V2, detectionModelName(AnalysisMode.FAST))
        assertEquals(AnalysisModels.GEMMA4_BASE, detectionModelName(AnalysisMode.DETAILED))
        assertEquals(AnalysisModels.GEMMA4_OCEANGUARD, detectionModelName(AnalysisMode.TUNED))
        assertEquals("RT-DETRv2 + Gemma 4 E2B", detectionModelName(AnalysisMode.REFINED))
    }

    @Test fun continuousScanAlwaysUsesTheBundledDetector() {
        val uses = taskUses(AnalysisMode.TUNED, reportModel = null, speciesReady = false)
        assertEquals(AnalysisModels.RT_DETR_V2, uses.first { it.task == ModelTask.SCAN }.modelName)
        assertEquals(AnalysisModels.GEMMA4_OCEANGUARD, uses.first { it.task == ModelTask.DETECT }.modelName)
        assertNull(uses.first { it.task == ModelTask.REPORT }.modelName)
        assertNull(uses.first { it.task == ModelTask.SPECIES }.modelName)
    }

    @Test fun engineStateComesFromTheEngineBehindTheDetector() {
        val state = ModelLoadingState(rtdetr = ModelStatus.Ready, gemma4Vision = ModelStatus.Loading)
        assertEquals(LoadState.READY, detectorLoadState(AnalysisMode.FAST, state))
        assertEquals(LoadState.LOADING, detectorLoadState(AnalysisMode.DETAILED, state))
        val failed = state.copy(gemma4Vision = ModelStatus.Error)
        assertEquals(LoadState.ERROR, detectorLoadState(AnalysisMode.TUNED, failed))
        val idle = ModelLoadingState()
        assertEquals(LoadState.ON_DEMAND, detectorLoadState(AnalysisMode.DETAILED, idle))
    }

    // --- mode waiting for its download --------------------------------------

    @Test fun aWaitingModeIsAppliedOnlyWhenItsModelIsThere() {
        assertFalse(shouldApplyPending(AnalysisMode.DETAILED, nothing, true))
        assertTrue(shouldApplyPending(AnalysisMode.DETAILED, baseOnly, true))
        assertFalse(shouldApplyPending(null, everything, true))
        assertFalse(shouldApplyPending(AnalysisMode.TUNED, everything, false))
    }

    // --- progress on the button ---------------------------------------------

    private val downloading = VlmDownloadState.Downloading(0.4f, 400L, 1_000L, "gemma-4-E2B-it.litertlm")

    @Test fun theFileNameIdentifiesWhoOwnsTheDownload() {
        val owner = downloadOwner(downloading, requested = null, "x.litertlm", emptyList())
        assertEquals(CatalogModel.GEMMA_BASE, owner)
    }

    @Test fun preparingAndInstallingBelongToTheLastRequest() {
        val requested = CatalogModel.QWEN_FAST
        assertEquals(requested, downloadOwner(VlmDownloadState.Preparing, requested, "x", emptyList()))
        assertEquals(requested, downloadOwner(VlmDownloadState.Installing, requested, "x", emptyList()))
        assertNull(downloadOwner(VlmDownloadState.Idle, requested, "x", emptyList()))
        assertNull(downloadOwner(VlmDownloadState.Complete, requested, "x", emptyList()))
    }

    @Test fun onlyTheOwnerShowsProgress() {
        assertEquals(ButtonProgress.Determinate(0.4f), buttonProgress(downloading, isOwner = true))
        assertEquals(ButtonProgress.Idle, buttonProgress(downloading, isOwner = false))
        assertEquals(ButtonProgress.Indeterminate, buttonProgress(VlmDownloadState.Preparing, true))
        assertEquals(ButtonProgress.Indeterminate, buttonProgress(VlmDownloadState.Installing, true))
        assertEquals(ButtonProgress.Idle, buttonProgress(VlmDownloadState.Idle, true))
    }

    @Test fun theSingleDownloadSlotIsBusyWhileWorking() {
        assertTrue(isDownloadBusy(downloading))
        assertTrue(isDownloadBusy(VlmDownloadState.Preparing))
        assertFalse(isDownloadBusy(VlmDownloadState.Idle))
        assertFalse(isDownloadBusy(VlmDownloadState.Error("x")))
    }

    @Test fun theReportModelOfATierIsFoundInTheCatalog() {
        assertEquals(
            CatalogModel.GEMMA_BASE,
            CatalogModel.forTier(TextModelTier.GEMMA4_E2B, TextModelVariant.BASE),
        )
        assertEquals(
            CatalogModel.GEMMA_TUNED,
            CatalogModel.forTier(TextModelTier.GEMMA4_E2B, TextModelVariant.FINETUNED),
        )
        assertEquals(
            CatalogModel.QWEN_FAST,
            CatalogModel.forTier(TextModelTier.FAST, TextModelVariant.FINETUNED),
        )
    }
}
