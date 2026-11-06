package com.oceanguard.ai.ui.screens.report

import com.oceanguard.ai.ReportGenerationState
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.data.HealthTrend
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.ZoneCluster
import com.oceanguard.ai.data.settings.ReportPeriod
import com.oceanguard.ai.data.settings.ReportPeriodSelection
import com.oceanguard.ai.inference.VlmDownloadState
import com.oceanguard.ai.ui.components.sonar.ButtonProgress
import com.oceanguard.ai.ui.screens.results.downloadProgress
import com.oceanguard.ai.ui.screens.results.generationInfo
import com.oceanguard.ai.ui.screens.results.inRange
import com.oceanguard.ai.ui.screens.results.isDownloading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ReportSupportTest {

    private fun report(id: Long, place: String?, time: Long) = GeneratedReport(
        id = id,
        text = "text",
        language = "en",
        sessionCount = 1,
        usedAi = true,
        timestamp = Date(time),
        locationName = place,
    )

    private fun session(id: Long, time: Long) = DetectionSession(
        id = id,
        imageUri = "content://photos/$id.jpg",
        debrisList = emptyList(),
        totalCount = 0,
        healthScore = 100,
        location = Location(39.5, 2.6),
        timestamp = Date(time),
        imageQuality = ImageQuality.GOOD,
        processingTimeMs = 4_000,
    )

    @Test
    fun savedReportsFilterByPlaceAndDates() {
        val reports = listOf(report(1, "Palma", 100), report(2, "Ibiza", 200), report(3, "Palma", 300))
        assertEquals(listOf(1L, 3L), filterReports(reports, "Palma", null, null).map { it.id })
        assertEquals(listOf(2L, 3L), filterReports(reports, null, 150, null).map { it.id })
        assertEquals(listOf(1L), filterReports(reports, "Palma", null, 200).map { it.id })
        assertEquals(3, filterReports(reports, null, null, null).size)
    }

    @Test
    fun rangeBoundsAreInclusiveAndOptional() {
        assertTrue(inRange(10, 10, 20))
        assertTrue(inRange(20, 10, 20))
        assertFalse(inRange(21, 10, 20))
        assertTrue(inRange(5, null, null))
        assertFalse(inRange(5, 10, null))
    }

    @Test
    fun selectionKeepsOnlyPhotosOfThePeriod() {
        val now = 10L * 24 * 60 * 60 * 1000
        val day = 24L * 60 * 60 * 1000
        val zone = ZoneCluster(
            centroidLat = 39.5,
            centroidLon = 2.6,
            sessions = listOf(session(1, now - 20 * day), session(2, now - 2 * day)),
            aggregateHealthScore = 100,
            trend = HealthTrend.STABLE,
        )
        val week = ReportSelection.of(zone, ReportPeriodSelection(ReportPeriod.LAST_7_DAYS), now)
        assertEquals(listOf(2L), week.sessions.map { it.id })
        assertEquals(2, ReportSelection.of(zone, ReportPeriodSelection(ReportPeriod.ALL), now).sessions.size)
    }

    @Test
    fun selectionWithoutZoneIsEmpty() {
        assertTrue(ReportSelection.of(null, ReportPeriodSelection()).sessions.isEmpty())
    }

    @Test
    fun streamedTokensDoNotChangeTheGenerationInfo() {
        val first = generationInfo(ReportGenerationState.StreamingText("a", tokenCount = 1, maxTokens = 100))
        val second = generationInfo(ReportGenerationState.StreamingText("ab", tokenCount = 2, maxTokens = 100))
        assertEquals(first, second)
        assertTrue(first.generating)
    }

    @Test
    fun toolProgressIsReportedOnlyWithATotal() {
        val withTotal = generationInfo(ReportGenerationState.ToolExecuting("get_debris_summary", 1, 3, 8))
        assertEquals(3 to 8, withTotal.toolProgress)
        assertEquals("get_debris_summary", withTotal.toolName)
        assertNull(generationInfo(ReportGenerationState.ToolExecuting("x", 1, 0, 0)).toolProgress)
    }

    @Test
    fun idleIsNotGenerating() {
        assertFalse(generationInfo(ReportGenerationState.Idle).generating)
    }

    @Test
    fun downloadShowsAFractionOnlyWhileBytesArrive() {
        assertEquals(
            ButtonProgress.Determinate(0.4f),
            downloadProgress(VlmDownloadState.Downloading(0.4f, 40, 100)),
        )
        assertEquals(ButtonProgress.Indeterminate, downloadProgress(VlmDownloadState.Preparing))
        assertEquals(ButtonProgress.Indeterminate, downloadProgress(VlmDownloadState.Installing))
        assertEquals(ButtonProgress.Idle, downloadProgress(VlmDownloadState.Idle))
        assertEquals(ButtonProgress.Idle, downloadProgress(VlmDownloadState.Complete))
        assertTrue(isDownloading(VlmDownloadState.Preparing))
        assertFalse(isDownloading(VlmDownloadState.Error("x")))
    }

    @Test
    fun headingIsTheLastSectionWritten() {
        assertEquals("", currentHeading("no sections yet"))
        assertEquals("Findings", currentHeading("intro\n## Summary\ntext\n## Findings\nmore"))
    }

    @Test
    fun etaNeedsAUsableSpeed() {
        val slow = ReportGenerationState.StreamingText("", tokenCount = 10, tokensPerSec = 0.2f, maxTokens = 100)
        val fast = ReportGenerationState.StreamingText("", tokenCount = 50, tokensPerSec = 10f, maxTokens = 100)
        assertNull(etaSeconds(slow))
        assertEquals(5, etaSeconds(fast))
        assertEquals("5s", formatEta(5))
        assertEquals("2m 5s", formatEta(125))
    }
}
