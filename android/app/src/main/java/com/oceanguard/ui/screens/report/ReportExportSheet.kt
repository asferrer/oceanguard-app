package com.oceanguard.ai.ui.screens.report

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.screens.results.ExportBlock
import com.oceanguard.ai.ui.screens.results.ReportExportFormat
import com.oceanguard.ai.ui.screens.results.exportBlock

/**
 * Formats the open report can be exported in. A format that cannot be produced stays visible
 * and says why; the one that needs a place offers to add it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportExportSheet(
    sessions: List<DetectionSession>,
    onExport: (ReportExportFormat) -> Unit,
    onAddLocation: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.results_export),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            for (format in ReportExportFormat.entries) {
                val block = exportBlock(format, sessions)
                ExportRow(format, block) { onExport(format) }
                if (block == ExportBlock.NO_LOCATION) {
                    SonarButton(
                        text = stringResource(R.string.results_add_location),
                        onClick = onAddLocation,
                        style = SonarButtonStyle.Secondary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportRow(format: ReportExportFormat, block: ExportBlock?, onClick: () -> Unit) {
    val enabled = block == null
    val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(format.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Column {
            Text(
                text = stringResource(format.titleRes()),
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(block.reasonRes() ?: format.descriptionRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun ReportExportFormat.icon(): ImageVector = when (this) {
    ReportExportFormat.PDF -> Icons.Filled.PictureAsPdf
    ReportExportFormat.MARKDOWN -> Icons.AutoMirrored.Filled.Notes
    ReportExportFormat.CSV -> Icons.Filled.TableChart
    ReportExportFormat.GEOJSON -> Icons.Filled.Map
}

private fun ReportExportFormat.titleRes(): Int = when (this) {
    ReportExportFormat.PDF -> R.string.results_export_pdf
    ReportExportFormat.MARKDOWN -> R.string.results_export_markdown
    ReportExportFormat.CSV -> R.string.results_export_csv
    ReportExportFormat.GEOJSON -> R.string.results_export_geojson
}

private fun ReportExportFormat.descriptionRes(): Int = when (this) {
    ReportExportFormat.PDF -> R.string.results_export_pdf_desc
    ReportExportFormat.MARKDOWN -> R.string.results_export_markdown_desc
    ReportExportFormat.CSV -> R.string.results_export_csv_desc
    ReportExportFormat.GEOJSON -> R.string.results_export_geojson_desc
}

private fun ExportBlock?.reasonRes(): Int? = when (this) {
    ExportBlock.NO_SESSIONS -> R.string.results_export_no_photos
    ExportBlock.NO_LOCATION -> R.string.results_export_no_location
    null -> null
}
