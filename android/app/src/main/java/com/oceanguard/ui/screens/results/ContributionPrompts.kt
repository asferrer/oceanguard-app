package com.oceanguard.ai.ui.screens.results

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.MainViewModel.ContributePromptState
import com.oceanguard.ai.ui.components.ContributeBottomSheet
import kotlinx.coroutines.launch

/**
 * Everything about offering the finished photos for research: the upload result message, the
 * "queued" message with its upload-now action, and the sheet that asks first.
 * Shared by the single result and the batch, which prompt in exactly the same way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributionPrompts(
    viewModel: MainViewModel,
    app: OceanGuardApp,
    snackbarHostState: SnackbarHostState,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val uploadResult by app.contributionUploadResult.collectAsStateWithLifecycle()
    val prompt by viewModel.contributePrompt.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }

    LaunchedEffect(uploadResult) {
        val result = uploadResult ?: return@LaunchedEffect
        val message = when {
            result.hadFailures && result.uploadedCount > 0 ->
                resources.getString(R.string.contribute_upload_partial, result.uploadedCount)
            result.hadFailures -> resources.getString(R.string.contribute_upload_failed)
            result.uploadedCount > 0 ->
                resources.getString(R.string.contribute_upload_success, result.uploadedCount)
            else -> null
        }
        message?.let { snackbarHostState.showSnackbar(it) }
        app.contributionUploadResult.value = null
    }

    LaunchedEffect(prompt) {
        when (val current = prompt) {
            is ContributePromptState.ShowPrompt -> showSheet = true
            is ContributePromptState.ShowInfo -> {
                val result = snackbarHostState.showSnackbar(
                    message = resources.getString(R.string.contribute_queued_message, current.count),
                    actionLabel = resources.getString(R.string.contribute_upload_now_btn),
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    scope.launch { app.contributionRepository.scheduleImmediateUpload() }
                }
                viewModel.dismissContributePrompt()
            }
            else -> Unit
        }
    }

    if (showSheet) {
        ContributionSheet(viewModel, (prompt as? ContributePromptState.ShowPrompt)?.sessionIds.orEmpty()) {
            showSheet = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContributionSheet(viewModel: MainViewModel, sessionIds: List<Long>, close: () -> Unit) {
    val declined = {
        close()
        viewModel.declineContributePrompt()
    }
    ContributeBottomSheet(
        imageCount = sessionIds.size,
        onUploadOnWifi = {
            close()
            viewModel.enqueueSessions(sessionIds, wifiOnly = true)
        },
        onUploadNow = {
            close()
            viewModel.enqueueSessions(sessionIds, wifiOnly = false)
        },
        onNotNow = declined,
        onDontAskAgain = {
            close()
            viewModel.setContributeNeverPrompt()
        },
        onDismiss = declined,
    )
}
