package com.oceanguard.ai.ui.navigation.routes

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.inference.VlmModelManager
import com.oceanguard.ai.ui.navigation.OceanNavContext
import com.oceanguard.ai.ui.navigation.OceanRoutes
import com.oceanguard.ai.ui.navigation.oceanScreen
import com.oceanguard.ai.ui.screens.HomeScreen
import com.oceanguard.ai.ui.screens.OnboardingScreen
import com.oceanguard.ai.ui.screens.SplashScreen
import com.oceanguard.ai.ui.screens.onboarding.WelcomeLogic
import com.oceanguard.ai.ui.screens.onboarding.WelcomeResult
import kotlinx.coroutines.launch

/** Routes of the entry flow: splash, welcome and Home. */
fun NavGraphBuilder.homeRoutes(context: OceanNavContext) {
    entryRoutes(context)
    homeScreen(context)
    // Placeholder: queue status is shown via notification and the existing screens.
    oceanScreen("job_queue") {
        LaunchedEffect(Unit) {
            context.navController.navigate(OceanRoutes.HOME) {
                popUpTo("job_queue") { inclusive = true }
            }
        }
    }
}

private fun NavGraphBuilder.entryRoutes(context: OceanNavContext) {
    val app = context.app
    val navController = context.navController

    oceanScreen(OceanRoutes.SPLASH) {
        SplashScreen(
            settingsRepository = app.settingsRepository,
            onNavigateToHome = {
                navController.navigate(OceanRoutes.HOME) {
                    popUpTo(OceanRoutes.SPLASH) { inclusive = true }
                }
            },
            onNavigateToOnboarding = {
                navController.navigate(OceanRoutes.ONBOARDING) {
                    popUpTo(OceanRoutes.SPLASH) { inclusive = true }
                }
            },
        )
    }
    oceanScreen(OceanRoutes.ONBOARDING) {
        val scope = rememberCoroutineScope()
        OnboardingScreen(
            onComplete = { result ->
                scope.launch {
                    finishWelcome(app, result)
                    navController.navigate(OceanRoutes.HOME) {
                        popUpTo(OceanRoutes.ONBOARDING) { inclusive = true }
                    }
                }
            },
        )
    }
}

/**
 * Applies what was chosen in the welcome before Home opens, so Home never sees a half-written
 * state: the analysis mode, its download, and whether the guided tour runs. Declining the tour
 * marks every tour done and clears its demo data, as "Skip tutorial" does.
 */
private suspend fun finishWelcome(app: OceanGuardApp, result: WelcomeResult) {
    val plan = WelcomeLogic.plan(
        current = app.settingsRepository.getAnalysisModeSync(),
        result = result,
        isDownloaded = app::isModelDownloaded,
        finetunedPublished = VlmModelManager.FINETUNED_LITERTLM_PUBLISHED,
    )
    if (plan.applyMode) app.applyAnalysisMode(result.mode)
    plan.downloadMode?.let { app.launchVlmDownload(it.tier, it.variant) }
    if (plan.startTour) {
        app.settingsRepository.setGuidedTourActive(true)
    } else {
        app.settingsRepository.markAllGuidedToursComplete()
        app.launchDemoCleanup()
    }
    app.settingsRepository.setOnboardingComplete(true)
}

private fun NavGraphBuilder.homeScreen(context: OceanNavContext) {
    val app = context.app
    val navController = context.navController

    oceanScreen(OceanRoutes.HOME) {
        val dexEntries by app.collectionRepository.allDexEntries
            .collectAsStateWithLifecycle(initialValue = emptyList())
        val discoveredCount by app.collectionRepository.discoveredCount
            .collectAsStateWithLifecycle(initialValue = 0)
        val allAchievements by app.collectionRepository.allAchievements
            .collectAsStateWithLifecycle(initialValue = emptyList())
        val unlockedCount = remember(allAchievements) { allAchievements.count { it.unlockedAt > 0 } }
        HomeScreen(
            navController = navController,
            viewModel = context.viewModel,
            dexEntries = dexEntries,
            discoveredCount = discoveredCount,
            achievementsUnlocked = unlockedCount,
            onSpeciesBatchPicked = { uris ->
                context.speciesViewModel.identifyBatch(uris)
                navController.navigate("species_batch_result")
            },
        )
    }
}
