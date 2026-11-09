package com.oceanguard.ai.ui.screens.report

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.inference.ReportType
import com.oceanguard.ai.ui.components.MarkdownText
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.components.sonar.SonarSection
import com.oceanguard.ai.data.savedFigures
import com.oceanguard.ai.ui.screens.results.BatchStats
import com.oceanguard.ai.ui.screens.results.ReportExportFormat
import com.oceanguard.ai.ui.screens.results.StatTile
import com.oceanguard.ai.ui.screens.results.reportExportBlock
import com.oceanguard.ai.ui.screens.results.reportFigures
import com.oceanguard.ai.ui.theme.SonarTheme
import java.text.SimpleDateFormat
import java.util.Locale

private val THUMB_SIZE = 88.dp

/** Actions the open report hands back to its screen. */
internal data class ReportViewActions(
    val onClose: () -> Unit,
    val onExport: (ReportExportFormat) -> Unit,
    val onSessionClick: (Long) -> Unit,
    val onAddLocation: () -> Unit,
    val onObservationClick: (Long) -> Unit = {},
)

/** The species a species or combined report was written from, and the catalogue that names them. */
internal class ReportSpecies(val observations: List<SpeciesObservation>, val catalog: SpeciesCatalog)

/**
 * A report in full: figures, the photos it analysed (each opens its detail), the text and the
 * model that wrote it. Export offers PDF, Markdown, CSV and GeoJSON.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportFullScreenView(
    report: GeneratedReport,
    sessions: List<DetectionSession>,
    species: ReportSpecies,
    exporting: Boolean,
    actions: ReportViewActions,
) {
    var showExport by remember { mutableStateOf(false) }
    BackHandler(onBack = actions.onClose)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                ReportViewTopBar(report, exporting, actions.onClose) { showExport = true }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            ReportBody(report, sessions, species, actions, Modifier.padding(padding))
        }
        if (showExport) {
            val type = ReportType.fromKey(report.reportType)
            ReportExportSheet(
                blockOf = { format -> reportExportBlock(type, format, sessions, species.observations) },
                canAddLocation = type != ReportType.SPECIES,
                onExport = {
                    showExport = false
                    actions.onExport(it)
                },
                onAddLocation = {
                    showExport = false
                    actions.onAddLocation()
                },
                onDismiss = { showExport = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportViewTopBar(report: GeneratedReport, exporting: Boolean, onClose: () -> Unit, onExport: () -> Unit) {
    TopAppBar(
        title = {
            Text(stringResource(if (report.usedAi) R.string.report_header_ai_generated else R.string.report_header_template))
        },
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.report_cd_go_back)) }
        },
        actions = {
            IconButton(onClick = onExport, enabled = !exporting) {
                if (exporting) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.IosShare, stringResource(R.string.results_export))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun ReportBody(
    report: GeneratedReport,
    sessions: List<DetectionSession>,
    species: ReportSpecies,
    actions: ReportViewActions,
    modifier: Modifier,
) {
    val type = ReportType.fromKey(report.reportType)
    val figures = remember(report, sessions) { reportFigures(report, sessions) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!report.usedAi) item { TemplateBanner() }
        item { ReportHeader(report) }
        if (type.hasDebris && (sessions.isNotEmpty() || report.savedFigures() != null)) item { FiguresRow(figures) }
        if (type.hasSpecies && species.observations.isNotEmpty()) {
            speciesItems(species, actions.onObservationClick)
        }
        if (type.hasDebris && sessions.isNotEmpty()) item { AnalysedPhotos(sessions, actions.onSessionClick) }
        item { SonarCard { MarkdownText(text = report.text, modifier = Modifier.fillMaxWidth()) } }
    }
}

@Composable
private fun FiguresRow(figures: BatchStats) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(stringResource(R.string.results_figure_photos), figures.photos.toString(), Modifier.weight(1f))
        StatTile(stringResource(R.string.results_figure_objects), figures.totalObjects.toString(), Modifier.weight(1f))
        StatTile(
            label = stringResource(R.string.results_figure_health),
            value = figures.averageHealth?.toString() ?: "—",
            modifier = Modifier.weight(1f),
        )
    }
}

/** Kind, audience, date and the model that wrote the report, or that it was not recorded. */
@Composable
internal fun ReportHeader(report: GeneratedReport) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    val meta = stringResource(
        if (report.sessionCount != 1) R.string.report_card_meta_plural else R.string.report_card_meta_singular,
        report.sessionCount,
        report.language.uppercase(),
        dateFormat.format(report.timestamp),
    )
    SonarCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = SonarTheme.colors.trace,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    report.locationName?.let {
                        Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                    report.audience?.let { AudienceBadge(audienceText(it)) }
                    ReportTypeBadge(report)
                }
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = report.modelName?.let { stringResource(R.string.results_report_written_by, it) }
                        ?: stringResource(R.string.results_model_unrecorded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
internal fun TemplateBanner() {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = stringResource(R.string.report_template_banner_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/** Row of the photos the report analysed; touching one opens its saved detail. */
@Composable
private fun AnalysedPhotos(sessions: List<DetectionSession>, onSessionClick: (Long) -> Unit) {
    val photos = remember(sessions) {
        sessions.mapNotNull { s -> (s.thumbnailUri ?: s.imageUri).takeIf { it.isNotBlank() }?.let { s to it } }
    }
    if (photos.isEmpty()) return
    SonarSection(stringResource(R.string.report_images_title)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos, key = { it.first.id }) { (session, uri) ->
                Box {
                    AsyncImage(
                        model = Uri.parse(uri),
                        contentDescription = stringResource(R.string.report_cd_session_thumbnail),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(THUMB_SIZE)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { onSessionClick(session.id) },
                    )
                    CountBadge(session.totalCount, Modifier.align(Alignment.TopEnd))
                }
            }
        }
    }
}

@Composable
private fun CountBadge(count: Int, modifier: Modifier) {
    if (count <= 0) return
    Surface(
        modifier = modifier.padding(4.dp),
        shape = RoundedCornerShape(10.dp),
        color = SonarTheme.colors.echo,
    ) {
        Text(
            text = count.toString(),
            style = SonarTheme.type.dataSmall,
            color = SonarTheme.colors.onEcho,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
