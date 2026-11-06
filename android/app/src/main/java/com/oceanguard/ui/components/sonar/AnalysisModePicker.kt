package com.oceanguard.ai.ui.components.sonar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.data.AnalysisModeAvailability
import com.oceanguard.ai.ui.theme.SonarTheme
import java.util.Locale
import kotlinx.coroutines.launch

private val CHIP_MIN_HEIGHT = 48.dp
private val CHIP_ICON_SIZE = 18.dp
private val CHIP_PADDING = 12.dp
private val CHIP_GAP = 6.dp
private val SHEET_PADDING = 16.dp
private val SHEET_GAP = 4.dp
private val OPTION_MIN_HEIGHT = 64.dp
private val OPTION_GAP = 12.dp
private const val DISABLED_ALPHA = 0.6f

/**
 * Mode chip and quick sheet, ready to drop in Home and the camera: reads the saved mode, changes
 * it through `OceanGuardApp.applyAnalysisMode`, and hands over [onDownload] when the chosen mode's
 * model is missing, without changing the mode.
 */
@Composable
fun AnalysisModePicker(
    onDownload: (AnalysisMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as OceanGuardApp
    val scope = rememberCoroutineScope()
    val initial = remember { app.settingsRepository.getAnalysisModeSync() }
    val mode by app.settingsRepository.analysisMode.collectAsStateWithLifecycle(initial)
    var open by rememberSaveable { mutableStateOf(false) }

    AnalysisModeChip(mode = mode, onClick = { open = true }, modifier = modifier)
    if (open) {
        AnalysisModeSheet(
            current = mode,
            availability = { it.availability(app::isModelDownloaded) },
            onSelect = { picked ->
                open = false
                scope.launch { app.applyAnalysisMode(picked) }
            },
            onDownload = { picked ->
                open = false
                onDownload(picked)
            },
            onDismiss = { open = false },
        )
    }
}

/** Shows the mode in use and opens the sheet. */
@Composable
fun AnalysisModeChip(mode: AnalysisMode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(mode.labelRes)
    val description = stringResource(R.string.analysis_mode_current_cd, label)
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = CHIP_MIN_HEIGHT)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                // clearAndSetSemantics drops the click action Surface gives, which TalkBack needs.
                onClick { onClick(); true }
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = CHIP_PADDING),
            horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Tune, null, Modifier.size(CHIP_ICON_SIZE))
            Text(label, style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Filled.ExpandMore, null, Modifier.size(CHIP_ICON_SIZE))
        }
    }
}

/** Quick sheet of the four modes. Prefer [AnalysisModePicker]; this one needs wiring by hand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisModeSheet(
    current: AnalysisMode,
    availability: (AnalysisMode) -> AnalysisModeAvailability,
    onSelect: (AnalysisMode) -> Unit,
    onDownload: (AnalysisMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SHEET_PADDING)
                .padding(bottom = SHEET_PADDING)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(SHEET_GAP),
        ) {
            Text(
                text = stringResource(R.string.analysis_mode_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.analysis_mode_offline_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.selectableGroup()) {
                AnalysisMode.entries.forEach { mode ->
                    val state = availability(mode)
                    ModeOption(mode, state, selected = mode == current) {
                        if (state == AnalysisModeAvailability.NEEDS_DOWNLOAD) {
                            onDownload(mode)
                        } else {
                            onSelect(mode)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeOption(
    mode: AnalysisMode,
    state: AnalysisModeAvailability,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val enabled = state != AnalysisModeAvailability.NOT_PUBLISHED
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OPTION_MIN_HEIGHT)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(OPTION_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
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
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = modeTimeLabel(mode),
                style = SonarTheme.type.dataSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
            )
            Text(
                text = modeCostLabel(mode, state),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
    }
}

/** Measured time per photo, or the plain truth that it has not been measured. */
@Composable
fun modeTimeLabel(mode: AnalysisMode): String = mode.approxSecondsPerPhoto
    ?.let { stringResource(R.string.analysis_mode_seconds, it) }
    ?: stringResource(R.string.analysis_mode_time_unknown)

@Composable
private fun modeCostLabel(mode: AnalysisMode, state: AnalysisModeAvailability): String = when {
    state == AnalysisModeAvailability.NOT_PUBLISHED ->
        stringResource(R.string.analysis_mode_not_published)
    state == AnalysisModeAvailability.NEEDS_DOWNLOAD -> stringResource(
        R.string.analysis_mode_download,
        String.format(Locale.getDefault(), "%.1f", mode.approxDownloadGb ?: 0f),
    )
    else -> stringResource(R.string.analysis_mode_included)
}
