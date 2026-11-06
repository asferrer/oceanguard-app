package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DebrisImpactInfo
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.EnvironmentalImpact
import com.oceanguard.ai.data.collection.CollectionRepository
import com.oceanguard.ai.data.collection.MarineDexEntry
import com.oceanguard.ai.ui.components.DexSpriteImage
import com.oceanguard.ai.ui.components.dex.DexHero
import com.oceanguard.ai.ui.components.dex.DexTabContent
import com.oceanguard.ai.ui.components.dex.debrisDisplayName
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.components.spotlight.GuidedTourTransitionDialog
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.navigation.OceanSharedKeys
import com.oceanguard.ai.ui.navigation.oceanSharedElement
import com.oceanguard.ai.ui.theme.SonarTheme
import com.oceanguard.ai.ui.theme.healthScoreColor
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

/**
 * Detail of one debris type: hero with the sprite that flew in from the grid and the favourite
 * heart, then the Info, Gallery and Map tabs. Tapping a photo in the gallery or a marker on the
 * map opens that analysis through [onNavigateToSession].
 *
 * @param initialTab Tab to land on: 0 = Info (default), 1 = Gallery, 2 = Map. Home's
 *                   top-debris card passes 1 to land on the gallery of that type.
 */
@Composable
fun MarineDexDetailScreen(
    debrisType: String,
    collectionRepository: CollectionRepository,
    onNavigateBack: () -> Unit,
    onNavigateToSession: (Long) -> Unit = {},
    navController: NavController? = null,
    initialTab: Int = 0,
) {
    val allEntries by collectionRepository.allDexEntries.collectAsStateWithLifecycle(emptyList())
    val entry = allEntries.firstOrNull { it.debrisType == debrisType }
    val parsedType = remember(debrisType) { DebrisType.fromString(debrisType) }
    val impact = remember(parsedType) { EnvironmentalImpact.getImpact(parsedType) }

    val app = LocalContext.current.applicationContext as OceanGuardApp
    val sessions by app.repository.getSessionsByDebrisType(parsedType)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val scope = rememberCoroutineScope()
    val boundsMap = rememberSpotlightBounds()
    val tourController = rememberSpotlightController(TourDefinitions.MARINEDEX_DETAIL)
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.MARINEDEX_DETAIL))
        .collectAsStateWithLifecycle(initialValue = true)
    val guidedTourActive by app.settingsRepository.guidedTourActive
        .collectAsStateWithLifecycle(initialValue = false)
    var showTransitionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(tourComplete) {
        if (!tourComplete) tourController.start()
    }

    // Seeded with [initialTab] so a deep link lands on the requested tab; taps overwrite it.
    var selectedTab by remember { mutableIntStateOf(initialTab.coerceIn(0, 2)) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            DexHero(
                isFavorite = entry?.isFavorite ?: false,
                onNavigateBack = onNavigateBack,
                onToggleFavorite = { scope.launch { collectionRepository.toggleFavorite(debrisType) } },
                backDescription = stringResource(R.string.marinedex_detail_cd_go_back),
                favoriteDescription = stringResource(
                    if (entry?.isFavorite == true) {
                        R.string.marinedex_detail_cd_remove_favorite
                    } else {
                        R.string.marinedex_detail_cd_add_favorite
                    },
                ),
                modifier = Modifier.spotlightTarget("dex_detail_sprite", boundsMap),
            ) { spriteModifier, size ->
                DexSpriteImage(
                    debrisType = parsedType,
                    size = size,
                    modifier = spriteModifier.oceanSharedElement(OceanSharedKeys.dexSprite(debrisType)),
                    contentDescription = debrisDisplayName(parsedType),
                )
            }

            DetailTabRow(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.spotlightTarget("dex_detail_tabs", boundsMap),
            )
            DexTabContent(selectedTab = selectedTab, modifier = Modifier.fillMaxSize()) { tab ->
                when (tab) {
                    0 -> InfoTabContent(entry, impact, parsedType, boundsMap)
                    1 -> MarineDexGallery(parsedType, sessions, onNavigateToSession)
                    else -> MarineDexTypeMap(parsedType, sessions, onNavigateToSession)
                }
            }
        }

        SpotlightOverlay(
            controller = tourController,
            targetBounds = boundsMap,
            onComplete = {
                scope.launch {
                    app.settingsRepository.markTourComplete(
                        TourDefinitions.getScreenId(TourDefinitions.MARINEDEX_DETAIL)
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
                scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                app.launchDemoCleanup()
            },
        )

        if (showTransitionDialog) {
            GuidedTourTransitionDialog(
                screenId = "marinedex_detail",
                onContinue = {
                    showTransitionDialog = false
                    navController?.navigate("achievements") { popUpTo("home") }
                },
                onSkipTutorial = {
                    showTransitionDialog = false
                    scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                    app.launchDemoCleanup()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Tab row
// ---------------------------------------------------------------------------

@Composable
private fun DetailTabRow(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        stringResource(R.string.marinedex_detail_tab_info),
        stringResource(R.string.marinedex_detail_tab_gallery),
        stringResource(R.string.marinedex_detail_tab_map),
    )
    PrimaryTabRow(selectedTabIndex = selectedTab, modifier = modifier) {
        tabs.forEachIndexed { index, title ->
            Tab(
                selected = selectedTab == index,
                onClick = { onTabSelected(index) },
                text = { Text(title) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Info tab
// ---------------------------------------------------------------------------

@Composable
private fun InfoTabContent(
    entry: MarineDexEntry?,
    impact: DebrisImpactInfo,
    parsedType: DebrisType,
    boundsMap: MutableMap<String, androidx.compose.ui.geometry.Rect>,
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val detectedCount = entry?.timesDetected ?: 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TitleSection(displayName = debrisDisplayName(parsedType), materialLabel = debrisToMaterial(parsedType))
        Text(
            text = if (detectedCount != 1) {
                stringResource(R.string.marinedex_detail_detected_plural, detectedCount)
            } else {
                stringResource(R.string.marinedex_detail_detected_singular, detectedCount)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.spotlightTarget("dex_detail_stats", boundsMap)) {
            StatsRow(entry = entry, dateFormat = dateFormat)
        }
        Box(modifier = Modifier.spotlightTarget("dex_detail_impact", boundsMap)) {
            EnvironmentalImpactCard(impact = impact)
        }
    }
}

@Composable
private fun TitleSection(displayName: String, materialLabel: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .background(SonarTheme.colors.traceContainer, RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = materialLabel,
                style = MaterialTheme.typography.labelSmall,
                color = SonarTheme.colors.onTraceContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun StatsRow(entry: MarineDexEntry?, dateFormat: SimpleDateFormat) {
    val firstSeen = remember(entry?.firstSeenAt) { entry?.firstSeenAt?.let { dateFormat.format(it) } ?: "--" }
    val lastSeen = remember(entry?.lastSeenAt) { entry?.lastSeenAt?.let { dateFormat.format(it) } ?: "--" }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(stringResource(R.string.marinedex_detail_stat_times_detected), (entry?.timesDetected ?: 0).toString(), Modifier.weight(1f))
        StatCard(stringResource(R.string.marinedex_detail_stat_first_seen), firstSeen, Modifier.weight(1f))
        StatCard(stringResource(R.string.marinedex_detail_stat_last_seen), lastSeen, Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    SonarCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                style = SonarTheme.type.data,
                color = SonarTheme.colors.trace,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Environmental impact
// ---------------------------------------------------------------------------

@Composable
private fun EnvironmentalImpactCard(impact: DebrisImpactInfo) {
    SonarCard {
        Text(
            text = stringResource(R.string.marinedex_detail_impact_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(16.dp))
        ImpactFact(
            icon = { Icon(Icons.Filled.Schedule, null, tint = SonarTheme.colors.trace, modifier = Modifier.size(20.dp)) },
            label = stringResource(R.string.marinedex_detail_impact_degradation_label),
            value = stringResource(impact.degradationTimeRes),
        )
        Spacer(modifier = Modifier.height(12.dp))
        RiskLevelBar(riskScore = impact.riskScore)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(impact.primaryRiskRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        impact.annualVolumeRes?.let { volumeRes ->
            Spacer(modifier = Modifier.height(12.dp))
            ImpactFact(
                icon = { Icon(Icons.Filled.WaterDrop, null, tint = SonarTheme.colors.echo, modifier = Modifier.size(20.dp)) },
                label = stringResource(R.string.marinedex_detail_impact_annual_volume_label),
                value = stringResource(volumeRes),
            )
        }
    }
}

@Composable
private fun ImpactFact(icon: @Composable () -> Unit, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        icon()
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun RiskLevelBar(riskScore: Int) {
    val clamped = riskScore.coerceIn(0, 10)
    val barColor = healthScoreColor(score = (100 - clamped * 10).coerceAtLeast(0))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.marinedex_detail_impact_risk_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = "$clamped / 10", style = SonarTheme.type.data, color = barColor)
        }
        LinearProgressIndicator(
            progress = { clamped / 10f },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------

@Composable
private fun debrisToMaterial(type: DebrisType): String = when (type) {
    // Plastic types
    DebrisType.BOTTLE, DebrisType.PLASTIC_DEBRIS, DebrisType.BOTTLE_CAP,
    DebrisType.PLASTIC_BAG, DebrisType.FOOD_WRAPPER, DebrisType.STYROFOAM,
    DebrisType.PLASTIC_CUP, DebrisType.STRAW, DebrisType.PLASTIC_UTENSIL,
    DebrisType.SIX_PACK_RING, DebrisType.PLASTIC_SHEETING, DebrisType.DIAPER,
    DebrisType.CIGARETTE_BUTT, DebrisType.CIGARETTE_LIGHTER
        -> stringResource(R.string.material_plastic)
    // Metal types
    DebrisType.CAN, DebrisType.METAL_DEBRIS, DebrisType.AEROSOL_CAN,
    DebrisType.METAL_DRUM, DebrisType.WIRE_CABLE, DebrisType.BATTERY,
    DebrisType.ELECTRONICS
        -> stringResource(R.string.material_metal)
    // Fishing / nylon types
    DebrisType.FISHING_NET, DebrisType.FISHING_LINE, DebrisType.ROPE,
    DebrisType.FISHING_BUOY, DebrisType.FISHING_TRAP
        -> stringResource(R.string.material_nylon)
    // Mixed / fabric types
    DebrisType.GLOVE, DebrisType.MASK, DebrisType.CLOTHING, DebrisType.SHOE
        -> stringResource(R.string.material_mixed)
    DebrisType.FABRIC_DEBRIS -> stringResource(R.string.material_fabric)
    // Rubber types
    DebrisType.TIRE, DebrisType.FLIP_FLOP, DebrisType.RUBBER_HOSE
        -> stringResource(R.string.material_rubber)
    // Glass types
    DebrisType.GLASS_DEBRIS, DebrisType.GLASS_BOTTLE, DebrisType.GLASS_JAR,
    DebrisType.GLASS_FRAGMENT, DebrisType.LIGHT_BULB
        -> stringResource(R.string.material_glass)
    // Wood, paper, ceramic and chemical types have no material label yet
    DebrisType.WOOD_PALLET, DebrisType.LUMBER,
    DebrisType.CARDBOARD, DebrisType.PAPER,
    DebrisType.CERAMIC_FRAGMENT, DebrisType.BRICK,
    DebrisType.PAINT_CAN, DebrisType.OIL_CONTAINER, DebrisType.SYRINGE,
    DebrisType.CHEMICAL_DRUM,
    DebrisType.OTHER
        -> stringResource(R.string.material_unknown)
}
