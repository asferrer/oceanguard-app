package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.spotlight.GuidedTourTransitionDialog
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.navigation.routes.ModelsRoutes
import com.oceanguard.ai.ui.screens.models.AboutBlock
import com.oceanguard.ai.ui.screens.models.GeneralGroup
import com.oceanguard.ai.ui.screens.models.ModelsEntryCard
import com.oceanguard.ai.ui.screens.models.PrivacyGroup
import com.oceanguard.ai.ui.screens.models.ReportsGroup
import com.oceanguard.ai.ui.screens.models.SettingsGroup
import com.oceanguard.ai.ui.screens.models.ToursBlock
import kotlinx.coroutines.launch

private val SCREEN_PADDING = 16.dp
private val GROUP_GAP = 24.dp

/**
 * Settings: Models & AI first, then General, Reports, Privacy and More. The guided tour keeps its
 * six stops: the targets moved with the settings they point at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: MainViewModel,
) {
    val app = LocalContext.current.applicationContext as OceanGuardApp
    val scroll = rememberScrollState()
    val bounds = rememberSpotlightBounds()
    val tour = rememberSpotlightController(TourDefinitions.SETTINGS)
    val tourId = TourDefinitions.getScreenId(TourDefinitions.SETTINGS)
    val tourComplete by app.settingsRepository.isTourComplete(tourId).collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(tourComplete) { if (!tourComplete) tour.start() }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            SettingsBody(app, viewModel, navController, bounds, scroll, tour.isActive, Modifier.padding(padding))
        }
        SettingsTour(app, navController, tour, bounds, scroll, tourId)
    }
}

@Composable
private fun SettingsBody(
    app: OceanGuardApp,
    viewModel: MainViewModel,
    navController: NavController,
    bounds: androidx.compose.runtime.snapshots.SnapshotStateMap<String, androidx.compose.ui.geometry.Rect>,
    scroll: androidx.compose.foundation.ScrollState,
    scrollLocked: Boolean,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val advanced by app.settingsRepository.devModeEnabled.collectAsStateWithLifecycle(initialValue = false)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll, enabled = !scrollLocked)
            .padding(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
    ) {
        ModelsEntryCard(
            app = app,
            onOpen = { navController.navigate(ModelsRoutes.MODELS) },
            modifier = Modifier.spotlightTarget("settings_confidence", bounds),
        )
        Column(Modifier.spotlightTarget("settings_language", bounds), verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
            GeneralGroup(app, scope, bounds)
            ReportsGroup(app, scope)
        }
        PrivacyGroup(app, viewModel, scope, bounds)
        MoreGroup(app, navController, advanced, bounds)
    }
}

@Composable
private fun MoreGroup(
    app: OceanGuardApp,
    navController: NavController,
    advanced: Boolean,
    bounds: androidx.compose.runtime.snapshots.SnapshotStateMap<String, androidx.compose.ui.geometry.Rect>,
) {
    val scope = rememberCoroutineScope()
    SettingsGroup(stringResource(R.string.settings_group_more)) {
        ToursBlock(
            onReplay = {
                app.launchTourReset()
                navController.navigate("home") { popUpTo("home") { inclusive = true } }
            },
            modifier = Modifier.spotlightTarget("settings_tours", bounds),
        )
        AboutBlock(
            advancedEnabled = advanced,
            onActivate = { scope.launch { app.settingsRepository.setDevModeEnabled(true) } },
            onDeactivate = { scope.launch { app.settingsRepository.setDevModeEnabled(false) } },
        )
    }
}

/** The spotlight overlay and the dialog that closes a guided tour, as they were. */
@Composable
private fun SettingsTour(
    app: OceanGuardApp,
    navController: NavController,
    tour: com.oceanguard.ai.ui.components.spotlight.SpotlightController,
    bounds: androidx.compose.runtime.snapshots.SnapshotStateMap<String, androidx.compose.ui.geometry.Rect>,
    scroll: androidx.compose.foundation.ScrollState,
    tourId: String,
) {
    val scope = rememberCoroutineScope()
    val guided by app.settingsRepository.guidedTourActive.collectAsStateWithLifecycle(initialValue = false)
    var transition by remember { mutableStateOf(false) }
    SpotlightOverlay(
        controller = tour,
        targetBounds = bounds,
        onComplete = {
            scope.launch {
                app.settingsRepository.markTourComplete(tourId)
                if (guided) transition = true else app.launchDemoCleanupIfComplete()
            }
        },
        scrollState = scroll,
        isGuidedTour = guided,
        onSkipTutorial = {
            scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
            app.launchDemoCleanup()
        },
    )
    if (transition) {
        GuidedTourTransitionDialog(
            screenId = "settings",
            onContinue = {
                transition = false
                scope.launch {
                    app.achievementChecker.checkTutorialComplete()
                    app.settingsRepository.setGuidedTourActive(false)
                }
                app.launchDemoCleanup()
                navController.navigate("home") { popUpTo("home") { inclusive = true } }
            },
            onSkipTutorial = {
                transition = false
                scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                app.launchDemoCleanup()
            },
        )
    }
}
