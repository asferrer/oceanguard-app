package com.oceanguard.ai.ui.screens.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.sonar.SonarErrorState
import com.oceanguard.ai.ui.theme.HealthCritical
import com.oceanguard.ai.ui.theme.HealthExcellent
import com.oceanguard.ai.ui.theme.HealthFair
import com.oceanguard.ai.ui.theme.HealthGood
import com.oceanguard.ai.ui.theme.HealthPoor
import com.oceanguard.ai.ui.theme.SonarTheme
import com.oceanguard.ai.ui.theme.healthScoreColor

private val OVERLAY_SHAPE = RoundedCornerShape(12.dp)
private const val OVERLAY_ALPHA = 0.92f

/** Aggregate health of every zone in view, at the top-start of the map. */
@Composable
fun ZoneHealthSummary(
    clusterCount: Int,
    avgScore: Int,
    modifier: Modifier = Modifier,
) {
    val scoreColor = healthScoreColor(avgScore)
    Surface(
        modifier = modifier,
        shape = OVERLAY_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = OVERLAY_ALPHA),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(10.dp).background(scoreColor, CircleShape))
            Column {
                Text(
                    text = if (clusterCount != 1) stringResource(R.string.map_zone_count_plural, clusterCount)
                    else stringResource(R.string.map_zone_count_singular, clusterCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.map_zone_avg_health, avgScore),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Collapsible "Health Score" legend: what each colour of the map means. */
@Composable
fun MapLegend(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        shape = OVERLAY_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = OVERLAY_ALPHA),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            LegendHeader(expanded) { expanded = !expanded }
            if (expanded) {
                LegendRow(HealthExcellent, stringResource(R.string.map_legend_excellent))
                LegendRow(HealthGood, stringResource(R.string.map_legend_good))
                LegendRow(HealthFair, stringResource(R.string.map_legend_moderate))
                LegendRow(HealthPoor, stringResource(R.string.map_legend_poor))
                LegendRow(HealthCritical, stringResource(R.string.map_legend_critical))
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun LegendHeader(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
            contentDescription = stringResource(
                if (expanded) R.string.map_legend_cd_collapse else R.string.map_legend_cd_expand,
            ),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.map_legend_title),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LegendRow(color: Color, label: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Tells the user the base map cannot load without internet while the detections stay available.
 * Announced politely, without moving the focus.
 */
@Composable
fun MapOfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = OVERLAY_SHAPE,
        color = SonarTheme.colors.alertContainer,
        contentColor = SonarTheme.colors.onAlertContainer,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.CloudOff, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.map_offline_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.map_offline_message), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Shown over the blank map when its style failed to load; [onRetry] tries again. */
@Composable
fun MapLoadFailed(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(24.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        SonarErrorState(
            title = stringResource(R.string.map_load_failed_title),
            message = stringResource(R.string.map_load_failed_message),
            onRetry = onRetry,
        )
    }
}
