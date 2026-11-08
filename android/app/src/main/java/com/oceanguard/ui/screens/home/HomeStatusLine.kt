package com.oceanguard.ai.ui.screens.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.inference.VlmDownloadState
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.rememberOceanLoop
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlinx.coroutines.flow.StateFlow

private val DOT_SIZE = 8.dp
private val BAR_HEIGHT = 4.dp
private const val INDETERMINATE_PERIOD_MS = 1_400
private const val SEGMENT_SHARE = 0.4f
private const val STILL_ALPHA = 0.35f

/**
 * Status line: which model analyses photos and whether it is ready, loading or still to download,
 * plus whether the report model is installed. It names the detector that really runs.
 */
@Composable
fun HomeStatusLine(
    app: OceanGuardApp,
    model: HomeModelUi,
    onDownloadReports: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SonarCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (model.kind == DetectorStatusKind.DOWNLOADING) {
                DownloadProgress(
                    modelLabel = stringResource(model.mode.labelRes),
                    stateFlow = app.vlmModelManager.downloadState,
                    onCancel = { app.vlmModelManager.cancelDownload() },
                )
            } else {
                DetectorRow(model)
            }
            ReportsRow(app, model, onDownloadReports)
        }
    }
}

@Composable
private fun DetectorRow(model: HomeModelUi) {
    val name = model.modelName ?: stringResource(model.mode.labelRes)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(dotColor(model.kind))
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = statusText(model),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (model.kind == DetectorStatusKind.LOADING || model.kind == DetectorStatusKind.WARMING_UP) {
            IndeterminateBar(Modifier.fillMaxWidth().height(BAR_HEIGHT))
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(DOT_SIZE).background(color, CircleShape))
}

@Composable
private fun dotColor(kind: DetectorStatusKind): Color {
    val colors = SonarTheme.colors
    return when (kind) {
        DetectorStatusKind.READY -> colors.trace
        DetectorStatusKind.FAILED -> colors.alert
        DetectorStatusKind.LOADING,
        DetectorStatusKind.WARMING_UP,
        DetectorStatusKind.DOWNLOADING,
        DetectorStatusKind.NEEDS_DOWNLOAD -> colors.echo
        DetectorStatusKind.STANDBY,
        DetectorStatusKind.NOT_LOADED -> colors.ink2
    }
}

@Composable
private fun statusText(model: HomeModelUi): String = when (model.kind) {
    DetectorStatusKind.READY -> stringResource(R.string.home_model_status_ready)
    DetectorStatusKind.LOADING -> stringResource(R.string.home_model_loading)
    DetectorStatusKind.WARMING_UP -> stringResource(R.string.home_model_warming_up)
    DetectorStatusKind.DOWNLOADING -> stringResource(R.string.home_model_download_downloading)
    DetectorStatusKind.NEEDS_DOWNLOAD -> stringResource(
        R.string.home_status_needs_download,
        HomeLogic.formatGb(model.mode.approxDownloadGb ?: 0f),
    )
    DetectorStatusKind.FAILED -> stringResource(R.string.home_model_status_failed)
    DetectorStatusKind.STANDBY -> stringResource(R.string.home_model_status_on_demand)
    DetectorStatusKind.NOT_LOADED -> stringResource(R.string.home_status_not_loaded)
}

@Composable
private fun ReportsRow(app: OceanGuardApp, model: HomeModelUi, onDownload: () -> Unit) {
    val text = if (model.reportModelMissing) {
        stringResource(R.string.home_status_reports_missing, reportTier(app).sizeLabel)
    } else {
        stringResource(R.string.home_status_reports_ready)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (HomeLogic.offerReportDownload(model.reportModelMissing, model.kind)) {
            TextButton(onClick = onDownload) { Text(stringResource(R.string.home_status_reports_download)) }
        }
    }
}

/**
 * Inline download progress. It collects the state flow itself, so streaming bytes recompose only
 * this subtree and not the whole of Home.
 */
@Composable
private fun DownloadProgress(
    modelLabel: String,
    stateFlow: StateFlow<VlmDownloadState>,
    onCancel: () -> Unit,
) {
    val state by stateFlow.collectAsStateWithLifecycle()
    val snapshot = state
    val label = when (snapshot) {
        is VlmDownloadState.Preparing -> stringResource(R.string.home_model_download_preparing)
        is VlmDownloadState.Installing -> stringResource(R.string.home_model_download_installing)
        else -> stringResource(R.string.home_model_download_downloading)
    }
    val progress = when (snapshot) {
        is VlmDownloadState.Downloading -> snapshot.progress
        is VlmDownloadState.Installing -> 1f
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(SonarTheme.colors.echo)
            Text(modelLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (snapshot is VlmDownloadState.Downloading) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            }
        }
        DownloadBar(progress)
        if (snapshot is VlmDownloadState.Downloading) {
            Text(
                text = HomeLogic.formatBytesLine(snapshot.downloadedBytes, snapshot.totalBytes, snapshot.progress),
                style = SonarTheme.type.dataSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadBar(progress: Float?) {
    val modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT)
    val track = MaterialTheme.colorScheme.surfaceVariant
    if (progress != null) {
        LinearProgressIndicator(progress = { progress }, modifier = modifier, trackColor = track)
    } else {
        IndeterminateBar(modifier)
    }
}

/**
 * Bar for work of unknown length. It slides with the shared loop, which pauses off screen and in the
 * background, instead of the library one that never stops. Under "Remove animations" it is a still bar.
 */
@Composable
private fun IndeterminateBar(modifier: Modifier = Modifier) {
    val phase by rememberOceanLoop(INDETERMINATE_PERIOD_MS)
    val reduced = LocalReducedMotion.current
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    Canvas(modifier.clip(CircleShape)) {
        drawRect(track)
        val segment = size.width * SEGMENT_SHARE
        val start = if (reduced) 0f else phase * (size.width + segment) - segment
        drawRect(fill, Offset(start, 0f), Size(if (reduced) size.width else segment, size.height), alpha = if (reduced) STILL_ALPHA else 1f)
    }
}
