package com.oceanguard.ai.data

import com.oceanguard.ai.inference.ReportAudience
import com.oceanguard.ai.inference.ToolReportContext
import com.oceanguard.ai.ui.screens.results.reportFigures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportFiguresTest {

    private fun session(id: Long, count: Int, health: Int) = DetectionSession(
        id = id, imageUri = "u$id", debrisList = emptyList(), totalCount = count,
        healthScore = health, imageQuality = ImageQuality.FAIR, processingTimeMs = 1L,
    )

    private val sessions = listOf(
        session(1, 3, 40), session(2, 0, 100), session(3, 5, 60), session(4, 2, 55),
    )

    private fun report() = GeneratedReport(text = "t", language = "es", sessionCount = 4, usedAi = true)

    @Test fun figuresFollowTheCriterionOfTheText() {
        val ctx = ToolReportContext(sessions = sessions, language = "es", audience = ReportAudience.SCIENTIFIC)
        val f = ReportFigureValues.of(sessions)
        assertEquals(ctx.sessionCount, f.photos)
        assertEquals(ctx.totalDebrisItems, f.objects)
        assertEquals(ctx.avgHealthScore, f.health)
    }

    @Test fun savedFiguresSurviveDeletingSessions() {
        val saved = report().withFigures(sessions)
        val header = reportFigures(saved, sessions.drop(1))
        assertEquals(4, header.photos)
        assertEquals(10, header.totalObjects)
        assertEquals(63, header.averageHealth)
    }

    @Test fun oldReportsFallBackToTheExistingSessions() {
        val old = report()
        assertNull(old.savedFigures())
        assertEquals(2, reportFigures(old, sessions.take(2)).photos)
    }

    @Test fun emptySessionsMeanHundredLikeTheText() {
        assertEquals(100, ReportFigureValues.of(emptyList()).health)
    }
}
