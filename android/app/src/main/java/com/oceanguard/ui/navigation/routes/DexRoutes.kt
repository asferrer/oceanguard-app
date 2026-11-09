package com.oceanguard.ai.ui.navigation.routes

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.ui.navigation.OceanNavContext
import com.oceanguard.ai.ui.navigation.OceanRoutes
import com.oceanguard.ai.ui.navigation.oceanScreen
import com.oceanguard.ai.ui.screens.AchievementsScreen
import com.oceanguard.ai.ui.screens.MarineDexDetailScreen
import com.oceanguard.ai.ui.screens.MarineDexScreen
import com.oceanguard.ai.ui.screens.SpeciesAnalysisDetailScreen
import com.oceanguard.ai.ui.screens.SpeciesBatchResultScreen
import com.oceanguard.ai.ui.screens.history.SpeciesHistoryGrouping
import com.oceanguard.ai.ui.screens.SpeciesBatchUiState
import com.oceanguard.ai.ui.screens.SpeciesDexDetailScreen
import com.oceanguard.ai.ui.screens.SpeciesDexScreen
import com.oceanguard.ai.ui.screens.SpeciesObservationDetailScreen
import com.oceanguard.ai.ui.screens.SpeciesResultScreen
import com.oceanguard.ai.ui.theme.AchievementsTheme

private fun tabArgument() = navArgument("tab") {
    type = NavType.IntType
    defaultValue = 0
}

/** MarineDex, BioDex, species results and achievements. */
fun NavGraphBuilder.dexRoutes(context: OceanNavContext) {
    marineDexRoutes(context)
    bioDexRoutes(context)
    speciesResultRoutes(context)
    oceanScreen("achievements") {
        AchievementsTheme {
            AchievementsScreen(
                collectionRepository = context.app.collectionRepository,
                onNavigateBack = { context.navController.popBackStack() },
                navController = context.navController,
            )
        }
    }
}

private fun NavGraphBuilder.marineDexRoutes(context: OceanNavContext) {
    val navController = context.navController
    val repository = context.app.collectionRepository

    oceanScreen("marinedex") {
        MarineDexScreen(
            collectionRepository = repository,
            onNavigateToDetail = { type -> navController.navigate("marinedex/$type") },
            onNavigateBack = { navController.popBackStack() },
            navController = navController,
        )
    }
    oceanScreen(
        route = "marinedex/{debrisType}?tab={tab}",
        arguments = listOf(navArgument("debrisType") { type = NavType.StringType }, tabArgument()),
    ) { entry ->
        val debrisType = entry.arguments?.getString("debrisType") ?: return@oceanScreen
        MarineDexDetailScreen(
            debrisType = debrisType,
            // 0 = Info (default), 1 = Gallery, 2 = Map. Home's TopDebrisTypesCard links to tab=1.
            initialTab = entry.arguments?.getInt("tab") ?: 0,
            collectionRepository = repository,
            onNavigateBack = { navController.popBackStack() },
            onNavigateToSession = { sessionId -> navController.navigate("session/$sessionId") },
            navController = navController,
        )
    }
}

private fun NavGraphBuilder.bioDexRoutes(context: OceanNavContext) {
    val app = context.app
    val navController = context.navController

    oceanScreen("biodex") {
        // Pre-warm the ONNX species encoder while the user browses, so the first identify is fast.
        LaunchedEffect(Unit) { app.prewarmSpeciesEncoder() }
        SpeciesDexScreen(
            repo = app.speciesCollectionRepository,
            catalog = app.speciesCatalog,
            onNavigateToDetail = { key -> navController.navigate("biodex/$key") },
            onNavigateBack = { navController.popBackStack() },
            onDownloadPack = { app.launchSpeciesPackDownload() },
        )
    }
    oceanScreen(
        route = "biodex/{speciesKey}?tab={tab}",
        arguments = listOf(navArgument("speciesKey") { type = NavType.StringType }, tabArgument()),
    ) { entry ->
        val speciesKey = entry.arguments?.getString("speciesKey") ?: return@oceanScreen
        SpeciesDexDetailScreen(
            speciesKey = speciesKey,
            repo = app.speciesCollectionRepository,
            catalog = app.speciesCatalog,
            onNavigateBack = { navController.popBackStack() },
            onObservationClick = { id -> navController.navigate("species_observation/$id") },
            // 0 = Info (default), 1 = Gallery, 2 = Map.
            initialTab = entry.arguments?.getInt("tab") ?: 0,
            enrichmentProvider = { aphiaId, name ->
                app.speciesEnrichmentRepository.enrich(aphiaId, name)
            },
        )
    }
    oceanScreen(
        route = "species_analysis/{key}",
        arguments = listOf(navArgument("key") { type = NavType.StringType }),
    ) { entry ->
        val key = entry.arguments?.getString("key") ?: return@oceanScreen
        val all by app.database.speciesObservationDao().getAll()
            .collectAsStateWithLifecycle(initialValue = emptyList())
        val rows = remember(all, key) { SpeciesHistoryGrouping.observationsOf(key, all) }
        SpeciesAnalysisDetailScreen(
            observations = rows,
            catalog = app.speciesCatalog,
            onNavigateBack = { navController.popBackStack() },
            onOpenSpecies = { navController.navigate("biodex/$it") },
            onOpenObservation = { navController.navigate("species_observation/$it") },
        )
    }
    oceanScreen(
        route = "species_observation/{observationId}",
        arguments = listOf(navArgument("observationId") { type = NavType.LongType }),
    ) { entry ->
        val observationId = entry.arguments?.getLong("observationId") ?: return@oceanScreen
        val observation by produceState<SpeciesObservation?>(null, observationId) {
            value = app.speciesCollectionRepository.getObservationById(observationId)
        }
        observation?.let {
            SpeciesObservationDetailScreen(
                observation = it,
                catalog = app.speciesCatalog,
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}

private fun NavGraphBuilder.speciesResultRoutes(context: OceanNavContext) {
    val navController = context.navController
    val speciesViewModel = context.speciesViewModel
    val catalog = context.app.speciesCatalog

    oceanScreen(OceanRoutes.SPECIES_RESULT) {
        SpeciesResultScreen(
            navController = navController,
            viewModel = speciesViewModel,
            catalog = catalog,
        )
    }
    oceanScreen("species_batch_result") {
        val batchState by speciesViewModel.batchUiState.collectAsStateWithLifecycle()
        val uriList = when (val state = batchState) {
            is SpeciesBatchUiState.Running -> state.items.map { it.uri }
            is SpeciesBatchUiState.Complete -> state.items.map { it.uri }
            else -> emptyList()
        }
        SpeciesBatchResultScreen(
            navController = navController,
            viewModel = speciesViewModel,
            catalog = catalog,
            uriList = uriList,
        )
    }
}
