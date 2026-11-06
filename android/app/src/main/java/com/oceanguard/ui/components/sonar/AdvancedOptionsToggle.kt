package com.oceanguard.ai.ui.components.sonar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R

private val TOGGLE_MIN_HEIGHT = 56.dp

/**
 * "Show advanced options". Turning it on asks first, turning it off does not. Bind it to
 * `settingsRepository.devModeEnabled`: advanced options are the former developer mode, so whoever
 * had it enabled keeps it, and the 21 taps stay as an alternative way in.
 */
@Composable
fun AdvancedOptionsToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TOGGLE_MIN_HEIGHT)
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = { on -> if (on) asking = true else onEnabledChange(false) },
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.advanced_options_show),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = enabled, onCheckedChange = null)
    }
    if (asking) {
        AdvancedOptionsNotice(
            onConfirm = {
                asking = false
                onEnabledChange(true)
            },
            onDismiss = { asking = false },
        )
    }
}

@Composable
private fun AdvancedOptionsNotice(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.advanced_options_notice_title)) },
        text = { Text(stringResource(R.string.advanced_options_notice_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.advanced_options_notice_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
