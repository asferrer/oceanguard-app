package com.oceanguard.ai.ui.components.sonar

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.motionSpec
import com.oceanguard.ai.ui.motion.rememberOceanLoop
import com.oceanguard.ai.ui.theme.SonarTheme

/** What a long action is doing. Busy without a total (preparing, installing) is [Indeterminate]. */
sealed interface ButtonProgress {
    data object Idle : ButtonProgress
    data object Indeterminate : ButtonProgress
    /** @param fraction 0..1 */
    data class Determinate(val fraction: Float) : ButtonProgress
}

private val PROGRESS_MIN_HEIGHT = 52.dp
private val PROGRESS_LABEL_PADDING = 16.dp
private const val INDETERMINATE_LOOP_MS = 1_400
private const val INDETERMINATE_WIDTH = 0.3f

/** While idle a [SonarButton]; afterwards the button itself is the progress bar. */
@Composable
fun SonarProgressButton(
    text: String,
    progress: ButtonProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCancel: (() -> Unit)? = null,
) {
    if (progress == ButtonProgress.Idle) {
        SonarButton(text, onClick, modifier.fillMaxWidth(), enabled = enabled)
        return
    }
    val fraction = (progress as? ButtonProgress.Determinate)?.fraction
    val percentLabel = fraction?.let { stringResource(R.string.sonar_percent, (it * 100).toInt()) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PROGRESS_MIN_HEIGHT)
            .progressSemantics(fraction, percentLabel),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(Modifier.progressFill(progress), contentAlignment = Alignment.CenterStart) {
            ProgressLabel(text, percentLabel, onCancel)
        }
    }
}

/** TalkBack reads the progress and announces its changes without moving the focus. */
private fun Modifier.progressSemantics(fraction: Float?, percentLabel: String?): Modifier =
    semantics(mergeDescendants = true) {
        liveRegion = LiveRegionMode.Polite
        if (percentLabel != null) stateDescription = percentLabel
        progressBarRangeInfo = fraction?.let { ProgressBarRangeInfo(it, 0f..1f) }
            ?: ProgressBarRangeInfo.Indeterminate
    }

@Composable
private fun ProgressLabel(text: String, percentLabel: String?, onCancel: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = PROGRESS_LABEL_PADDING),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (percentLabel != null) {
            Text(
                text = percentLabel,
                style = SonarTheme.type.dataSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = PROGRESS_LABEL_PADDING),
            )
        }
        if (onCancel != null) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, stringResource(R.string.common_cancel))
            }
        }
    }
}

@Composable
private fun Modifier.progressFill(progress: ButtonProgress): Modifier {
    val fill = SonarTheme.colors.traceContainer
    return when (progress) {
        is ButtonProgress.Determinate -> determinateFill(progress.fraction, fill)
        else -> indeterminateFill(fill)
    }
}

@Composable
private fun Modifier.determinateFill(fraction: Float, fill: Color): Modifier {
    val shown by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = motionSpec(OceanMotion.standard()),
        label = "buttonProgress",
    )
    return drawBehind { drawRect(fill, size = Size(size.width * shown, size.height)) }
}

/** A band that travels across the button; a static band under "Remove animations". */
@Composable
private fun Modifier.indeterminateFill(fill: Color): Modifier {
    val reduced = LocalReducedMotion.current
    val phase by rememberOceanLoop(INDETERMINATE_LOOP_MS)
    return drawBehind {
        val band = size.width * INDETERMINATE_WIDTH
        val x = if (reduced) 0f else phase * (size.width + band) - band
        drawRect(fill, topLeft = Offset(x, 0f), size = Size(band, size.height))
    }
}
