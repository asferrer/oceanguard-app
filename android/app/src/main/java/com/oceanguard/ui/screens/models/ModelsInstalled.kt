package com.oceanguard.ai.ui.screens.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.inference.DownloadFailureKind
import com.oceanguard.ai.inference.VlmDownloadState
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.components.sonar.SonarErrorState
import com.oceanguard.ai.ui.components.sonar.SonarSection
import com.oceanguard.ai.ui.components.sonar.SwipeUndoRow
import com.oceanguard.ai.ui.components.sonar.UndoableDeletes
import com.oceanguard.ai.ui.theme.SonarTheme

private val ROW_GAP = 12.dp
private val BLOCK_GAP = 8.dp
private const val BUNDLED_DETECTOR_BYTES = 83_000_000L

/** Free space on the phone and what the models take, so deleting one has a visible reason. */
@Composable
private fun StorageLine(freeBytes: Long, usedBytes: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = stringResource(R.string.models_storage_free, formatBytes(freeBytes)),
            style = SonarTheme.type.dataSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.models_storage_used, formatBytes(usedBytes)),
            style = SonarTheme.type.dataSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What is installed, with its size; swipe or press the bin to delete, with Undo. */
@Composable
fun InstalledSection(
    models: List<CatalogModel>,
    sizes: Map<CatalogModel, Long>,
    freeBytes: Long,
    deletes: UndoableDeletes<CatalogModel>,
    onDelete: (CatalogModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    SonarSection(stringResource(R.string.models_installed_title), modifier) {
        StorageLine(freeBytes, sizes.values.sum())
        Column(verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
            BundledDetectorRow()
            models.forEach { model ->
                if (model.deletable) {
                    SwipeUndoRow(model, deletes) {
                        InstalledRow(model, sizes[model] ?: 0L, onDelete = { onDelete(model) })
                    }
                } else {
                    InstalledRow(model, sizes[model] ?: 0L, onDelete = null)
                }
            }
        }
    }
}

@Composable
private fun BundledDetectorRow() {
    SonarCard {
        Column {
            Text(
                text = stringResource(R.string.models_item_rtdetr),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.models_item_rtdetr_purpose),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${stringResource(R.string.analysis_mode_included)} · ${formatBytes(BUNDLED_DETECTOR_BYTES)}",
                style = SonarTheme.type.dataSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun InstalledRow(model: CatalogModel, bytes: Long, onDelete: (() -> Unit)?) {
    val title = stringResource(model.titleRes)
    SonarCard {
        Row(horizontalArrangement = Arrangement.spacedBy(ROW_GAP), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = stringResource(model.purposeRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatBytes(bytes),
                    style = SonarTheme.type.dataSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.models_delete_cd, title))
                }
            }
        }
    }
}

/** Models that are not installed and are not reached by choosing a mode. */
@Composable
fun MoreDownloadsSection(
    models: List<CatalogModel>,
    ui: DownloadUi,
    onDownload: (CatalogModel) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (models.isEmpty()) return
    SonarSection(stringResource(R.string.models_more_title), modifier) {
        models.forEach { model ->
            SonarCard {
                Column(verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
                    Text(
                        text = stringResource(model.titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(model.purposeRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ModelDownloadButton(
                        model = model,
                        idleLabel = stringResource(R.string.models_download_with_size, formatBytes(model.approxBytes)),
                        ui = ui,
                        onDownload = { onDownload(model) },
                        onCancel = onCancel,
                    )
                }
            }
        }
    }
}

/** A failed download in plain words, with Retry and a way to dismiss it. */
@Composable
fun DownloadErrorCard(
    state: VlmDownloadState.Error,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SonarCard(modifier) {
        SonarErrorState(
            title = stringResource(R.string.models_error_title),
            message = stringResource(failureMessage(state.kind)),
            onRetry = onRetry,
        )
        SonarButton(
            text = stringResource(R.string.common_close),
            onClick = onDismiss,
            style = SonarButtonStyle.Quiet,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One plain sentence per failure: what happened and what to do. */
internal fun failureMessage(kind: DownloadFailureKind): Int = when (kind) {
    DownloadFailureKind.NO_CONNECTION -> R.string.models_error_no_connection
    DownloadFailureKind.NO_SPACE -> R.string.models_error_no_space
    DownloadFailureKind.NOT_FOUND -> R.string.models_error_not_found
    DownloadFailureKind.SERVER -> R.string.models_error_server
    DownloadFailureKind.INCOMPLETE -> R.string.models_error_incomplete
    DownloadFailureKind.UNKNOWN -> R.string.models_error_unknown
}
