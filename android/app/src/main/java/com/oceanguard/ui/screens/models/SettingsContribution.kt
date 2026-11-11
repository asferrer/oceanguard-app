package com.oceanguard.ai.ui.screens.models

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.contribution.ContributionSyncStatus
import com.oceanguard.ai.data.contribution.UploadPanelState
import com.oceanguard.ai.data.contribution.WithdrawResult
import com.oceanguard.ai.data.settings.ContributionSyncState
import com.oceanguard.ai.data.settings.contributeIncludeLocation
import com.oceanguard.ai.data.settings.contributePromptsSilenced
import com.oceanguard.ai.data.settings.contributionClientIdFlow
import com.oceanguard.ai.data.settings.contributionSyncState
import com.oceanguard.ai.data.settings.resetContributePrompts
import com.oceanguard.ai.data.settings.setContributeIncludeLocation
import com.oceanguard.ai.ui.components.ContributionPrivacyLink
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val BLOCK_GAP = 8.dp

/**
 * Research contribution: consent, what travels with the photo, the state of the uploads,
 * withdrawal and the privacy policy. Turning it off empties the pending queue; turning it on
 * asks for consent first. In a build without a destination the switch is disabled and says why.
 */
@Composable
fun ContributionSettings(app: OceanGuardApp, scope: CoroutineScope) {
    val settings = app.settingsRepository
    val repository = app.contributionRepository
    val available = repository.isAvailable
    val consent by settings.contributeConsentGiven.collectAsStateWithLifecycle(initialValue = false)
    var asking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
        SettingsToggleRow(
            title = stringResource(R.string.contribute_toggle_label),
            description = stringResource(
                when {
                    !available -> R.string.contribute_unavailable_desc
                    consent -> R.string.contribute_consent_given_desc
                    else -> R.string.contribute_toggle_desc
                },
            ),
            checked = consent && available,
            enabled = available,
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
        if (available) {
            ContributionDetails(app, scope, consent)
            PromptsAndPolicy(app, scope)
        }
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

/** Wi-Fi only, location, state of the uploads and withdrawal, shown once there is consent. */
@Composable
private fun ContributionDetails(app: OceanGuardApp, scope: CoroutineScope, consent: Boolean) {
    val settings = app.settingsRepository
    val wifiOnly by settings.contributeWifiOnly.collectAsStateWithLifecycle(initialValue = true)
    val includeLocation by settings.contributeIncludeLocation.collectAsStateWithLifecycle(initialValue = true)
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
            SettingsToggleRow(
                title = stringResource(R.string.contribute_include_location_label),
                description = stringResource(R.string.contribute_include_location_desc),
                checked = includeLocation,
                onCheckedChange = { scope.launch { settings.setContributeIncludeLocation(it) } },
            )
            UploadStatus(app, scope, wifiOnly)
        }
    }
    WithdrawBlock(app, scope)
}

/**
 * State of the uploads, said first and in plain words: uploading (with progress), waiting (and for what), failed or all
 * sent, with the one action that applies right under it. The totals and the last contact with the gateway go below.
 */
@Composable
private fun UploadStatus(app: OceanGuardApp, scope: CoroutineScope, wifiOnly: Boolean) {
    val repository = app.contributionRepository
    val pendingFlow = remember(repository) { repository.getPendingCountFlow() }
    val doneFlow = remember(repository) { repository.getDoneCountFlow() }
    val failedFlow = remember(repository) { repository.getFailedCountFlow() }
    val progressFlow = remember(repository) { repository.uploadProgressFlow() }
    val pending by pendingFlow.collectAsStateWithLifecycle(initialValue = 0)
    val done by doneFlow.collectAsStateWithLifecycle(initialValue = 0)
    val failed by failedFlow.collectAsStateWithLifecycle(initialValue = 0)
    val running by progressFlow.collectAsStateWithLifecycle(initialValue = null)
    val sync by app.settingsRepository.contributionSyncState
        .collectAsStateWithLifecycle(initialValue = ContributionSyncState(ContributionSyncStatus.NEVER, null))
    val state = UploadPanelState.of(pending, done, failed, running, wifiOnly)
    val accent = when (state) {
        is UploadPanelState.Uploading, is UploadPanelState.AllSent -> MaterialTheme.colorScheme.primary
        is UploadPanelState.Waiting -> MaterialTheme.colorScheme.tertiary
        is UploadPanelState.Failed -> MaterialTheme.colorScheme.error
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = accent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.contribute_panel_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = panelHeadline(state), style = MaterialTheme.typography.titleMedium, color = accent)
            panelHint(state)?.let { StatusLine(it, MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state is UploadPanelState.Uploading) {
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { (state.current - 1).coerceAtLeast(0) / state.total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            when (state) {
                is UploadPanelState.Waiting -> SonarButton(
                    text = stringResource(R.string.contribute_upload_now_btn),
                    onClick = { repository.scheduleImmediateUpload() },
                    modifier = Modifier.fillMaxWidth(),
                )
                is UploadPanelState.Failed -> SonarButton(
                    text = stringResource(R.string.contribute_retry_failed_btn),
                    onClick = { scope.launch { repository.retryFailed() } },
                    modifier = Modifier.fillMaxWidth(),
                    style = SonarButtonStyle.Secondary,
                )
                else -> Unit
            }
            if (done + pending + failed > 0) {
                StatusLine(
                    stringResource(R.string.contribute_panel_totals, done, pending, failed),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SyncLine(sync)
        }
    }
}

@Composable
private fun panelHeadline(state: UploadPanelState): String {
    val res = LocalResources.current
    return when (state) {
        is UploadPanelState.Uploading -> if (state.total > 0) {
            stringResource(R.string.contribute_panel_uploading, state.current, state.total)
        } else {
            stringResource(R.string.contribute_panel_starting)
        }
        is UploadPanelState.Waiting -> res.getQuantityString(
            if (state.wifiOnly) R.plurals.contribute_panel_waiting_wifi else R.plurals.contribute_panel_waiting,
            state.count, state.count,
        )
        is UploadPanelState.Failed -> res.getQuantityString(R.plurals.contribute_panel_failed, state.count, state.count)
        is UploadPanelState.AllSent -> stringResource(
            if (state.sent > 0) R.string.contribute_panel_all_sent else R.string.contribute_panel_nothing_yet,
        )
    }
}

@Composable
private fun panelHint(state: UploadPanelState): String? = when (state) {
    is UploadPanelState.Waiting -> stringResource(
        if (state.wifiOnly) R.string.contribute_panel_waiting_wifi_hint else R.string.contribute_panel_waiting_hint,
    )
    is UploadPanelState.Failed -> stringResource(R.string.contribute_panel_failed_hint)
    else -> null
}

/** Last contact with the gateway, so a stuck queue always says why. */
@Composable
private fun SyncLine(sync: ContributionSyncState) {
    val at = sync.atMs ?: return
    val text = stringResource(syncStatusText(sync.status))
    val color = if (sync.status == ContributionSyncStatus.OK) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.error
    }
    val whenText = DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    StatusLine(stringResource(R.string.contribute_sync_line, text, whenText.toString()), color)
}

internal fun syncStatusText(status: ContributionSyncStatus): Int = when (status) {
    ContributionSyncStatus.NEVER, ContributionSyncStatus.OK -> R.string.contribute_sync_ok
    ContributionSyncStatus.NOT_CONFIGURED -> R.string.contribute_sync_not_configured
    ContributionSyncStatus.AUTH -> R.string.contribute_sync_auth
    ContributionSyncStatus.ATTESTATION -> R.string.contribute_sync_attestation
    ContributionSyncStatus.APP_NOT_OFFICIAL -> R.string.contribute_sync_app_not_official
    ContributionSyncStatus.RATE_LIMITED -> R.string.contribute_sync_rate_limited
    ContributionSyncStatus.QUOTA -> R.string.contribute_sync_quota
    ContributionSyncStatus.SERVER -> R.string.contribute_sync_server
    ContributionSyncStatus.NETWORK -> R.string.contribute_sync_network
    ContributionSyncStatus.REJECTED -> R.string.contribute_sync_rejected
}

@Composable
private fun StatusLine(text: String, color: Color) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** Deletes this install's photos on the server; shows the anonymous id for e-mail requests. */
@Composable
private fun WithdrawBlock(app: OceanGuardApp, scope: CoroutineScope) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val done by app.contributionRepository.getDoneCountFlow().collectAsStateWithLifecycle(initialValue = 0)
    val clientId by app.settingsRepository.contributionClientIdFlow.collectAsStateWithLifecycle(initialValue = null)
    var confirming by remember { mutableStateOf(false) }
    val id = clientId ?: return
    Column(verticalArrangement = Arrangement.spacedBy(BLOCK_GAP / 2)) {
        SelectionContainer {
            StatusLine(stringResource(R.string.contribute_client_id_line, id), MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (done > 0) {
            TextButton(onClick = { confirming = true }) {
                Text(stringResource(R.string.contribute_withdraw_btn), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirming) {
        WithdrawDialog(
            onConfirm = {
                confirming = false
                scope.launch {
                    val message = when (app.contributionRepository.withdrawAll()) {
                        is WithdrawResult.Done -> resources.getString(R.string.contribute_withdraw_done)
                        else -> resources.getString(R.string.contribute_withdraw_failed)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { confirming = false },
        )
    }
}

/** Privacy policy link and, when the prompt was silenced, the button that brings it back. */
@Composable
private fun PromptsAndPolicy(app: OceanGuardApp, scope: CoroutineScope) {
    val context = LocalContext.current
    val resetDone = stringResource(R.string.contribute_reset_prompts_done)
    val silenced by app.settingsRepository.contributePromptsSilenced.collectAsStateWithLifecycle(initialValue = false)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ContributionPrivacyLink()
        if (silenced) {
            TextButton(
                onClick = {
                    scope.launch {
                        app.settingsRepository.resetContributePrompts()
                        Toast.makeText(context, resetDone, Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text(stringResource(R.string.contribute_reset_prompts_btn)) }
        }
    }
}

@Composable
private fun ConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contribute_consent_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.contribute_consent_body))
                ContributionPrivacyLink()
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text(stringResource(R.string.contribute_consent_accept)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.contribute_consent_dismiss)) }
        },
    )
}

@Composable
private fun WithdrawDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contribute_withdraw_title)) },
        text = { Text(stringResource(R.string.contribute_withdraw_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.contribute_withdraw_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.contribute_consent_dismiss)) }
        },
    )
}
