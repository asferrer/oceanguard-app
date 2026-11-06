package com.oceanguard.ai.ui.screens.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.ReportGenerationState
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.screens.results.ReportStage
import com.oceanguard.ai.ui.screens.results.ReportStepState
import com.oceanguard.ai.ui.screens.results.StepStatus
import com.oceanguard.ai.ui.screens.results.reportSteps
import com.oceanguard.ai.ui.theme.SonarTheme

/**
 * Maps the snake_case tool name reported by the model's function-call parser to a translated
 * label. Keep in sync with the tool methods in `OceanGuardTools` and the required tools of
 * `ToolReportGenerator`.
 */
private val TOOL_LABELS: Map<String, Int> = mapOf(
    "get_per_session_details" to R.string.tool_per_session_details,
    "get_debris_summary" to R.string.tool_debris_summary,
    "get_material_breakdown" to R.string.tool_material_breakdown,
    "get_type_breakdown" to R.string.tool_type_breakdown,
    "get_risk_assessment" to R.string.tool_risk_assessment,
    "get_ecological_impacts" to R.string.tool_ecological_impacts,
    "get_survey_statistics" to R.string.tool_survey_statistics,
    "get_temporal_trend" to R.string.tool_temporal_trend,
)

/** The report's real steps, with the one running now. Only the writing step shows tokens. */
@Composable
internal fun ReportProgressCard(
    app: OceanGuardApp,
    stage: ReportStage?,
    toolProgress: Pair<Int, Int>?,
    toolName: String?,
    modifier: Modifier = Modifier,
) {
    val steps = remember(stage, toolProgress) { reportSteps(stage, toolProgress) }
    SonarCard(modifier) {
        Text(
            text = stringResource(R.string.results_report_steps_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (step in steps) {
                StepRow(step, toolName)
                if (step.stage == ReportStage.WRITING && step.status == StepStatus.ACTIVE) WritingDetail(app)
            }
        }
    }
}

@Composable
private fun StepRow(step: ReportStepState, toolName: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (step.status) {
                StepStatus.DONE -> Icon(Icons.Filled.CheckCircle, null, tint = SonarTheme.colors.trace)
                StepStatus.ACTIVE -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                StepStatus.PENDING -> Icon(
                    Icons.Filled.RadioButtonUnchecked,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column {
            val label = stringResource(step.stage.labelRes())
            Text(
                text = step.progress?.let { "$label ($it)" } ?: label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (step.status == StepStatus.PENDING) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (step.stage == ReportStage.DATA && step.status == StepStatus.ACTIVE) {
                toolLabel(toolName)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun ReportStage.labelRes(): Int = when (this) {
    ReportStage.MODEL -> R.string.results_step_model
    ReportStage.DATA -> R.string.results_step_data
    ReportStage.WRITING -> R.string.results_step_writing
}

@Composable
private fun toolLabel(snake: String?): String? {
    if (snake == null) return null
    val res = TOOL_LABELS[snake]
    if (res != null) return stringResource(res)
    // A tool added later still reads well: "get_foo_bar" becomes "Foo Bar".
    return snake.removePrefix("get_").split('_').filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}

/**
 * Section being written and token progress. It collects the state itself, so the streaming text
 * redraws only this block and not the whole screen.
 */
@Composable
private fun WritingDetail(app: OceanGuardApp) {
    val state by app.reportGenerationState.collectAsStateWithLifecycle()
    val streaming = state as? ReportGenerationState.StreamingText ?: return
    val hasMetrics = streaming.tokenCount > 0 && streaming.maxTokens > 0
    val fraction = if (hasMetrics) (streaming.tokenCount.toFloat() / streaming.maxTokens).coerceIn(0f, 1f) else 0f
    Column(Modifier.padding(start = 36.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        currentHeading(streaming.partialText).takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hasMetrics) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            MetricsLine(streaming, fraction)
        }
    }
}

@Composable
private fun MetricsLine(streaming: ReportGenerationState.StreamingText, fraction: Float) {
    val eta = etaSeconds(streaming)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val style = SonarTheme.type.dataSmall
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Text("%.1f tok/s".format(streaming.tokensPerSec), style = style, color = color)
        Text("${(fraction * 100).toInt()}%", style = style, color = color)
        if (eta != null) Text(stringResource(R.string.report_eta, formatEta(eta)), style = style, color = color)
    }
}

/** Heading of the section being written: the last `## ` line of the text so far. */
internal fun currentHeading(text: String): String {
    val start = text.lastIndexOf("\n## ")
    if (start < 0) return ""
    val end = text.indexOf('\n', start + 1).takeIf { it > 0 } ?: text.length
    return text.substring(start + 1, end).removePrefix("## ").trim()
}

internal fun etaSeconds(streaming: ReportGenerationState.StreamingText): Int? =
    if (streaming.tokensPerSec > 0.5f) {
        ((streaming.maxTokens - streaming.tokenCount) / streaming.tokensPerSec).toInt().coerceAtLeast(0)
    } else {
        null
    }

internal fun formatEta(seconds: Int): String =
    if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
