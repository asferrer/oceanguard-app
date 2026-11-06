package com.oceanguard.ai.ui.screens.history

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.ui.components.EditDateDialog
import com.oceanguard.ai.ui.components.EditLocationDialog

/** The bulk action waiting for confirmation, if any. */
enum class BulkDialog { None, EditDate, EditLocation, Delete }

/**
 * Dialog of the chosen bulk action over [count] selected analyses. Each callback receives what
 * the user confirmed; the screen applies it and leaves selection mode.
 */
@Composable
fun HistoryBulkDialogs(
    dialog: BulkDialog,
    count: Int,
    app: OceanGuardApp,
    onDismiss: () -> Unit,
    onDate: (Long) -> Unit,
    onLocation: (Location) -> Unit,
    onDelete: () -> Unit,
) {
    when (dialog) {
        BulkDialog.None -> Unit
        BulkDialog.EditDate -> EditDateDialog(selectedCount = count, onConfirm = onDate, onDismiss = onDismiss)
        BulkDialog.EditLocation -> EditLocationDialog(
            selectedCount = count,
            geocoder = app.geocoder,
            onConfirm = onLocation,
            onDismiss = onDismiss,
        )
        BulkDialog.Delete -> DeleteConfirmDialog(count, onDelete, onDismiss)
    }
}

@Composable
private fun DeleteConfirmDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bulk_delete_title)) },
        text = { Text(stringResource(R.string.bulk_delete_message, count)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.bulk_delete_title), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
