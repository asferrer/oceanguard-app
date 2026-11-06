package com.oceanguard.ai.ui.screens.results

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.motionSpec
import com.oceanguard.ai.ui.theme.SonarTheme
import com.oceanguard.ai.ui.theme.healthScoreColor

private val THUMB_SHAPE = RoundedCornerShape(14.dp)

/** Everything the grid needs; kept in one value so the composables stay short. */
internal data class BatchGridState(
    val items: List<BatchItem>,
    val currentIndex: Int,
    val stats: BatchStats?,
)

@Composable
internal fun BatchGridContent(
    state: BatchGridState,
    boundsMap: MutableMap<String, Rect>,
    onNewBatch: () -> Unit,
    onViewHistory: () -> Unit,
    onViewMap: () -> Unit,
    onItemClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val completed = state.items.count { it.state is BatchItemState.Done || it.state is BatchItemState.Failed }
    val fraction = if (state.items.isEmpty()) 0f else completed.toFloat() / state.items.size
    val progress by animateFloatAsState(fraction, motionSpec(OceanMotion.standard()), label = "batch_progress")

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            BatchProgressHeader(
                currentIndex = state.currentIndex,
                total = state.items.size,
                completed = completed,
                finished = state.stats != null,
                progress = progress,
                modifier = Modifier.spotlightTarget("batch_grid", boundsMap),
            )
        }
        state.stats?.let { stats ->
            item(span = { GridItemSpan(2) }) {
                BatchSummaryCard(stats, Modifier.spotlightTarget("batch_summary", boundsMap))
            }
        }
        items(state.items, key = { it.uri.toString() }) { BatchThumbnailCard(it, onItemClick) }
        if (state.stats != null) {
            item(span = { GridItemSpan(2) }) { BatchActions(onNewBatch, onViewHistory, onViewMap) }
        }
        item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun BatchProgressHeader(
    currentIndex: Int,
    total: Int,
    completed: Int,
    finished: Boolean,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val container = if (finished) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
    val content = if (finished) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = container) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ProgressStatusLine(currentIndex, total, completed, finished, content)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (finished) MaterialTheme.colorScheme.secondary else content,
                trackColor = content.copy(alpha = 0.2f),
            )
        }
    }
}

@Composable
private fun ProgressStatusLine(currentIndex: Int, total: Int, completed: Int, finished: Boolean, content: Color) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (finished) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.secondary)
            } else {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = content)
            }
            val title = if (finished) {
                stringResource(R.string.batch_status_complete)
            } else {
                stringResource(R.string.batch_status_analyzing, currentIndex + 1, total)
            }
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = content)
        }
        val count = if (finished) {
            stringResource(R.string.batch_images_count, total)
        } else {
            stringResource(R.string.batch_status_done_count, completed)
        }
        Text(text = count, style = MaterialTheme.typography.bodySmall, color = content)
    }
}

/** One photo: dimmed while pending, spinner while analysing, health or "no detections" when done. */
@Composable
private fun BatchThumbnailCard(item: BatchItem, onItemClick: (Long) -> Unit) {
    val state = item.state
    val done = state as? BatchItemState.Done
    val health = done?.let { displayedHealth(it.result.healthScore, it.result.totalDebrisCount) }
    val borderColor = when {
        state is BatchItemState.Failed -> MaterialTheme.colorScheme.error
        health != null -> healthScoreColor(health)
        else -> MaterialTheme.colorScheme.outline
    }
    val open = done?.sessionId?.takeIf { it > 0 }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(THUMB_SHAPE)
            .border(BorderStroke(if (state is BatchItemState.Done || state is BatchItemState.Failed) 2.dp else 1.dp, borderColor), THUMB_SHAPE)
            .then(if (open != null) Modifier.clickable(role = Role.Button) { onItemClick(open) } else Modifier),
    ) {
        AsyncImage(
            model = done?.annotatedUri ?: item.uri,
            contentDescription = stringResource(R.string.cd_batch_image_thumbnail),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        when (state) {
            is BatchItemState.Pending -> Scrim(0.6f)
            is BatchItemState.Analyzing -> AnalyzingOverlay()
            is BatchItemState.Done -> DoneOverlay(state, health)
            is BatchItemState.Failed -> FailedOverlay(state.message)
        }
    }
}

@Composable
private fun Scrim(alpha: Float) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = alpha)))
}

@Composable
private fun AnalyzingOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Scrim(0.55f)
        CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun DoneOverlay(done: BatchItemState.Done, health: Int?) {
    Box(Modifier.fillMaxSize()) {
        if (health != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), CircleShape)
                    .border(2.dp, healthScoreColor(health), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(health.toString(), style = SonarTheme.type.dataSmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        val count = done.result.totalDebrisCount
        val label = if (count > 0) {
            stringResource(R.string.batch_debris_count, count)
        } else {
            stringResource(R.string.results_no_detections_title)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .padding(horizontal = 6.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun FailedOverlay(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Error,
            stringResource(R.string.cd_analysis_failed),
            Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun BatchActions(onNewBatch: () -> Unit, onViewHistory: () -> Unit, onViewMap: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SonarButton(
            text = stringResource(R.string.batch_btn_new_batch),
            onClick = onNewBatch,
            icon = Icons.Filled.Refresh,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SonarButton(
                text = stringResource(R.string.batch_btn_view_history),
                onClick = onViewHistory,
                style = SonarButtonStyle.Secondary,
                icon = Icons.Filled.History,
                modifier = Modifier.weight(1f),
            )
            SonarButton(
                text = stringResource(R.string.batch_btn_view_map),
                onClick = onViewMap,
                style = SonarButtonStyle.Secondary,
                icon = Icons.Filled.Map,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
