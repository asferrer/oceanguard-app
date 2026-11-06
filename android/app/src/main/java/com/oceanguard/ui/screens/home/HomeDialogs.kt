package com.oceanguard.ai.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.utils.ModelUpdate
import com.oceanguard.ai.utils.UpdateInfo
import java.util.Locale

private const val RELEASE_NOTES_MAX_CHARS = 600
private const val MB_PER_GB = 1000

/** Offers the download of the model a mode needs, with its real size. */
@Composable
fun ModeDownloadDialog(
    mode: AnalysisMode,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_download_title)) },
        text = {
            Text(
                text = stringResource(
                    R.string.home_download_message,
                    stringResource(mode.labelRes),
                    HomeLogic.formatGb(mode.approxDownloadGb ?: 0f),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.vlm_download_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
fun UpdateAvailableDialog(
    info: UpdateInfo,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_available_title)) },
        text = {
            Text(
                text = stringResource(R.string.update_new_version, info.versionName),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDownload) { Text(stringResource(R.string.update_download)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
        },
    )
}

@Composable
fun ModelUpdateAvailableDialog(
    update: ModelUpdate,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val delta = String.format(Locale.getDefault(), "+%.3f", update.deltaVsBase)
    val size = if (update.sizeMb > MB_PER_GB) {
        String.format(Locale.getDefault(), "~%.1f GB", update.sizeMb / MB_PER_GB.toFloat())
    } else {
        "~${update.sizeMb} MB"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_update_title)) },
        text = { ModelUpdateText(update, delta, size) },
        confirmButton = {
            TextButton(onClick = onDownload) { Text(stringResource(R.string.model_update_download_action)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onSkip) { Text(stringResource(R.string.model_update_skip_action)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_update_later_action)) }
            }
        },
    )
}

@Composable
private fun ModelUpdateText(update: ModelUpdate, delta: String, size: String) {
    Column {
        Text(
            text = stringResource(R.string.model_update_description, update.version, delta, size),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (update.releaseNotes.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = update.releaseNotes.take(RELEASE_NOTES_MAX_CHARS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
}
