package com.oceanguard.ai.ui.screens.results

import com.oceanguard.ai.data.DetectionSession

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
