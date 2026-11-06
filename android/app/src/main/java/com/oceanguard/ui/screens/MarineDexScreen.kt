package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.EnvironmentalImpact
import com.oceanguard.ai.data.collection.CollectionRepository
import com.oceanguard.ai.data.collection.MarineDexEntry
import com.oceanguard.ai.ui.components.DexSpriteImage
import com.oceanguard.ai.ui.components.dex.DexCard
import com.oceanguard.ai.ui.components.dex.DexCollectionHeader
import com.oceanguard.ai.ui.components.dex.DexItem
import com.oceanguard.ai.ui.components.dex.DexSpriteSlot
import com.oceanguard.ai.ui.components.dex.debrisDisplayName
import com.oceanguard.ai.ui.components.spotlight.GuidedTourTransitionDialog
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.navigation.OceanSharedKeys
import com.oceanguard.ai.ui.navigation.oceanSharedElement
import com.oceanguard.ai.ui.theme.SonarTheme
import com.oceanguard.ai.ui.theme.healthScoreColor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter

private val TOTAL_DEX_ENTRIES = DebrisType.CANONICAL.size

/**
 * MarineDex: collection of the debris types the detector knows. A discovered entry shows its
 * sprite, name, count and risk dot; an undiscovered one shows the silhouette of the sprite and a
 * "?". The sprite of a discovered entry flies into the detail header. A Konfetti burst
 * celebrates the full collection, except under "Remove animations".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarineDexScreen(
    collectionRepository: CollectionRepository,
    onNavigateToDetail: (debrisType: String) -> Unit,
    onNavigateBack: () -> Unit,
    navController: NavController? = null,
) {
    val dexEntries by collectionRepository.allDexEntries.collectAsStateWithLifecycle(emptyList())
    val discoveredCount by collectionRepository.discoveredCount.collectAsStateWithLifecycle(0)
    val favoriteCount by collectionRepository.favoriteCount.collectAsStateWithLifecycle(0)
    val totalDetections by collectionRepository.totalDetections.collectAsStateWithLifecycle(0)

    val dexContext = LocalContext.current
    val app = remember(dexContext) { dexContext.applicationContext as OceanGuardApp }
    val dexScope = rememberCoroutineScope()
    val boundsMap = rememberSpotlightBounds()
    val tourController = rememberSpotlightController(TourDefinitions.MARINEDEX)
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.MARINEDEX))
        .collectAsStateWithLifecycle(initialValue = true)
    val guidedTourActive by app.settingsRepository.guidedTourActive
        .collectAsStateWithLifecycle(initialValue = false)
    var showTransitionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(tourComplete) {
        if (!tourComplete) tourController.start()
    }

    val discoveredTypes = remember(dexEntries) { dexEntries.associateBy { it.debrisType } }
    val allComplete = discoveredCount == TOTAL_DEX_ENTRIES

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { MarineDexTopBar(onNavigateBack) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize()) {
                MarineDexGrid(
                    discoveredTypes = discoveredTypes,
                    discoveredCount = discoveredCount,
                    favoriteCount = favoriteCount,
                    totalDetections = totalDetections,
                    onNavigateToDetail = onNavigateToDetail,
                    boundsMap = boundsMap,
                    modifier = Modifier.padding(paddingValues),
                )
                if (allComplete && !LocalReducedMotion.current) {
                    KonfettiView(modifier = Modifier.fillMaxSize(), parties = buildConfettiParties())
                }
            }
        }

        SpotlightOverlay(
            controller = tourController,
            targetBounds = boundsMap,
            onComplete = {
                dexScope.launch {
                    app.settingsRepository.markTourComplete(
                        TourDefinitions.getScreenId(TourDefinitions.MARINEDEX)
                    )
                    if (guidedTourActive) {
                        showTransitionDialog = true
                    } else {
                        app.launchDemoCleanupIfComplete()
                    }
                }
            },
            isGuidedTour = guidedTourActive,
            onSkipTutorial = {
                dexScope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                app.launchDemoCleanup()
            },
        )

        if (showTransitionDialog) {
            GuidedTourTransitionDialog(
                screenId = "marinedex",
                onContinue = {
                    showTransitionDialog = false
                    navController?.navigate("marinedex/OTHER")
                },
                onSkipTutorial = {
                    showTransitionDialog = false
                    dexScope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                    app.launchDemoCleanup()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarineDexTopBar(onNavigateBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.marinedex_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.marinedex_cd_go_back),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

// ---------------------------------------------------------------------------
// Grid: header plus one card per canonical debris type
// ---------------------------------------------------------------------------

@Composable
private fun MarineDexGrid(
    discoveredTypes: Map<String, MarineDexEntry>,
    discoveredCount: Int,
    favoriteCount: Int,
    totalDetections: Int,
    onNavigateToDetail: (String) -> Unit,
    boundsMap: MutableMap<String, androidx.compose.ui.geometry.Rect>,
    modifier: Modifier = Modifier,
) {
    val complete = discoveredCount == TOTAL_DEX_ENTRIES
    val caption = if (complete) {
        stringResource(R.string.marinedex_collection_complete)
    } else {
        stringResource(R.string.dex_progress_caption, discoveredCount, TOTAL_DEX_ENTRIES)
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(3) }) {
            DexCollectionHeader(
                discoveredCount = discoveredCount,
                totalCount = TOTAL_DEX_ENTRIES,
                favoriteCount = favoriteCount,
                observationCount = totalDetections,
                caption = caption,
                discoveredLabel = stringResource(R.string.marinedex_stat_discovered),
                favoritesLabel = stringResource(R.string.marinedex_stat_favorites),
                observationsLabel = stringResource(R.string.marinedex_stat_detections),
                modifier = Modifier.spotlightTarget("dex_progress", boundsMap),
            )
        }
        // Extended types (PLASTIC_BAG, STYROFOAM, ...) collapse to their canonical parent in
        // AchievementChecker, so only the canonical ones ever reach the dex table.
        itemsIndexed(items = DebrisType.CANONICAL, key = { _, type -> type.name }) { index, type ->
            MarineDexCard(
                type = type,
                entry = discoveredTypes[type.name],
                index = index,
                onNavigateToDetail = onNavigateToDetail,
                modifier = if (index == 0) Modifier.spotlightTarget("dex_grid", boundsMap) else Modifier,
            )
        }
    }
}

@Composable
private fun MarineDexCard(
    type: DebrisType,
    entry: MarineDexEntry?,
    index: Int,
    onNavigateToDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = DexItem(
        key = type.name,
        displayName = debrisDisplayName(type),
        spritePath = null,
        discovered = entry != null,
        count = entry?.timesDetected ?: 0,
        isFavorite = entry?.isFavorite ?: false,
    )
    val sprite: DexSpriteSlot = { spriteModifier, size, silhouette ->
        DexSpriteImage(
            debrisType = type,
            size = size,
            silhouette = silhouette,
            silhouetteColor = SonarTheme.colors.lineStrong,
            modifier = if (silhouette) {
                spriteModifier
            } else {
                spriteModifier.oceanSharedElement(OceanSharedKeys.dexSprite(type.name))
            },
        )
    }
    val riskScore = EnvironmentalImpact.IMPACT_MAP[type]?.riskScore ?: 0
    val riskColor = healthScoreColor(100 - (riskScore * 10).coerceAtMost(100))
    DexCard(
        item = item,
        animationIndex = index,
        onNavigateToDetail = onNavigateToDetail,
        modifier = modifier,
        sprite = sprite,
        badge = {
            Box(modifier = Modifier.size(8.dp).background(riskColor, CircleShape))
        },
    )
}

// ---------------------------------------------------------------------------
// Konfetti: celebrates a full collection
// ---------------------------------------------------------------------------

private fun buildConfettiParties(): List<Party> {
    val emitter = Emitter(duration = 3, timeUnit = TimeUnit.SECONDS).perSecond(80)
    return listOf(
        Party(emitter = emitter, position = Position.Relative(0.0, 0.0), angle = 135, spread = 60),
        Party(emitter = emitter, position = Position.Relative(1.0, 0.0), angle = 45, spread = 60),
    )
}
