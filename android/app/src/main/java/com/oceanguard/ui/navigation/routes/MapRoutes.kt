package com.oceanguard.ai.ui.navigation.routes

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.oceanguard.ai.ui.navigation.OceanNavContext
import com.oceanguard.ai.ui.navigation.OceanRoutes
import com.oceanguard.ai.ui.navigation.oceanScreen
import com.oceanguard.ai.ui.screens.HistoryScreen
import com.oceanguard.ai.ui.screens.MapScreen

/** Routes of the map and history screens. */
fun NavGraphBuilder.mapRoutes(context: OceanNavContext) {
    oceanScreen(OceanRoutes.HISTORY) {
        HistoryScreen(navController = context.navController, viewModel = context.viewModel)
    }
    oceanScreen(
        route = "${OceanRoutes.MAP}?lat={lat}&lon={lon}",
        arguments = listOf(
            navArgument("lat") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("lon") { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) { entry ->
        MapScreen(
            navController = context.navController,
            viewModel = context.viewModel,
            focusLat = entry.arguments?.getString("lat")?.toDoubleOrNull(),
            focusLon = entry.arguments?.getString("lon")?.toDoubleOrNull(),
        )
    }
}
