package com.oceanguard.ai.ui.screens.home

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.inference.TextModelVariant
import com.oceanguard.ai.ui.components.spotlight.GuidedTourTransitionDialog
import com.oceanguard.ai.ui.components.spotlight.SpotlightController
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.utils.ModelUpdate
import com.oceanguard.ai.utils.UpdateInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Asks for location once, so photos picked from the gallery can carry GPS, and reports when the
 * answer is known. The tour waits for it: a system dialog must never cover the first stop.
 * If the answer never arrives the wait ends after [HomeLogic.LOCATION_WAIT_MS].
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberLocationResolved(): Boolean {
    var resolved by rememberSaveable { mutableStateOf(false) }
    val permissions = rememberMultiplePermissionsState(
        permissions = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
        onPermissionsResult = { resolved = true },
    )
    LaunchedEffect(Unit) {
        if (permissions.allPermissionsGranted) {
            resolved = true
            return@LaunchedEffect
        }
        permissions.launchMultiplePermissionRequest()
        delay(HomeLogic.LOCATION_WAIT_MS)
        resolved = true
    }
    return resolved
}

/** Starts the first-run tour once the layout has settled and the permission prompt is over. */
@Composable
fun HomeTourStart(controller: SpotlightController, tourComplete: Boolean, locationResolved: Boolean) {
    LaunchedEffect(tourComplete, locationResolved) {
        if (HomeLogic.shouldStartTour(tourComplete, locationResolved)) {
            delay(HomeLogic.TOUR_START_DELAY_MS.toLong())
            controller.start()
        }
    }
}

/** Overlay of the Home tour, and the hand-over dialog to the next stop of the guided tour. */
@Composable
fun HomeTourOverlay(
    app: OceanGuardApp,
    controller: SpotlightController,
    bounds: SnapshotStateMap<String, Rect>,
    scrollState: ScrollState,
    navController: NavController,
) {
    val scope = rememberCoroutineScope()
    val guidedTourActive by app.settingsRepository.guidedTourActive
        .collectAsStateWithLifecycle(initialValue = false)
    var showTransition by remember { mutableStateOf(false) }

    SpotlightOverlay(
        controller = controller,
        targetBounds = bounds,
        onComplete = {
            scope.launch {
                app.settingsRepository.markTourComplete(TourDefinitions.getScreenId(TourDefinitions.HOME))
                if (guidedTourActive) showTransition = true else app.launchDemoCleanupIfComplete()
            }
        },
        scrollState = scrollState,
        isGuidedTour = guidedTourActive,
        onSkipTutorial = {
            scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
            app.launchDemoCleanup()
        },
    )
    if (showTransition) {
        GuidedTourTransitionDialog(
            screenId = "home",
            onContinue = {
                showTransition = false
                navController.navigate("marinedex")
            },
            onSkipTutorial = {
                showTransition = false
                scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                app.launchDemoCleanup()
            },
        )
    }
}

/**
 * Checks for a newer app and a newer model in the background. Their dialogs wait while [allowed]
 * is false, so they never interrupt the tour. Nothing is lost: the answers are kept until then.
 */
@Composable
fun HomeUpdateDialogs(app: OceanGuardApp, allowed: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var appUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var modelUpdate by remember { mutableStateOf<ModelUpdate?>(null) }
    LaunchedEffect(Unit) { appUpdate = app.updateChecker.check() }
    LaunchedEffect(Unit) {
        app.vlmModelManager.refreshBestModelFromManifest(app.modelUpdateChecker)
        modelUpdate = app.modelUpdateChecker.check()
    }
    if (!allowed) return

    appUpdate?.let { info ->
        UpdateAvailableDialog(
            info = info,
            onDownload = {
                app.updateChecker.downloadApk(context, info)
                appUpdate = null
            },
            onDismiss = { appUpdate = null },
        )
    }
    modelUpdate?.let { update ->
        ModelUpdateAvailableDialog(
            update = update,
            onDownload = {
                modelUpdate = null
                app.launchVlmDownload(TextModelTier.GEMMA4_E2B, TextModelVariant.FINETUNED)
            },
            onSkip = {
                scope.launch { app.settingsRepository.setSkippedFinetunedVersion(update.version) }
                modelUpdate = null
            },
            onDismiss = { modelUpdate = null },
        )
    }
}
