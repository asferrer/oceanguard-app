package com.oceanguard.ai.ui.screens.results

import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.data.savedFigures

/** A saved analysis reduced to what the figures need, so they are computed like a batch's. */
fun DetectionSession.toOutcome(): PhotoOutcome = PhotoOutcome(
    healthScore = healthScore,
    totalCount = totalCount,
    typeNames = debrisList.map { it.type.name },
    modelName = modelName,
)

/** Photos, objects and average health of the sessions a report covers. */
fun reportFigures(sessions: List<DetectionSession>): BatchStats =
    summarizeBatch(sessions.map { it.toOutcome() }, durationMs = 0L)

/**
 * What the header of a saved report shows: the figures stored with it, which match its text, or,
 * for a report saved before they were stored, those of the sessions that still exist.
 */
fun reportFigures(report: GeneratedReport, sessions: List<DetectionSession>): BatchStats {
    val saved = report.savedFigures() ?: return reportFigures(sessions)
    return reportFigures(emptyList()).copy(
        photos = saved.photos,
        totalObjects = saved.objects,
        averageHealth = saved.health,
    )
}
