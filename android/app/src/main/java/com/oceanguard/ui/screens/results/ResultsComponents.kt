package com.oceanguard.ai.ui.screens.results

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.EnvironmentalImpact
import com.oceanguard.ai.data.HealthScoreFactors
import com.oceanguard.ai.ui.components.DexSpriteImage
import com.oceanguard.ai.ui.components.HealthScoreGauge
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.theme.SonarTheme
import com.oceanguard.ai.ui.components.colorForClassOrMaterial
import com.oceanguard.ai.ui.theme.materialColor

/** Short figure with its caption: the same tile in a single result, a saved photo and a batch. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    SonarCard(modifier = modifier) {
        Text(
            text = value,
            style = SonarTheme.type.data,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
    }
}

/** Replaces the health gauge when a photo has nothing detected: there is nothing to rate. */
@Composable
fun NoDetectionsCard(modifier: Modifier = Modifier) {
    SonarCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.SearchOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
            Column {
                Text(
                    text = stringResource(R.string.results_no_detections_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.results_no_detections_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Health gauge in a card. The gauge keeps its own information button and score breakdown, so
 * [factors] must be passed for them to appear.
 */
@Composable
fun HealthCard(score: Int, factors: HealthScoreFactors, modifier: Modifier = Modifier) {
    SonarCard(modifier = modifier) {
        HealthScoreGauge(
            score = score,
            size = 140.dp,
            factors = factors,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Model and time of the analysis, or an honest "not recorded" for rows saved before they were kept. */
@Composable
fun AnalysisInfoRow(modelName: String?, processingTimeMs: Long, modifier: Modifier = Modifier) {
    val model = modelName?.let { stringResource(R.string.results_model_line, it) }
        ?: stringResource(R.string.results_model_unrecorded)
    val seconds = secondsLabel(processingTimeMs)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = model,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (seconds != null) {
            Text(
                text = stringResource(R.string.results_time_line, seconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun secondsLabel(ms: Long): String? =
    if (ms <= 0L) null else String.format(java.util.Locale.US, "%.1f", ms / 1000f)

@Composable
fun MaterialChipsRow(breakdown: Map<DebrisMaterial, Int>, modifier: Modifier = Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(breakdown.entries.toList()) { (material, count) ->
            MaterialChip(material, count)
        }
    }
}

@Composable
private fun MaterialChip(material: DebrisMaterial, count: Int) {
    val color = materialColor(material)
    Surface(shape = RoundedCornerShape(20.dp), color = color.copy(alpha = 0.18f)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Text(
                text = "${material.name.replace("_", " ")} ($count)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * One detected object. The percentage appears only when [modelName] has a real confidence;
 * Gemma's does not, so its detections show no percentage.
 */
@Composable
fun FindingCard(debris: Debris, modelName: String?, modifier: Modifier = Modifier) {
    val impact = EnvironmentalImpact.getImpact(debris.type)
    val confidence = confidenceLabel(debris.confidence, modelName)
    SonarCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(colorForClassOrMaterial(debris.type.name, debris.material).copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DexSpriteImage(debrisType = debris.type.canonical(), size = 28.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = debris.type.name.replace("_", " "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = debris.material.name.replace("_", " "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (confidence != null) {
                Text(text = confidence, style = SonarTheme.type.data, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            text = "${stringResource(impact.degradationTimeRes)} · ${stringResource(impact.primaryRiskRes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Warning for a photo where one object type makes up most of the detections. */
@Composable
fun RepeatedObjectsNotice(repeated: RepeatedObjects, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = stringResource(
                R.string.results_repeated_warning,
                repeated.count,
                repeated.typeName.replace("_", " "),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}
