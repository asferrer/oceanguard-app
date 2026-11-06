package com.oceanguard.ai.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.oceanguard.ai.R

/**
 * Contextual top bar displayed when the user has entered multi-select mode
 * on the History screen, by the Select button or a long press.
 *
 * Shows the number of currently selected sessions and provides bulk-action
 * shortcuts for date editing, location editing, deletion, and select-all.
 *
 * @param selectedCount Number of sessions currently selected.
 * @param onClose       Called when the user taps the close icon to exit selection mode.
 * @param onEditDate    Called when the user taps the calendar icon to bulk-edit dates.
 * @param onEditLocation Called when the user taps the location icon to bulk-edit locations.
 * @param onDelete      Called when the user taps the delete icon to bulk-delete sessions.
 * @param onSelectAll   Called when the user taps the select-all icon.
 * @param actionsEnabled False while nothing is selected, when editing or deleting has no target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onEditDate: () -> Unit,
    onEditLocation: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    actionsEnabled: Boolean = true,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_exit_selection),
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.bulk_selected_count, selectedCount),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        actions = { SelectionActions(onEditDate, onEditLocation, onDelete, onSelectAll, actionsEnabled) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@Composable
private fun RowScope.SelectionActions(
    onEditDate: () -> Unit,
    onEditLocation: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    enabled: Boolean,
) {
    IconButton(onClick = onEditDate, enabled = enabled) {
        Icon(Icons.Filled.CalendarMonth, stringResource(R.string.cd_edit_date))
    }
    IconButton(onClick = onEditLocation, enabled = enabled) {
        Icon(Icons.Filled.LocationOn, stringResource(R.string.cd_edit_location))
    }
    IconButton(onClick = onDelete, enabled = enabled) {
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = stringResource(R.string.cd_delete_session),
            tint = if (enabled) MaterialTheme.colorScheme.error else LocalContentColor.current,
        )
    }
    IconButton(onClick = onSelectAll) {
        Icon(Icons.Filled.DoneAll, stringResource(R.string.bulk_select_all))
    }
}
