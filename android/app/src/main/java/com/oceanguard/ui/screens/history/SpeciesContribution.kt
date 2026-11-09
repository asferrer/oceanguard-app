package com.oceanguard.ai.ui.screens.history

import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.contribution.ContributionOutputs
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlinx.coroutines.launch

/**
 * Upload of the photos of a species identification for research, the same action debris photos
 * have. Shown only with a destination in this build and the user's consent; the answer the app
 * gave (species, candidates, confidence) goes with each photo.
 */
@Composable
fun SpeciesUploadButton(group: SpeciesHistoryGroup, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = remember(context) { context.applicationContext as OceanGuardApp }
    val consent by app.settingsRepository.contributeConsentGiven.collectAsStateWithLifecycle(initialValue = false)
    if (!app.contributionRepository.isAvailable || !consent) return
    val enqueueError = stringResource(R.string.contribute_enqueue_error)
    val status by app.contributionRepository
        .getStatusFlowForUri(group.cover.imageUri)
        .collectAsStateWithLifecycle(initialValue = "LOADING")
    val (icon, description) = when (status) {
        "PENDING" -> Icons.Filled.Sync to R.string.contribute_status_pending
        "DONE" -> Icons.Filled.CheckCircle to R.string.contribute_status_done
        "FAILED" -> Icons.Filled.Refresh to R.string.contribute_status_failed
        else -> Icons.Filled.CloudUpload to R.string.contribute_upload_btn
    }
    IconButton(
        onClick = {
            scope.launch {
                val ok = group.observations.groupBy { it.imageUri }.map { (uri, rows) ->
                    app.contributionRepository.enqueueSpecies(uri, rows.map(ContributionOutputs::speciesOf))
                }.all { it }
                if (!ok) Toast.makeText(context, enqueueError, Toast.LENGTH_SHORT).show()
            }
        },
        enabled = status == null || status == "FAILED",
        modifier = modifier.size(48.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(description),
            tint = if (status == "FAILED") MaterialTheme.colorScheme.error else SonarTheme.colors.trace,
        )
    }
}
