package com.oceanguard.ai.ui.screens.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.components.sonar.SonarSection
import com.oceanguard.ai.ui.theme.SonarTheme

private val RINGS_SIZE = 112.dp
private val BLOCK_GAP = 16.dp
private val LINE_GAP = 4.dp
private val TASK_MIN_HEIGHT = 40.dp

/**
 * What is really loaded right now, not what is set: the effective mode with its depth rings, the
 * state of its engine, and the model behind each task.
 */
@Composable
fun InUseSection(
    saved: AnalysisMode,
    effective: AnalysisMode,
    rings: List<Ring>,
    load: LoadState,
    tasks: List<TaskUse>,
    modifier: Modifier = Modifier,
) {
    SonarSection(stringResource(R.string.models_in_use_title), modifier) {
        SonarCard {
            Column(verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
                Row(horizontalArrangement = Arrangement.spacedBy(BLOCK_GAP), verticalAlignment = Alignment.CenterVertically) {
                    DepthRings(rings, Modifier.size(RINGS_SIZE))
                    ActiveSummary(effective, load, Modifier.weight(1f))
                }
                if (saved != effective) FallbackNote(saved, effective)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                tasks.forEach { TaskRow(it) }
            }
        }
    }
}

@Composable
private fun ActiveSummary(effective: AnalysisMode, load: LoadState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
        Text(
            text = stringResource(effective.labelRes),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = detectionModelName(effective),
            style = SonarTheme.type.dataSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val (text, color) = loadText(load)
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun loadText(load: LoadState): Pair<String, Color> = when (load) {
    LoadState.READY -> stringResource(R.string.models_state_ready) to MaterialTheme.colorScheme.primary
    LoadState.LOADING -> stringResource(R.string.models_state_loading) to MaterialTheme.colorScheme.onSurfaceVariant
    LoadState.ERROR -> stringResource(R.string.models_state_error) to MaterialTheme.colorScheme.error
    LoadState.ON_DEMAND -> stringResource(R.string.models_state_on_demand) to MaterialTheme.colorScheme.onSurfaceVariant
}

/** The chosen mode cannot run yet: say which one will, so the screen never claims more. */
@Composable
private fun FallbackNote(saved: AnalysisMode, effective: AnalysisMode) {
    Text(
        text = stringResource(
            R.string.models_fallback_note,
            stringResource(saved.labelRes),
            stringResource(effective.labelRes),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = SonarTheme.colors.echo,
    )
}

@Composable
private fun TaskRow(use: TaskUse) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = TASK_MIN_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(BLOCK_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(use.task.titleRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (use.modelName != null) {
            Text(
                text = use.modelName,
                style = SonarTheme.type.dataSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                text = stringResource(R.string.models_task_no_model),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
