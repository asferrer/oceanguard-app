package com.oceanguard.ai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.oceanguard.ai.ui.components.analysis.OnPhoto
import com.oceanguard.ai.ui.components.analysis.drawSweepLine
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.rememberOceanLoop

private const val DEFAULT_CYCLE_MS = 2_000

/**
 * Sonar line that sweeps the whole area while a model is working, for pipelines that deliver no
 * boxes to reveal. Draws nothing under "Remove animations": callers keep a status text.
 */
@Composable
fun ScanningOverlay(
    modifier: Modifier = Modifier,
    lineColor: Color = OnPhoto.trace,
    cycleDurationMs: Int = DEFAULT_CYCLE_MS,
) {
    if (LocalReducedMotion.current) return
    val progress by rememberOceanLoop(cycleDurationMs)
    Canvas(modifier = modifier.fillMaxSize()) {
        drawSweepLine(Rect(Offset.Zero, size), progress, lineColor, alpha = 1f)
    }
}
