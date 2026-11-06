package com.oceanguard.ai.ui.screens.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.data.AnalysisModeAvailability
import com.oceanguard.ai.inference.VlmDownloadState
import com.oceanguard.ai.ui.components.sonar.ButtonProgress
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.components.sonar.SonarProgressButton
import com.oceanguard.ai.ui.components.sonar.SonarSection
import com.oceanguard.ai.ui.components.sonar.modeTimeLabel
import com.oceanguard.ai.ui.theme.SonarTheme

private val OPTION_MIN_HEIGHT = 64.dp
private val OPTION_GAP = 12.dp
private val BLOCK_GAP = 8.dp
private const val DISABLED_ALPHA = 0.6f

/** The single download slot of the app, and which catalog model is using it. */
@Immutable
data class DownloadUi(val state: VlmDownloadState, val owner: CatalogModel?) {
    val busy: Boolean get() = isDownloadBusy(state)
}

/**
 * Download button that becomes its own progress bar: bytes while they flow, then preparing and
 * installing. Only the owner shows progress; with another download running the rest wait.
 */
@Composable
fun ModelDownloadButton(
    model: CatalogModel,
    idleLabel: String,
    ui: DownloadUi,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOwner = ui.owner == model
    val progress = buttonProgress(ui.state, isOwner)
    SonarProgressButton(
        text = if (progress == ButtonProgress.Idle) idleLabel else busyLabel(ui.state),
        progress = progress,
        onClick = onDownload,
        modifier = modifier,
        enabled = !ui.busy,
        onCancel = if (isOwner) onCancel else null,
    )
}

@Composable
private fun busyLabel(state: VlmDownloadState): String = when (state) {
    is VlmDownloadState.Downloading -> stringResource(
        R.string.models_downloading_bytes,
        formatBytes(state.downloadedBytes),
        formatBytes(state.totalBytes),
    )
    VlmDownloadState.Installing -> stringResource(R.string.models_installing)
    else -> stringResource(R.string.models_preparing)
}

/** The four modes as one choice. A mode whose model is missing downloads it and then applies. */
@Composable
fun ModeSection(
    saved: AnalysisMode,
    availability: (AnalysisMode) -> AnalysisModeAvailability,
    ui: DownloadUi,
    onSelect: (AnalysisMode) -> Unit,
    onDownload: (AnalysisMode) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SonarSection(stringResource(R.string.models_modes_title), modifier) {
        Text(
            text = stringResource(R.string.models_modes_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
            AnalysisMode.entries.forEach { mode ->
                ModeCard(mode, availability(mode), mode == saved, ui, onSelect, onDownload, onCancel)
            }
        }
    }
}

@Composable
private fun ModeCard(
    mode: AnalysisMode,
    state: AnalysisModeAvailability,
    selected: Boolean,
    ui: DownloadUi,
    onSelect: (AnalysisMode) -> Unit,
    onDownload: (AnalysisMode) -> Unit,
    onCancel: () -> Unit,
) {
    val enabled = state != AnalysisModeAvailability.NOT_PUBLISHED
    val needsDownload = state == AnalysisModeAvailability.NEEDS_DOWNLOAD
    SonarCard {
        Column(verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = OPTION_MIN_HEIGHT)
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { if (needsDownload) onDownload(mode) else onSelect(mode) },
                    ),
                horizontalArrangement = Arrangement.spacedBy(OPTION_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null, enabled = enabled)
                ModeTexts(mode, state, enabled, Modifier.weight(1f))
            }
            if (needsDownload) {
                val model = CatalogModel.forMode(mode)
                if (model != null) {
                    ModelDownloadButton(
                        model = model,
                        idleLabel = stringResource(R.string.models_download_and_use, formatBytes(model.approxBytes)),
                        ui = ui,
                        onDownload = { onDownload(mode) },
                        onCancel = onCancel,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeTexts(
    mode: AnalysisMode,
    state: AnalysisModeAvailability,
    enabled: Boolean,
    modifier: Modifier,
) {
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Column(modifier) {
        Text(
            text = stringResource(mode.labelRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
        Text(
            text = stringResource(mode.descriptionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
        if (mode == AnalysisMode.TUNED && enabled) {
            Text(
                text = stringResource(R.string.models_tuned_howto),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = BLOCK_GAP / 2),
            )
        }
        Text(
            text = modeFacts(mode, state),
            style = SonarTheme.type.dataSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
            modifier = Modifier.padding(top = BLOCK_GAP / 2),
        )
    }
}

@Composable
private fun modeFacts(mode: AnalysisMode, state: AnalysisModeAvailability): String {
    val time = modeTimeLabel(mode)
    val status = when (state) {
        AnalysisModeAvailability.NOT_PUBLISHED -> stringResource(R.string.analysis_mode_not_published)
        AnalysisModeAvailability.NEEDS_DOWNLOAD -> stringResource(R.string.models_status_not_installed)
        AnalysisModeAvailability.READY -> stringResource(
            if (mode.needsGemma) R.string.models_status_installed else R.string.analysis_mode_included,
        )
    }
    return "$time · $status"
}
