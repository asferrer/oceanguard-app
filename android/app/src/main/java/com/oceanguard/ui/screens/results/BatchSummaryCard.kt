package com.oceanguard.ai.ui.screens.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.sonar.SonarCard

/**
 * What the batch found, who found it and how long it took. An average health is shown only
 * when at least one photo has detections; photos with none are counted apart, not averaged in.
 */
@Composable
internal fun BatchSummaryCard(stats: BatchStats, modifier: Modifier = Modifier) {
    SonarCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.batch_summary_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SummaryTiles(stats)
            SummaryDetails(stats)
            stats.repeated?.let { RepeatedObjectsNotice(it) }
        }
    }
}

@Composable
private fun SummaryTiles(stats: BatchStats) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(
            label = stringResource(R.string.batch_summary_total_debris),
            value = stats.totalObjects.toString(),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(R.string.batch_summary_avg_health),
            value = stats.averageHealth?.let { "$it / 100" } ?: "—",
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(R.string.batch_summary_total_time),
            value = formatDuration(stats.durationMs),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryDetails(stats: BatchStats) {
    val analysed = stats.photos - stats.failed
    val perPhoto = secondsPerPhoto(stats.durationMs, analysed)
    val body = MaterialTheme.typography.bodyMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stats.modelLabel?.let { stringResource(R.string.results_model_line, it) }
                ?: stringResource(R.string.results_model_unrecorded),
            style = body,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (perPhoto != null) {
            Text(stringResource(R.string.results_batch_per_photo, perPhoto), style = body, color = muted)
        }
        Text(
            text = stringResource(R.string.results_batch_counts, stats.withDetections, stats.withoutDetections),
            style = body,
            color = muted,
        )
        if (stats.failed > 0) {
            Text(
                text = stringResource(R.string.results_batch_failed, stats.failed),
                style = body,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
