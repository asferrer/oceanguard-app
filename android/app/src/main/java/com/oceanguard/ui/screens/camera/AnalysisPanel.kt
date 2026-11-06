package com.oceanguard.ai.ui.screens.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.inference.AnalysisResult
import com.oceanguard.ai.ui.components.analysis.rememberCountBump
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.theme.SonarTheme
import com.oceanguard.ai.ui.theme.healthScoreColor

/** Analysing a photo runs entirely on the device; sharing photos is a separate opt-in. */
private const val PHOTOS_SENT_TO_INTERNET = 0

private val PANEL_PADDING_X = 20.dp
private val PANEL_PADDING_Y = 12.dp
private val PANEL_GAP = 10.dp
private val STAT_PADDING = 12.dp

/**
 * Counters of the analysis and, when it ends, the two ways out. It stays until the user chooses:
 * nothing here runs on a timer.
 *
 * @param found Detections shown so far, in step with the reveal.
 * @param result Null until the analysis finishes.
 * @param contributing The user chose to share analysed photos, so "0 sent" would be misleading.
 * @param onViewDetails Null until the session has been saved.
 */
@Composable
internal fun AnalysisPanel(
    found: Int,
    result: AnalysisResult?,
    contributing: Boolean,
    onViewDetails: (() -> Unit)?,
    onNewScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = PANEL_PADDING_X, vertical = PANEL_PADDING_Y),
        verticalArrangement = Arrangement.spacedBy(PANEL_GAP),
    ) {
        AnalysisCounters(found, result, contributing)
        if (contributing) {
            Text(
                text = stringResource(R.string.sonar_analysis_contributing),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result != null) {
            AnalysisActions(onViewDetails, onNewScan)
        }
    }
}

@Composable
private fun AnalysisCounters(found: Int, result: AnalysisResult?, contributing: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(PANEL_GAP),
    ) {
        val cell = Modifier
            .weight(1f)
            .fillMaxHeight()
        if (result != null && result.totalDebrisCount == 0) {
            MessagePanel(stringResource(R.string.camera_result_no_debris), cell)
        } else {
            FoundPanel(found, cell)
        }
        if (result != null && result.totalDebrisCount > 0) {
            StatPanel(
                value = "${result.healthScore}%",
                label = stringResource(R.string.camera_result_summary_health),
                valueColor = healthScoreColor(result.healthScore),
                modifier = cell,
            )
        }
        if (!contributing) {
            StatPanel(
                value = PHOTOS_SENT_TO_INTERNET.toString(),
                label = stringResource(R.string.sonar_analysis_photos_sent),
                valueColor = MaterialTheme.colorScheme.onSurface,
                modifier = cell,
            )
        }
    }
}

/** Goes up with each echo, jumping a little, and TalkBack follows it. */
@Composable
private fun FoundPanel(found: Int, modifier: Modifier) {
    val bump by rememberCountBump(found)
    StatPanel(
        value = found.toString(),
        label = stringResource(R.string.camera_result_summary_debris),
        valueColor = if (found > 0) SonarTheme.colors.echo else MaterialTheme.colorScheme.onSurface,
        valueScale = { bump },
        modifier = modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
        },
    )
}

@Composable
private fun StatPanel(
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    valueScale: () -> Float = { 1f },
) {
    PanelSurface(modifier) {
        Text(
            text = value,
            style = SonarTheme.type.dataLarge,
            color = valueColor,
            modifier = Modifier.graphicsLayer {
                scaleX = valueScale()
                scaleY = valueScale()
            },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessagePanel(text: String, modifier: Modifier = Modifier) {
    PanelSurface(modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PanelSurface(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(STAT_PADDING), content = content)
    }
}

@Composable
private fun AnalysisActions(onViewDetails: (() -> Unit)?, onNewScan: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PANEL_GAP),
    ) {
        SonarButton(
            text = stringResource(R.string.camera_result_btn_new_scan),
            onClick = onNewScan,
            style = SonarButtonStyle.Secondary,
            icon = Icons.Filled.CameraAlt,
            modifier = Modifier.weight(1f),
        )
        if (onViewDetails != null) {
            SonarButton(
                text = stringResource(R.string.camera_result_btn_view_details),
                onClick = onViewDetails,
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
