package com.oceanguard.ai.ui.screens.models

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val BLOCK_GAP = 8.dp

/**
 * Research contribution: consent, Wi-Fi only and the state of the uploads. Turning it off empties
 * the pending queue; turning it on asks for consent first.
 */
@Composable
fun ContributionSettings(app: OceanGuardApp, scope: CoroutineScope) {
    val settings = app.settingsRepository
    val repository = app.contributionRepository
    val consent by settings.contributeConsentGiven.collectAsStateWithLifecycle(initialValue = false)
    val wifiOnly by settings.contributeWifiOnly.collectAsStateWithLifecycle(initialValue = true)
    var asking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
        SettingsToggleRow(
            title = stringResource(R.string.contribute_toggle_label),
            description = stringResource(
                if (consent) R.string.contribute_consent_given_desc else R.string.contribute_toggle_desc,
            ),
            checked = consent,
            onCheckedChange = { enabled ->
                if (enabled && !consent) {
                    asking = true
                } else {
                    scope.launch {
                        settings.setContributeConsentGiven(enabled)
                        if (!enabled) repository.clearPendingQueue()
                    }
                }
            },
        )
        ContributionDetails(app, scope, consent, wifiOnly)
    }
    if (asking) {
        ConsentDialog(
            onAccept = {
                scope.launch { settings.setContributeConsentGiven(true) }
                asking = false
            },
            onDismiss = { asking = false },
        )
    }
}

/** Wi-Fi only and the state of the uploads, shown once there is consent. */
@Composable
private fun ContributionDetails(app: OceanGuardApp, scope: CoroutineScope, consent: Boolean, wifiOnly: Boolean) {
    val settings = app.settingsRepository
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = consent,
        enter = if (reduced) {
            fadeIn(OceanMotion.reducedFade())
        } else {
            expandVertically(OceanMotion.standard()) + fadeIn(OceanMotion.standard())
        },
        exit = if (reduced) {
            fadeOut(OceanMotion.reducedFade())
        } else {
            shrinkVertically(OceanMotion.exit()) + fadeOut(OceanMotion.exit())
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
            SettingsToggleRow(
                title = stringResource(R.string.contribute_wifi_only_label),
                description = stringResource(R.string.contribute_wifi_only_desc),
                checked = wifiOnly,
                onCheckedChange = { scope.launch { settings.setContributeWifiOnly(it) } },
            )
            UploadStatus(app)
        }
    }
}

@Composable
private fun UploadStatus(app: OceanGuardApp) {
    val repository = app.contributionRepository
    val pending by repository.getPendingCountFlow().collectAsStateWithLifecycle(initialValue = 0)
    val done by repository.getDoneCountFlow().collectAsStateWithLifecycle(initialValue = 0)
    val failed by repository.getFailedCountFlow().collectAsStateWithLifecycle(initialValue = 0)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (done > 0 || pending > 0 || failed > 0) {
                StatusLine(stringResource(R.string.contribute_stats_uploaded, done), MaterialTheme.colorScheme.primary)
                if (pending > 0) {
                    StatusLine(
                        stringResource(R.string.contribute_stats_pending, pending),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (failed > 0) {
                    StatusLine(stringResource(R.string.contribute_stats_failed, failed), MaterialTheme.colorScheme.error)
                }
            } else {
                StatusLine(stringResource(R.string.contribute_all_synced), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (pending > 0) {
            TextButton(onClick = { repository.scheduleImmediateUpload() }) {
                Text(stringResource(R.string.contribute_upload_now_btn))
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun ConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contribute_consent_title)) },
        text = { Text(stringResource(R.string.contribute_consent_body)) },
        confirmButton = {
            TextButton(onClick = onAccept) { Text(stringResource(R.string.contribute_consent_accept)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.contribute_consent_dismiss)) }
        },
    )
}
