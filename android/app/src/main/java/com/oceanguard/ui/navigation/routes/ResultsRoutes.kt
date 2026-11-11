package com.oceanguard.ai.ui.navigation.routes

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.oceanguard.ai.service.InferenceServiceState
import com.oceanguard.ai.ui.navigation.OceanNavContext
import com.oceanguard.ai.ui.navigation.OceanRoutes
import com.oceanguard.ai.ui.navigation.oceanScreen
import com.oceanguard.ai.ui.screens.BatchResultsScreen
import com.oceanguard.ai.ui.screens.LocationPickerScreen
import com.oceanguard.ai.ui.screens.ReportScreen
import com.oceanguard.ai.ui.screens.ResultsScreen
import com.oceanguard.ai.ui.screens.SessionDetailScreen
import com.oceanguard.ai.ui.screens.VideoDetailScreen
import com.oceanguard.ai.ui.screens.VideoResultsScreen

private fun nullableString(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

/** Batch, results, session detail, reports and export. */
fun NavGraphBuilder.resultsRoutes(context: OceanNavContext) {
    val navController = context.navController
    val viewModel = context.viewModel

    oceanScreen("results") {
        ResultsScreen(navController = navController, viewModel = viewModel)
    }
    oceanScreen(
        route = "${OceanRoutes.REPORTS}?lat={lat}&lon={lon}&name={name}",
        arguments = listOf(nullableString("lat"), nullableString("lon"), nullableString("name")),
    ) { entry ->
        ReportScreen(
            navController = navController,
            viewModel = viewModel,
            preselectedLat = entry.arguments?.getString("lat")?.toDoubleOrNull(),
            preselectedLon = entry.arguments?.getString("lon")?.toDoubleOrNull(),
            preselectedName = entry.arguments?.getString("name"),
        )
    }
    oceanScreen("batch") {
        val batchUris by viewModel.batchUris.collectAsStateWithLifecycle()
        val service by context.app.inferenceServiceState.collectAsStateWithLifecycle()
        // Opened from the notification after the activity was recreated: the photos come from the running batch.
        val uris = batchUris.ifEmpty {
            when (val s = service) {
                is InferenceServiceState.BatchRunning -> s.allUris
                is InferenceServiceState.BatchComplete -> s.allUris
                else -> emptyList()
            }
        }
        BatchResultsScreen(navController = navController, viewModel = viewModel, uriList = uris)
    }
    sessionRoutes(context)
    videoRoutes(context)
}

private fun NavGraphBuilder.sessionRoutes(context: OceanNavContext) {
    val navController = context.navController
    val sessionArgument = listOf(navArgument("sessionId") { type = NavType.LongType })

    oceanScreen("session/{sessionId}", sessionArgument) { entry ->
        val sessionId = entry.arguments?.getLong("sessionId") ?: return@oceanScreen
        SessionDetailScreen(
            navController = navController,
            viewModel = context.viewModel,
            sessionId = sessionId,
            onNavigateToLocationPicker = { id -> navController.navigate("location_picker/$id") },
        )
    }
    oceanScreen("location_picker/{sessionId}", sessionArgument) { entry ->
        val sessionId = entry.arguments?.getLong("sessionId") ?: return@oceanScreen
        LocationPickerScreen(
            sessionId = sessionId,
            onNavigateBack = { navController.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.videoRoutes(context: OceanNavContext) {
    val navController = context.navController

    oceanScreen("video_results") {
        VideoResultsScreen(navController = navController, viewModel = context.viewModel)
    }
    oceanScreen(
        route = "video_detail/{analysisId}",
        arguments = listOf(navArgument("analysisId") { type = NavType.LongType }),
    ) { entry ->
        val analysisId = entry.arguments?.getLong("analysisId") ?: return@oceanScreen
        VideoDetailScreen(
            navController = navController,
            viewModel = context.viewModel,
            analysisId = analysisId,
        )
    }
}
