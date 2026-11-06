package com.oceanguard.ai.ui.screens.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.sonar.ButtonProgress
import com.oceanguard.ai.ui.components.sonar.SonarProgressButton
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.screens.results.ReportModelStatus

/** Everything the generate button and the model line need to say. */
internal data class GenerateUi(
    val modelStatus: ReportModelStatus,
    val modelName: String,
    val modelSize: String,
    val downloadProgress: ButtonProgress,
    val generating: Boolean,
    val hasZone: Boolean,
    val sessionCount: Int,
)

/** Model status first, so the person knows before pressing what Generate will do. */
@Composable
internal fun ReportGenerateSection(
    ui: GenerateUi,
    onGenerate: () -> Unit,
    onCancelDownload: () -> Unit,
    boundsMap: MutableMap<String, Rect>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelStatusLine(ui)
        val downloading = ui.modelStatus == ReportModelStatus.DOWNLOADING
        val progress = when {
            downloading -> ui.downloadProgress
            ui.generating -> ButtonProgress.Indeterminate
            else -> ButtonProgress.Idle
        }
        SonarProgressButton(
            text = generateLabel(ui),
            progress = progress,
            onClick = onGenerate,
            enabled = ui.hasZone && ui.sessionCount > 0,
            onCancel = if (downloading) onCancelDownload else null,
            modifier = Modifier.spotlightTarget("reports_generate", boundsMap),
        )
        if (ui.modelStatus != ReportModelStatus.READY) {
            Text(
                text = stringResource(R.string.results_report_auto_start),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun generateLabel(ui: GenerateUi): String = stringResource(
    when {
        ui.generating -> R.string.report_btn_generating
        ui.modelStatus == ReportModelStatus.DOWNLOADING -> R.string.results_report_model_downloading
        !ui.hasZone -> R.string.report_zone_no_selection
        ui.modelStatus == ReportModelStatus.NEEDS_DOWNLOAD -> R.string.results_report_generate_download
        else -> R.string.report_btn_generate_zone
    },
)

@Composable
private fun ModelStatusLine(ui: GenerateUi) {
    val (icon, text) = when (ui.modelStatus) {
        ReportModelStatus.READY ->
            Icons.Filled.CheckCircle to stringResource(R.string.results_report_model_ready, ui.modelName)
        ReportModelStatus.NEEDS_DOWNLOAD -> Icons.Filled.CloudDownload to
            stringResource(R.string.results_report_model_missing, ui.modelName, ui.modelSize)
        ReportModelStatus.DOWNLOADING ->
            Icons.Filled.Sync to stringResource(R.string.results_report_model_downloading_named, ui.modelName)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
