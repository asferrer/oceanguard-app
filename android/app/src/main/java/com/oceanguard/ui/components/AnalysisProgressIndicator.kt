package com.oceanguard.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.UiState
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.rememberOceanLoop
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlin.math.abs

private const val PULSE_CYCLE_MS = 1_800
private const val PULSE_MIN_ALPHA = 0.7f
private const val FILL_ALPHA = 0.18f
private const val BORDER_ALPHA = 0.6f

private val CHIP_MIN_HEIGHT = 38.dp
private val CHIP_PADDING = 12.dp
private val CHIP_GAP = 8.dp
private val STRIP_PADDING = 16.dp
private val ICON_SIZE = 16.dp
private val SPINNER_SIZE = 14.dp
private val SPINNER_WIDTH = 2.dp
private val PENDING_DOT = 10.dp

/**
 * Two-chip progress strip for the single-image analysis pipeline.
 *
 * Stage 1: "Preparing", active during [UiState.ModelLoading] (image load, model cold start).
 * Stage 2: "Detecting" or "Deep analysis", active during [UiState.Detecting] and
 * [UiState.AnalyzingDeep]. The label depends on [isDeepAnalysis].
 *
 * The active chip breathes softly; under "Remove animations" it stays still.
 */
@Composable
fun AnalysisProgressIndicator(
    state: UiState,
    isDeepAnalysis: Boolean,
    modifier: Modifier = Modifier,
) {
    val (prepareStatus, analyzeStatus) = stageStatuses(state)
    val secondStage = if (isDeepAnalysis) R.string.sonar_stage_deep else R.string.sonar_stage_detecting

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = STRIP_PADDING),
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StageChip(
            label = stringResource(R.string.sonar_stage_preparing),
            status = prepareStatus,
            modifier = Modifier.weight(1f),
        )
        StageChip(
            label = stringResource(secondStage),
            status = analyzeStatus,
            modifier = Modifier.weight(1f),
        )
    }
}

private enum class ChipStatus { PENDING, ACTIVE, DONE }

private fun stageStatuses(state: UiState): Pair<ChipStatus, ChipStatus> = when (state) {
    is UiState.ModelLoading -> ChipStatus.ACTIVE to ChipStatus.PENDING
    is UiState.Detecting,
    is UiState.AnalyzingDeep,
    is UiState.DetectionsReady,
    -> ChipStatus.DONE to ChipStatus.ACTIVE
    is UiState.AnalysisComplete -> ChipStatus.DONE to ChipStatus.DONE
    else -> ChipStatus.PENDING to ChipStatus.PENDING
}

@Composable
private fun chipContentColor(status: ChipStatus): Color = when (status) {
    ChipStatus.DONE -> MaterialTheme.colorScheme.primary
    ChipStatus.ACTIVE -> SonarTheme.colors.echo
    ChipStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun StageChip(
    label: String,
    status: ChipStatus,
    modifier: Modifier = Modifier,
) {
    val content = chipContentColor(status)
    val phase by rememberOceanLoop(PULSE_CYCLE_MS, running = status == ChipStatus.ACTIVE)
    val shape = MaterialTheme.shapes.large

    Row(
        modifier = modifier
            .heightIn(min = CHIP_MIN_HEIGHT)
            .clip(shape)
            .background(content.copy(alpha = FILL_ALPHA))
            .border(width = 1.dp, color = content.copy(alpha = BORDER_ALPHA), shape = shape)
            .graphicsLayer {
                if (status == ChipStatus.ACTIVE) alpha = pulseAlpha(phase)
            }
            .padding(horizontal = CHIP_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        StageIcon(status, content)
        Spacer(modifier = Modifier.width(CHIP_GAP))
        Text(text = label, color = content, style = MaterialTheme.typography.labelLarge)
    }
}

/** Triangle wave between [PULSE_MIN_ALPHA] and 1 over one cycle of [phase]. */
private fun pulseAlpha(phase: Float): Float {
    val distanceToPeak = abs(phase * 2f - 1f)
    return 1f - (1f - PULSE_MIN_ALPHA) * distanceToPeak
}

@Composable
private fun StageIcon(status: ChipStatus, color: Color) {
    when {
        status == ChipStatus.DONE -> Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(ICON_SIZE),
        )
        status == ChipStatus.ACTIVE && !LocalReducedMotion.current -> Box(
            modifier = Modifier.size(ICON_SIZE),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(SPINNER_SIZE),
                color = color,
                strokeWidth = SPINNER_WIDTH,
            )
        }
        else -> Box(
            modifier = Modifier
                .size(PENDING_DOT)
                .clip(CircleShape)
                .background(color),
        )
    }
}
