package com.oceanguard.ai.ui.screens.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.AnalysisMode

/** A download held back until the user has read the warning. */
data class PendingDownload(
    val model: CatalogModel,
    val plan: DownloadPlan,
    val freeBytes: Long,
)

/**
 * The one way to start a model download. It checks free space and the network first: with no
 * room or no connection it explains instead of starting, on mobile data it asks, and otherwise
 * it just starts. Anyone offering a download (Home, camera, Models & AI) goes through here.
 */
@Stable
class ModelDownloader internal constructor(private val app: OceanGuardApp) {

    /** What was asked last: the row that owns the progress and what Retry repeats. */
    var requested by mutableStateOf<CatalogModel?>(null)
        private set

    internal var pending by mutableStateOf<PendingDownload?>(null)
        private set

    fun request(mode: AnalysisMode) {
        CatalogModel.forMode(mode)?.let(::request)
    }

    fun request(model: CatalogModel) {
        requested = model
        val free = app.vlmModelManager.getModelDirectory().usableSpace
        val plan = planDownload(model.approxBytes, free, currentNetworkKind(app))
        if (plan == DownloadPlan.Proceed) start(model) else pending = PendingDownload(model, plan, free)
    }

    internal fun confirmPending() {
        val held = pending ?: return
        pending = null
        start(held.model)
    }

    internal fun dismissPending() {
        pending = null
    }

    private fun start(model: CatalogModel) {
        val tier = model.tier
        if (tier == null) app.launchSpeciesPackDownload() else app.launchVlmDownload(tier, model.variant)
    }

    /** Place it once in the screen that calls [request]. */
    @Composable
    fun PreflightDialog() {
        val held = pending ?: return
        when (val plan = held.plan) {
            is DownloadPlan.NoSpace -> NoSpaceDialog(held, plan, ::dismissPending)
            DownloadPlan.NoNetwork -> NoticeDialog(
                title = R.string.models_preflight_offline_title,
                body = stringResource(R.string.models_preflight_offline_body),
                onDismiss = ::dismissPending,
            )
            DownloadPlan.Metered -> MeteredDialog(held, ::confirmPending, ::dismissPending)
            DownloadPlan.Proceed -> Unit
        }
    }
}

@Composable
fun rememberModelDownloader(): ModelDownloader {
    val app = LocalContext.current.applicationContext as OceanGuardApp
    return remember(app) { ModelDownloader(app) }
}

@Composable
private fun NoSpaceDialog(held: PendingDownload, plan: DownloadPlan.NoSpace, onDismiss: () -> Unit) {
    NoticeDialog(
        title = R.string.models_preflight_space_title,
        body = stringResource(
            R.string.models_preflight_space_body,
            formatBytes(held.model.approxBytes),
            formatBytes(held.freeBytes),
            formatBytes(plan.missingBytes),
        ),
        onDismiss = onDismiss,
    )
}

@Composable
private fun NoticeDialog(title: Int, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } },
    )
}

@Composable
private fun MeteredDialog(held: PendingDownload, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.models_preflight_metered_title)) },
        text = {
            Text(stringResource(R.string.models_preflight_metered_body, formatBytes(held.model.approxBytes)))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.models_preflight_metered_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** Mobile data and metered Wi-Fi count as metered; no active network with internet is none. */
fun currentNetworkKind(context: Context): NetworkKind {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkKind.NONE
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
    return when {
        capabilities == null ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkKind.NONE
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetworkKind.UNMETERED
        else -> NetworkKind.METERED
    }
}
