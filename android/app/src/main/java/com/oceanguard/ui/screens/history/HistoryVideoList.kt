package com.oceanguard.ai.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.VideoAnalysis
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.components.sonar.SonarEmptyState
import com.oceanguard.ai.ui.theme.SonarTheme
import java.text.DateFormat

/** Video analyses, newest first as the database returns them. */
@Composable
fun HistoryVideoList(
    videoAnalyses: List<VideoAnalysis>,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (videoAnalyses.isEmpty()) {
        SonarEmptyState(
            icon = Icons.Filled.Videocam,
            title = stringResource(R.string.history_empty_title),
            message = stringResource(R.string.history_empty_message),
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(videoAnalyses, key = { it.id }) { analysis ->
            VideoCard(analysis, dateFormat.format(analysis.timestamp)) { onOpen(analysis.id) }
        }
    }
}

@Composable
private fun VideoCard(analysis: VideoAnalysis, date: String, onClick: () -> Unit) {
    SonarCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VideoThumbnail(analysis.thumbnailUri)
            Column(Modifier.weight(1f)) {
                Text(date, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = pluralStringResource(
                        R.plurals.history_video_unique_debris, analysis.uniqueDebrisCount, analysis.uniqueDebrisCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.history_video_summary, analysis.durationMs / 1000, analysis.processedFrameCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            VideoHealth(analysis)
        }
    }
}

@Composable
private fun VideoThumbnail(uri: String?) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (uri != null) {
            AsyncImage(model = uri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = SonarTheme.colors.ink,
        )
    }
}

@Composable
private fun VideoHealth(analysis: VideoAnalysis) {
    val color = riskColor(analysis.getRiskLevel())
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.18f)) {
        Text(
            text = analysis.healthScore.toString(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = SonarTheme.type.dataSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
