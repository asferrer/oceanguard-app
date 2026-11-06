package com.oceanguard.ai.ui.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.data.collection.AchievementDef
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.AchievementUnlockOverlay
import com.oceanguard.ai.ui.components.OceanBottomBar
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.navigation.routes.cameraRoutes
import com.oceanguard.ai.ui.navigation.routes.dexRoutes
import com.oceanguard.ai.ui.navigation.routes.homeRoutes
import com.oceanguard.ai.ui.navigation.routes.mapRoutes
import com.oceanguard.ai.ui.navigation.routes.modelsRoutes
import com.oceanguard.ai.ui.navigation.routes.resultsRoutes
import com.oceanguard.ai.ui.screens.SpeciesViewModel

/** What the route files need. It is rebuilt only when the composition root is. */
class OceanNavContext(
    val app: OceanGuardApp,
    val navController: NavHostController,
    val viewModel: MainViewModel,
    val speciesViewModel: SpeciesViewModel,
)

/** App shell: bottom bar, navigation graph, shared transitions and overlays above everything. */
@Composable
fun OceanApp(
    app: OceanGuardApp,
    viewModel: MainViewModel,
    speciesViewModel: SpeciesViewModel,
    pendingDeepLink: MutableState<String?>,
) {
    val navController = rememberNavController()
    val route = navController.currentBackStackEntryAsState().value?.destination?.route
    val context = remember(navController) {
        OceanNavContext(app, navController, viewModel, speciesViewModel)
    }
    val cameraReveal = rememberCameraReveal()

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalCameraReveal provides cameraReveal) {
            Scaffold(
                contentWindowInsets = if (OceanRouteRules.isImmersive(route)) {
                    WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
                } else {
                    ScaffoldDefaults.contentWindowInsets
                },
                bottomBar = { OceanTabBar(navController, route) },
            ) { padding ->
                OceanNavHost(context, padding)
            }
        }
        DeepLinkHandler(navController, pendingDeepLink)
        CameraRevealOverlay(cameraReveal)
        AchievementOverlayHost(app)
    }
}

@Composable
private fun OceanTabBar(navController: NavHostController, route: String?) {
    if (!OceanRouteRules.isTab(route)) return
    OceanBottomBar(
        tabs = OCEAN_BOTTOM_TABS,
        currentRoute = OceanRoutes.base(route),
        onTabSelected = { target ->
            navController.navigate(target) {
                popUpTo(OceanRoutes.HOME) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun OceanNavHost(context: OceanNavContext, padding: PaddingValues) {
    val reduced by rememberUpdatedState(LocalReducedMotion.current)
    val transitions = remember { OceanTransitions { reduced } }
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalOceanSharedTransition provides this) {
            NavHost(
                navController = context.navController,
                startDestination = OceanRoutes.SPLASH,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                enterTransition = { transitions.enter(this) },
                exitTransition = { transitions.exit(this) },
                popEnterTransition = { transitions.popEnter(this) },
                popExitTransition = { transitions.popExit(this) },
            ) {
                homeRoutes(context)
                modelsRoutes(context)
                cameraRoutes(context)
                resultsRoutes(context)
                mapRoutes(context)
                dexRoutes(context)
            }
        }
    }
}

/** Notification taps. Waits for the graph: the splash to home step may still be running. */
@Composable
private fun DeepLinkHandler(navController: NavHostController, pending: MutableState<String?>) {
    val target by pending
    LaunchedEffect(target) {
        val route = target ?: return@LaunchedEffect
        pending.value = null
        if (route != OceanRoutes.HOME && route.isNotEmpty()) {
            navController.navigate(route) { launchSingleTop = true }
        }
    }
}

@Composable
private fun AchievementOverlayHost(app: OceanGuardApp) {
    var pendingUnlock by remember { mutableStateOf<AchievementDef?>(null) }
    LaunchedEffect(Unit) {
        app.achievementChecker.achievementUnlocked.collect { pendingUnlock = it }
    }
    AchievementUnlockOverlay(
        achievementDef = pendingUnlock,
        onDismiss = { pendingUnlock = null },
    )
}
