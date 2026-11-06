package com.oceanguard.ai.ui.screens.results

import com.oceanguard.ai.ReportGenerationState
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRunLogicTest {

    private fun session(located: Boolean) = DetectionSession(
        imageUri = "content://photos/1.jpg",
        debrisList = emptyList(),
        totalCount = 0,
        healthScore = 100,
        location = if (located) Location(39.5, 2.6) else null,
        imageQuality = ImageQuality.GOOD,
        processingTimeMs = 4_000,
    )

    @Test
    fun stagesFollowTheGenerationState() {
        assertEquals(ReportStage.MODEL, activeStage(ReportGenerationState.LoadingModel))
        assertEquals(ReportStage.DATA, activeStage(ReportGenerationState.Generating))
        assertEquals(ReportStage.DATA, activeStage(ReportGenerationState.ToolExecuting("get_debris_summary", 1)))
        assertEquals(ReportStage.WRITING, activeStage(ReportGenerationState.ComposingProse))
        assertEquals(ReportStage.WRITING, activeStage(ReportGenerationState.StreamingText("# Title")))
        assertNull(activeStage(ReportGenerationState.Idle))
    }

    @Test
    fun earlierStagesAreDoneAndLaterOnesPending() {
        val steps = reportSteps(ReportStage.DATA, toolProgress = 3 to 8)
        assertEquals(StepStatus.DONE, steps[0].status)
        assertEquals(StepStatus.ACTIVE, steps[1].status)
        assertEquals("3/8", steps[1].progress)
        assertEquals(StepStatus.PENDING, steps[2].status)
    }

    @Test
    fun toolProgressOnlyShowsOnTheActiveDataStage() {
        assertNull(reportSteps(ReportStage.WRITING, 8 to 8)[1].progress)
        assertNull(reportSteps(ReportStage.MODEL, 1 to 8)[1].progress)
    }

    @Test
    fun nothingRunningMeansEverythingPending() {
        assertTrue(reportSteps(null, null).all { it.status == StepStatus.PENDING })
    }

    @Test
    fun modelStatusPrefersDownloadingOverAvailable() {
        assertEquals(ReportModelStatus.DOWNLOADING, reportModelStatus(available = false, downloading = true))
        assertEquals(ReportModelStatus.READY, reportModelStatus(available = true, downloading = false))
        assertEquals(ReportModelStatus.NEEDS_DOWNLOAD, reportModelStatus(available = false, downloading = false))
    }

    @Test
    fun downloadStartsTheReportOnlyWhenRequestedAndReady() {
        assertTrue(shouldStartAfterDownload(requested = true, modelNowAvailable = true, generating = false))
        assertFalse(shouldStartAfterDownload(requested = false, modelNowAvailable = true, generating = false))
        assertFalse(shouldStartAfterDownload(requested = true, modelNowAvailable = false, generating = false))
        assertFalse(shouldStartAfterDownload(requested = true, modelNowAvailable = true, generating = true))
    }

    @Test
    fun geoJsonNeedsAtLeastOneLocatedPhoto() {
        assertEquals(ExportBlock.NO_SESSIONS, exportBlock(ReportExportFormat.GEOJSON, emptyList()))
        assertEquals(ExportBlock.NO_LOCATION, exportBlock(ReportExportFormat.GEOJSON, listOf(session(false))))
        assertNull(exportBlock(ReportExportFormat.GEOJSON, listOf(session(false), session(true))))
    }

    @Test
    fun csvNeedsSessionsAndTextFormatsNeedNothing() {
        assertEquals(ExportBlock.NO_SESSIONS, exportBlock(ReportExportFormat.CSV, emptyList()))
        assertNull(exportBlock(ReportExportFormat.CSV, listOf(session(false))))
        assertNull(exportBlock(ReportExportFormat.PDF, emptyList()))
        assertNull(exportBlock(ReportExportFormat.MARKDOWN, emptyList()))
    }
}
