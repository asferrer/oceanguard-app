package com.oceanguard.ai.ui.screens.home

import com.oceanguard.ai.ModelLoadingState
import com.oceanguard.ai.ModelStatus
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.data.AnalysisModeAvailability
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLogicTest {

    private val ready = AnalysisModeAvailability.READY
    private val missing = AnalysisModeAvailability.NEEDS_DOWNLOAD

    @Test fun detectorStatusFollowsTheDetectorThatWillRun() {
        val state = ModelLoadingState(rtdetr = ModelStatus.Ready, gemma4Vision = ModelStatus.Loading)
        assertEquals(ModelStatus.Ready, HomeLogic.detectorStatus(AnalysisMode.FAST, state))
        assertEquals(ModelStatus.Ready, HomeLogic.detectorStatus(AnalysisMode.REFINED, state))
        assertEquals(ModelStatus.Loading, HomeLogic.detectorStatus(AnalysisMode.DETAILED, state))
        assertEquals(ModelStatus.Loading, HomeLogic.detectorStatus(AnalysisMode.TUNED, state))
    }

    @Test fun aDownloadInFlightWinsOverEverythingElse() {
        val kind = HomeLogic.statusKind(missing, isDownloading = true, ModelStatus.Ready)
        assertEquals(DetectorStatusKind.DOWNLOADING, kind)
    }

    @Test fun aMissingModelOffersItsDownload() {
        val kind = HomeLogic.statusKind(missing, isDownloading = false, ModelStatus.Standby)
        assertEquals(DetectorStatusKind.NEEDS_DOWNLOAD, kind)
        assertTrue(kind.canScan)
    }

    @Test fun everyEngineStateHasItsOwnKind() {
        val expected = mapOf(
            ModelStatus.Ready to DetectorStatusKind.READY,
            ModelStatus.Loading to DetectorStatusKind.LOADING,
            ModelStatus.WarmingUp to DetectorStatusKind.WARMING_UP,
            ModelStatus.Error to DetectorStatusKind.FAILED,
            ModelStatus.Standby to DetectorStatusKind.STANDBY,
            ModelStatus.NotLoaded to DetectorStatusKind.NOT_LOADED,
        )
        expected.forEach { (status, kind) ->
            assertEquals(kind, HomeLogic.statusKind(ready, false, status))
        }
    }

    @Test fun onlyReadyOrDownloadableDebrisCanBeScanned() {
        DetectorStatusKind.entries.forEach { kind ->
            val expected = kind == DetectorStatusKind.READY || kind == DetectorStatusKind.NEEDS_DOWNLOAD
            assertEquals(expected, HomeLogic.scanEnabled(ScanTarget.DEBRIS, kind))
        }
    }

    @Test fun speciesAreScannableWhateverTheDetectorDoes() {
        DetectorStatusKind.entries.forEach { kind ->
            assertTrue(HomeLogic.scanEnabled(ScanTarget.SPECIES, kind))
            assertFalse(HomeLogic.needsDownloadFirst(ScanTarget.SPECIES, kind))
        }
    }

    @Test fun debrisAsksForTheDownloadOnlyWhenTheModelIsMissing() {
        assertTrue(HomeLogic.needsDownloadFirst(ScanTarget.DEBRIS, DetectorStatusKind.NEEDS_DOWNLOAD))
        assertFalse(HomeLogic.needsDownloadFirst(ScanTarget.DEBRIS, DetectorStatusKind.READY))
    }

    @Test fun gemmaIsPrewarmedOnlyWhenItIsOnDiskAndNotInMemory() {
        val idle = ModelStatus.NotLoaded
        assertTrue(HomeLogic.shouldPrewarmGemma(AnalysisMode.DETAILED, ready, idle))
        assertTrue(HomeLogic.shouldPrewarmGemma(AnalysisMode.REFINED, ready, ModelStatus.Standby))
        assertFalse(HomeLogic.shouldPrewarmGemma(AnalysisMode.FAST, ready, idle))
        assertFalse(HomeLogic.shouldPrewarmGemma(AnalysisMode.DETAILED, missing, idle))
        assertFalse(HomeLogic.shouldPrewarmGemma(AnalysisMode.DETAILED, ready, ModelStatus.Loading))
        assertFalse(HomeLogic.shouldPrewarmGemma(AnalysisMode.DETAILED, ready, ModelStatus.Ready))
    }

    @Test fun theTourWaitsForTheLocationPrompt() {
        assertFalse(HomeLogic.shouldStartTour(tourComplete = false, locationResolved = false))
        assertTrue(HomeLogic.shouldStartTour(tourComplete = false, locationResolved = true))
        assertFalse(HomeLogic.shouldStartTour(tourComplete = true, locationResolved = true))
    }

    @Test fun updateDialogsStayAwayFromTheTour() {
        assertFalse(HomeLogic.mayShowUpdateDialogs(tourComplete = false, tourActive = false))
        assertFalse(HomeLogic.mayShowUpdateDialogs(tourComplete = true, tourActive = true))
        assertTrue(HomeLogic.mayShowUpdateDialogs(tourComplete = true, tourActive = false))
    }

    @Test fun entriesAreStaggeredAndNeverNegative() {
        assertEquals(0, HomeLogic.entryDelayMs(-2))
        assertTrue(HomeLogic.entryDelayMs(3) > HomeLogic.entryDelayMs(1))
    }

    @Test fun sizesReadLikeThePhoneShowsThem() {
        assertEquals("0 B", HomeLogic.humanReadableSize(0))
        assertEquals("512 KB", HomeLogic.humanReadableSize(512L * 1024))
        assertEquals("2.6 GB", HomeLogic.humanReadableSize((2.6 * 1024 * 1024 * 1024).toLong()))
        assertEquals("2.6", HomeLogic.formatGb(2.6f, Locale.US))
        assertEquals("5,2", HomeLogic.formatGb(5.2f, Locale.GERMANY))
    }

    @Test fun theProgressLineShowsBytesAndPercent() {
        val line = HomeLogic.formatBytesLine(50L * 1024 * 1024, 100L * 1024 * 1024, 0.5f)
        assertEquals("50.0 MB / 100.0 MB  (50%)", line)
        assertTrue(HomeLogic.formatBytesLine(0, 0, 1.7f).endsWith("(100%)"))
    }
}
