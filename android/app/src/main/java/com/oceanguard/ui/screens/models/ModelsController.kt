package com.oceanguard.ai.ui.screens.models

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.inference.VlmDownloadState
import com.oceanguard.ai.inference.VlmModelManager
import com.oceanguard.ai.ui.components.sonar.UndoableDeletes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the Models & AI screen does. It owns no files and no engines: choosing a mode goes through
 * `OceanGuardApp.applyAnalysisMode`, downloads through [ModelDownloader] and deletions through
 * `OceanGuardApp.deleteModelFile`.
 */
@Stable
class ModelsController(
    val app: OceanGuardApp,
    private val context: Context,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    val downloader: ModelDownloader,
) {
    private val manager get() = app.vlmModelManager

    /** Changes when the installed files change, so lists are read again. */
    var revision by mutableIntStateOf(0)
        private set

    /** The mode chosen while its model was missing: applied once the download finishes. */
    var pendingMode by mutableStateOf<AnalysisMode?>(null)
        private set

    fun select(mode: AnalysisMode) {
        pendingMode = null
        scope.launch { app.applyAnalysisMode(mode) }
    }

    fun download(mode: AnalysisMode) {
        pendingMode = mode
        downloader.request(mode)
    }

    fun download(model: CatalogModel) = downloader.request(model)

    fun cancelDownload() {
        pendingMode = null
        manager.cancelDownload()
    }

    /** Clears the error and asks again for the same model, still waiting for the same mode. */
    fun retry() {
        manager.cancelDownload()
        downloader.requested?.let(downloader::request)
    }

    fun dismissError() = cancelDownload()

    /** Called when the download state changes: reread the files and apply the waiting mode. */
    suspend fun onDownloadState(state: VlmDownloadState) {
        if (state !is VlmDownloadState.Complete) return
        revision++
        val waiting = pendingMode ?: return
        if (shouldApplyPending(waiting, app::isModelDownloaded, VlmModelManager.FINETUNED_LITERTLM_PUBLISHED)) {
            pendingMode = null
            app.applyAnalysisMode(waiting)
        }
    }

    /** Starts the undo window, unless the model is being used right now. */
    fun requestDelete(model: CatalogModel, deletes: UndoableDeletes<CatalogModel>) {
        if (app.isModelBusy()) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.models_delete_busy)) }
        } else {
            deletes.request(model)
        }
    }

    /** Runs when the undo window closes without Undo. */
    fun commitDelete(model: CatalogModel) {
        val tier = model.tier ?: return
        app.deleteModelFile(tier, model.variant)
        revision++
    }
}

@Composable
fun rememberModelsController(snackbar: SnackbarHostState): ModelsController {
    val context = LocalContext.current
    val app = context.applicationContext as OceanGuardApp
    val scope = rememberCoroutineScope()
    val downloader = rememberModelDownloader()
    return remember(app, snackbar) { ModelsController(app, context.applicationContext, scope, snackbar, downloader) }
}
