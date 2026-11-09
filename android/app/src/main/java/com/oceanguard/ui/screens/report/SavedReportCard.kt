package com.oceanguard.ai.ui.screens.report

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

private const val HIGHLIGHT_HOLD_MS = 200L
private const val HIGHLIGHT_FADE_MS = 1_200

/** Strips markdown syntax to produce a clean plain-text preview snippet. */
internal fun stripMarkdownForPreview(text: String): String = text
    .replace(Regex("#{1,6}\\s+"), "")
    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    .replace(Regex("__(.+?)__"), "$1")
    .replace(Regex("\\*(.+?)\\*"), "$1")
    .replace(Regex("_(.+?)_"), "$1")
    .replace(Regex("`(.+?)`"), "$1")
    .replace(Regex("^>\\s*", RegexOption.MULTILINE), "")
    .replace(Regex("^[-*+]\\s+", RegexOption.MULTILINE), "")
    .replace(Regex("\\|.+"), "")
    .replace(Regex("\\n+"), " ")
    .trim()

/** Label for the audience a report was written for; unknown keys read as the scientific one. */
@Composable
internal fun audienceText(key: String): String = stringResource(
    when (key) {
        "ngo_manager" -> R.string.audience_ngo
        "citizen" -> R.string.audience_citizen
        else -> R.string.audience_scientific
    },
)

/** One saved report: kind, audience, place, date, the model that wrote it and a preview. */
@Composable
internal fun SavedReportCard(
    report: GeneratedReport,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onHighlightFinished: () -> Unit = {},
) {
    val alpha = remember { Animatable(0f) }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(highlight) {
        if (!highlight) return@LaunchedEffect
        alpha.snapTo(1f)
        delay(HIGHLIGHT_HOLD_MS)
        if (reduced) alpha.snapTo(0f) else alpha.animateTo(0f, tween(HIGHLIGHT_FADE_MS, easing = LinearEasing))
        onHighlightFinished()
    }
    val border = if (alpha.value > 0f) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = alpha.value), MaterialTheme.shapes.large)
    } else {
        Modifier
    }
    SonarCard(modifier = modifier.then(border), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = SonarTheme.colors.traceContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = SonarTheme.colors.onTraceContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            ReportSummary(report, Modifier.weight(1f))
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.report_card_delete_cd),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ReportSummary(report: GeneratedReport, modifier: Modifier) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    val whenWritten = dateFormat.format(report.timestamp)
    val meta = stringResource(
        if (report.sessionCount != 1) R.string.report_card_meta_plural else R.string.report_card_meta_singular,
        report.sessionCount,
        report.language.uppercase(),
        whenWritten,
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(if (report.usedAi) R.string.report_card_ai_label else R.string.report_card_template_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            report.audience?.let { AudienceBadge(audienceText(it)) }
            ReportTypeBadge(report)
        }
        report.locationName?.let { PlaceLine(it) }
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = report.modelName?.let { stringResource(R.string.results_report_written_by, it) }
                ?: stringResource(R.string.results_model_unrecorded),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val preview = remember(report.id) { stripMarkdownForPreview(report.text).take(140) + "…" }
        Text(
            text = preview,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun AudienceBadge(label: String) {
    Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PlaceLine(name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.LocationOn, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
