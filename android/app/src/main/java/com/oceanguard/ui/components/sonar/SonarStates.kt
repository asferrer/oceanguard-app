package com.oceanguard.ai.ui.components.sonar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R

private val STATE_ICON_SIZE = 40.dp
private val STATE_PADDING = 24.dp
private val STATE_GAP = 8.dp
private val STATE_ACTION_GAP = 12.dp

@Composable
private fun StateMessage(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    message: String,
    modifier: Modifier,
    action: @Composable (() -> Unit)?,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(STATE_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(STATE_GAP),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(STATE_ICON_SIZE))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Column(Modifier.padding(top = STATE_ACTION_GAP)) { action() }
        }
    }
}

/** Nothing to show yet. Offer the next step in [actionLabel] whenever there is one. */
@Composable
fun SonarEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StateMessage(
        icon = icon,
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        title = title,
        message = message,
        modifier = modifier,
        action = if (actionLabel != null && onAction != null) {
            { SonarButton(text = actionLabel, onClick = onAction) }
        } else {
            null
        },
    )
}

/**
 * Something failed. [message] says in plain language what happened and what to do: never a raw
 * exception. TalkBack announces it without moving the focus.
 */
@Composable
fun SonarErrorState(
    title: String,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StateMessage(
        icon = Icons.Filled.ErrorOutline,
        iconTint = MaterialTheme.colorScheme.error,
        title = title,
        message = message,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        action = {
            SonarButton(
                text = stringResource(R.string.common_retry),
                onClick = onRetry,
                icon = Icons.Filled.Refresh,
            )
        },
    )
}
