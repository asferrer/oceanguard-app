package com.oceanguard.ai.ui.navigation.routes

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.oceanguard.ai.ui.navigation.OceanNavContext
import com.oceanguard.ai.ui.navigation.OceanRoutes
import com.oceanguard.ai.ui.navigation.oceanScreen
import com.oceanguard.ai.ui.screens.CameraResultScreen
import com.oceanguard.ai.ui.screens.CameraScreen
import com.oceanguard.ai.ui.screens.LiveDetectionScreen

/** Exact pattern of the camera destination: what `popUpTo` must name to find it for certain. */
internal const val CAMERA_ROUTE_PATTERN = "${OceanRoutes.CAMERA}?mode={mode}"

/** Camera, analysis with the signature moment and continuous scan. */
fun NavGraphBuilder.cameraRoutes(context: OceanNavContext) {
    val navController = context.navController

    oceanScreen(
        route = CAMERA_ROUTE_PATTERN,
        arguments = listOf(
            navArgument("mode") {
                type = NavType.StringType
                defaultValue = "debris"
            },
        ),
    ) { entry ->
        CameraScreen(
            navController = navController,
            viewModel = context.viewModel,
            speciesMode = entry.arguments?.getString("mode") == "species",
            onSpeciesCapture = { uri ->
                context.speciesViewModel.identifyFromImage(uri)
                navController.navigate(OceanRoutes.SPECIES_RESULT)
            },
        )
    }
    oceanScreen(OceanRoutes.CONTINUOUS_SCAN) {
        LiveDetectionScreen(navController = navController, viewModel = context.viewModel)
    }
    oceanScreen(OceanRoutes.CAMERA_RESULT) {
        CameraResultScreen(navController = navController, viewModel = context.viewModel)
    }
}
