package com.oceanguard.ai.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.components.analysis.OnPhoto
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.rememberOceanLoop

private const val SWEEP_CYCLE_MS = 2_400
private const val DOTS_CYCLE_MS = 900
private const val DOT_COUNT = 3
private const val DOT_IDLE_ALPHA = 0.35f

private val PILL_BOTTOM_PORTRAIT = 120.dp
private val PILL_BOTTOM_LANDSCAPE = 16.dp
private val PILL_SIDE = 24.dp
private val PILL_PADDING_X = 18.dp
private val PILL_PADDING_Y = 12.dp
private val PILL_DOT_GAP = 10.dp
private val SUBTEXT_GAP = 8.dp
private val DOT_SIZE = 6.dp
private val DOT_GAP = 4.dp

/**
 * Feedback over a captured image while a model works and has no boxes to reveal: the sonar line
 * and a status pill. The photo stays visible behind them.
 *
 * @param statusText  Main message shown in the pill.
 * @param subText     Optional secondary line (e.g. the elapsed time).
 * @param accentColor Tint of the line and of the dots.
 */
@Composable
fun InferenceAnimationOverlay(
    statusText: String,
    modifier: Modifier = Modifier,
    subText: String? = null,
    accentColor: Color = OnPhoto.trace,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val pillBottom = if (isLandscape) PILL_BOTTOM_LANDSCAPE else PILL_BOTTOM_PORTRAIT
    Box(modifier = modifier.fillMaxSize()) {
        ScanningOverlay(lineColor = accentColor, cycleDurationMs = SWEEP_CYCLE_MS)
        AnalysisStatusPill(
            statusText = statusText,
            subText = subText,
            accentColor = accentColor,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = pillBottom, start = PILL_SIDE, end = PILL_SIDE),
        )
    }
}

/** What the model is doing now, in words. TalkBack reads each change without moving the focus. */
@Composable
fun AnalysisStatusPill(
    statusText: String,
    modifier: Modifier = Modifier,
    subText: String? = null,
    accentColor: Color = OnPhoto.trace,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = OnPhoto.scrim,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = PILL_PADDING_X, vertical = PILL_PADDING_Y),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleSmall,
                    color = OnPhoto.ink,
                    textAlign = TextAlign.Center,
                )
                if (!LocalReducedMotion.current) {
                    Spacer(modifier = Modifier.width(PILL_DOT_GAP))
                    AnimatedDots(color = accentColor)
                }
            }
        }
        if (!subText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(SUBTEXT_GAP))
            Text(
                text = subText,
                style = MaterialTheme.typography.labelMedium,
                color = OnPhoto.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(OnPhoto.scrim, MaterialTheme.shapes.small)
                    .padding(horizontal = SUBTEXT_GAP),
            )
        }
    }
}

@Composable
private fun AnimatedDots(color: Color) {
    val phase by rememberOceanLoop(DOTS_CYCLE_MS)
    // Recomposes when the active dot changes, not on every frame of the loop.
    val activeIndex by remember { derivedStateOf { (phase * DOT_COUNT).toInt() % DOT_COUNT } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DOT_GAP),
    ) {
        repeat(DOT_COUNT) { index ->
            Box(
                modifier = Modifier
                    .size(DOT_SIZE)
                    .background(
                        color = if (index == activeIndex) color else color.copy(alpha = DOT_IDLE_ALPHA),
                        shape = CircleShape,
                    ),
            )
        }
    }
}
