package com.oceanguard.ai.ui.screens.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.ui.components.spotlight.GuidedTourTransitionDialog
import com.oceanguard.ai.ui.components.spotlight.SpotlightController
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.navigation.OceanRoutes
import kotlinx.coroutines.launch

/**
 * Runs the history's spotlight tour and, inside the guided tour, the hop to the next stop: the
 * detail of the first analysis, or settings when there is none.
 */
@Composable
fun HistoryTourOverlay(
    navController: NavController,
    controller: SpotlightController,
    boundsMap: Map<String, Rect>,
    firstSessionId: Long?,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OceanGuardApp }
    val scope = rememberCoroutineScope()
    val screenId = TourDefinitions.getScreenId(TourDefinitions.HISTORY)
    val tourComplete by app.settingsRepository.isTourComplete(screenId)
        .collectAsStateWithLifecycle(initialValue = true)
    val guidedTourActive by app.settingsRepository.guidedTourActive
        .collectAsStateWithLifecycle(initialValue = false)
    var showTransitionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(tourComplete) {
        if (!tourComplete) controller.start()
    }

    SpotlightOverlay(
        controller = controller,
        targetBounds = boundsMap,
        onComplete = {
            scope.launch {
                app.settingsRepository.markTourComplete(screenId)
                if (guidedTourActive) showTransitionDialog = true else app.launchDemoCleanupIfComplete()
            }
        },
        isGuidedTour = guidedTourActive,
        onSkipTutorial = {
            scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
            app.launchDemoCleanup()
        },
    )

    if (showTransitionDialog) {
        HistoryTourHop(
            navController = navController,
            firstSessionId = firstSessionId,
            onDone = { showTransitionDialog = false },
            onSkip = {
                scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                app.launchDemoCleanup()
            },
        )
    }
}

/** Guided tour only: offers the next stop, or to end the tour. */
@Composable
private fun HistoryTourHop(
    navController: NavController,
    firstSessionId: Long?,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    GuidedTourTransitionDialog(
        screenId = "history",
        onContinue = {
            onDone()
            if (firstSessionId != null) {
                navController.navigate("session/$firstSessionId")
            } else {
                navController.navigate(OceanRoutes.SETTINGS) {
                    popUpTo(OceanRoutes.HOME) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        },
        onSkipTutorial = {
            onDone()
            onSkip()
        },
    )
}
