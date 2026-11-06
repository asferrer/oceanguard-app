package com.oceanguard.ai.ui.screens.home

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.motionSpec
import com.oceanguard.ai.ui.theme.SonarTheme
import com.oceanguard.ai.ui.theme.healthScoreColor

private const val PLACEHOLDER = "--"

/** Analyses, debris found and average health. Figures count up and jump under "Remove animations". */
@Composable
fun HomeStats(
    totalSessions: Int,
    totalDebris: Int,
    avgHealthScore: Float?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    SonarCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Stat(stringResource(R.string.home_stat_sessions), totalSessions, scheme.primary)
            StatDivider()
            Stat(stringResource(R.string.home_stat_debris), totalDebris, SonarTheme.colors.echo)
            StatDivider()
            Stat(
                label = stringResource(R.string.home_stat_avg_health),
                value = avgHealthScore?.toInt(),
                color = avgHealthScore?.let { healthScoreColor(it.toInt()) } ?: scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatDivider() {
    VerticalDivider(
        modifier = Modifier.height(48.dp),
        color = MaterialTheme.colorScheme.outline,
    )
}

/** One figure with its label. A null [value] shows a dash until there is something to average. */
@Composable
private fun Stat(label: String, value: Int?, color: Color) {
    val shown by animateIntAsState(
        targetValue = value ?: 0,
        animationSpec = motionSpec(OceanMotion.signature()),
        label = "statCount",
    )
    val text = if (value == null) PLACEHOLDER else shown.toString()
    val description = "$label: ${value ?: PLACEHOLDER}"
    Column(
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = text, style = SonarTheme.type.dataLarge, color = color)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
