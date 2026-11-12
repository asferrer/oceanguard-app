package com.oceanguard.ai.ui.screens.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.inference.species.SpeciesPackConfig
import com.oceanguard.ai.inference.species.SpeciesPackUpdate
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the species pack prompt sits: a card on Home, or bare inside another screen's content. */
enum class SpeciesPackPlacement { CARD, INLINE }

/**
 * One-tap download of the species pack (BioDex identifier) from wherever the pack is missing.
 * It goes through [ModelDownloader] (space, network and mobile-data checks), shows progress with
 * a cancel button, explains a failure and offers a retry. When the pack finishes installing it
 * calls [onInstalled] and, if [showReady], keeps a quiet "ready" line.
 */
@Composable
fun SpeciesPackPrompt(
    modifier: Modifier = Modifier,
    placement: SpeciesPackPlacement = SpeciesPackPlacement.CARD,
    showReady: Boolean = false,
    onInstalled: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as OceanGuardApp
    val downloader = rememberModelDownloader()
    val packState = rememberSpeciesPackState(app)
    val status = packState.status
    var previous by remember { mutableStateOf<SpeciesPackStatus?>(null) }
    LaunchedEffect(status) {
        if (SpeciesPackLogic.justInstalled(previous, status)) onInstalled()
        previous = status
    }
    downloader.PreflightDialog()
    if (status == SpeciesPackStatus.Ready && !showReady) return
    val content: @Composable () -> Unit = {
        SpeciesPackContent(status, app, packState.updateBytes, onDownload = { downloader.request(CatalogModel.SPECIES_PACK) })
    }
    if (placement == SpeciesPackPlacement.CARD) SonarCard(modifier = modifier) { content() }
    else Column(modifier = modifier) { content() }
}

/** Status of the pack and, when an update is offered, about how many bytes it downloads (null when unknown). */
private data class SpeciesPackCardState(val status: SpeciesPackStatus, val updateBytes: Long?)

@Composable
private fun rememberSpeciesPackState(app: OceanGuardApp): SpeciesPackCardState {
    val manager = app.vlmModelManager
    val state by manager.downloadState.collectAsStateWithLifecycle()
    val selected by manager.speciesPackSelected.collectAsStateWithLifecycle()
    val revision by manager.speciesPackRevision.collectAsStateWithLifecycle()
    var installedVersion by remember { mutableStateOf(manager.installedSpeciesPackVersion()) }
    var remote by remember { mutableStateOf<SpeciesPackConfig?>(null) }
    // Re-read the disk when the download ends (completed, failed or cancelled), not on each byte; the revision
    // covers the end of an update, whose last state has the same class as the per-file ones.
    LaunchedEffect(state.javaClass, revision) {
        installedVersion = withContext(Dispatchers.IO) { manager.installedSpeciesPackVersion() }
    }
    // Once per installed version: is a newer pack published? (null offline: no offer)
    LaunchedEffect(installedVersion) {
        if (installedVersion != null) remote = manager.remoteSpeciesPackConfig()
    }
    val updateAvailable = SpeciesPackUpdate.isNewer(installedVersion, remote?.version)
    // Only the files that change are downloaded (the encoder is usually kept): show that size, not the whole pack's.
    var updateBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(updateAvailable, remote) {
        val newer = remote
        updateBytes = if (!updateAvailable || newer == null) null else withContext(Dispatchers.IO) {
            manager.installedSpeciesPackConfig()?.let { installed ->
                SpeciesPackUpdate.approxUpdateBytes(
                    installed, newer, CatalogModel.SPECIES_PACK.approxBytes, manager::installedSpeciesFileLength,
                )
            }
        }
    }
    return SpeciesPackCardState(SpeciesPackLogic.status(installedVersion != null, selected, state, updateAvailable), updateBytes)
}

@Composable
private fun SpeciesPackContent(status: SpeciesPackStatus, app: OceanGuardApp, updateBytes: Long?, onDownload: () -> Unit) {
    val size = formatBytes(CatalogModel.SPECIES_PACK.approxBytes)
    val updateSize = updateBytes?.let { formatBytes(it) } ?: size
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (status) {
            SpeciesPackStatus.Ready -> PackLine(stringResource(R.string.species_pack_status_ready))
            SpeciesPackStatus.UpdateAvailable -> PackRow(
                text = stringResource(R.string.species_pack_status_update, updateSize),
                button = stringResource(R.string.species_pack_btn_update),
                onClick = onDownload,
            )
            SpeciesPackStatus.Missing -> PackRow(
                text = stringResource(R.string.species_pack_status_missing, size),
                button = stringResource(R.string.species_pack_btn_download),
                onClick = onDownload,
            )
            is SpeciesPackStatus.Failed -> {
                PackLine(stringResource(failureMessage(status.kind)), error = true)
                SonarButton(
                    text = stringResource(R.string.species_pack_btn_retry),
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is SpeciesPackStatus.Downloading ->
                PackProgress(status, onCancel = { app.vlmModelManager.cancelDownload() })
        }
    }
}

@Composable
private fun PackLine(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PackRow(text: String, button: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClick) { Text(button) }
    }
}

@Composable
private fun PackProgress(status: SpeciesPackStatus.Downloading, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.species_pack_status_downloading),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
    }
    val bar = Modifier.fillMaxWidth()
    val track = MaterialTheme.colorScheme.surfaceVariant
    val progress = status.progress
    if (progress != null) {
        LinearProgressIndicator(progress = { progress }, modifier = bar, trackColor = track)
        Text(
            text = "${formatBytes(status.downloadedBytes)} / ${formatBytes(status.totalBytes)}",
            style = SonarTheme.type.dataSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        LinearProgressIndicator(modifier = bar, trackColor = track)
    }
}
