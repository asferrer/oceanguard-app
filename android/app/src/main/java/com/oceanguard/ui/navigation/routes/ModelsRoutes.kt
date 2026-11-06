package com.oceanguard.ai.ui.navigation.routes

import androidx.navigation.NavGraphBuilder
import com.oceanguard.ai.ui.navigation.OceanNavContext
import com.oceanguard.ai.ui.navigation.OceanRoutes
import com.oceanguard.ai.ui.navigation.oceanScreen
import com.oceanguard.ai.ui.screens.SettingsScreen
import com.oceanguard.ai.ui.screens.models.ModelsScreen

/** Routes of Settings and Models & AI (advanced options live inside it). */
object ModelsRoutes {
    /** Models & AI, opened from the first card in Settings. */
    const val MODELS = "models"
}

/** Settings, Models & AI and advanced options. */
fun NavGraphBuilder.modelsRoutes(context: OceanNavContext) {
    oceanScreen(OceanRoutes.SETTINGS) {
        SettingsScreen(navController = context.navController, viewModel = context.viewModel)
    }
    oceanScreen(ModelsRoutes.MODELS) {
        ModelsScreen(navController = context.navController, viewModel = context.viewModel)
    }
}
