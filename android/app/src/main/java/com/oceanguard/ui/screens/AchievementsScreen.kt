package com.oceanguard.ai.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.collection.ACHIEVEMENT_TOTAL
import com.oceanguard.ai.data.collection.ALL_ACHIEVEMENTS
import com.oceanguard.ai.data.collection.Achievement
import com.oceanguard.ai.data.collection.AchievementCategory
import com.oceanguard.ai.data.collection.AchievementDef
import com.oceanguard.ai.data.collection.CollectionRepository
import com.oceanguard.ai.ui.components.AchievementUnlockOverlay
import com.oceanguard.ai.ui.components.dex.LockedAchievementCard
import com.oceanguard.ai.ui.components.dex.UnlockedAchievementCard
import com.oceanguard.ai.ui.components.spotlight.GuidedTourTransitionDialog
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.motionSpec
import com.oceanguard.ai.ui.theme.AchievementColors
import com.oceanguard.ai.ui.theme.LocalIsDarkTheme
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val MinTouchTarget = 48.dp

// ---------------------------------------------------------------------------
// Screen entry point
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsScreen(
    collectionRepository: CollectionRepository,
    onNavigateBack: () -> Unit,
    navController: NavController? = null,
) {
    val achievements by collectionRepository.allAchievements
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val unlockedCount by collectionRepository.unlockedAchievementCount
        .collectAsStateWithLifecycle(initialValue = 0)

    val achContext = LocalContext.current
    val app = remember(achContext) { achContext.applicationContext as OceanGuardApp }
    val achScope = rememberCoroutineScope()
    val boundsMap = rememberSpotlightBounds()
    val tourController = rememberSpotlightController(TourDefinitions.ACHIEVEMENTS)
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.ACHIEVEMENTS))
        .collectAsStateWithLifecycle(initialValue = true)

    val guidedTourActive by app.settingsRepository.guidedTourActive
        .collectAsStateWithLifecycle(initialValue = false)
    var showTransitionDialog by remember { mutableStateOf(false) }
    var replaying by remember { mutableStateOf<AchievementDef?>(null) }

    LaunchedEffect(tourComplete) {
        if (!tourComplete) tourController.start()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { AchievementsTopBar(onNavigateBack) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { paddingValues ->
            AchievementsContent(
                achievements = achievements,
                unlockedCount = unlockedCount,
                onReplay = { replaying = it },
                modifier = Modifier.padding(paddingValues),
                boundsMap = boundsMap,
            )
        }

        SpotlightOverlay(
            controller = tourController,
            targetBounds = boundsMap,
            onComplete = {
                achScope.launch {
                    app.settingsRepository.markTourComplete(
                        TourDefinitions.getScreenId(TourDefinitions.ACHIEVEMENTS)
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
                achScope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                app.launchDemoCleanup()
            },
        )

        if (showTransitionDialog) {
            GuidedTourTransitionDialog(
                screenId = "achievements",
                onContinue = {
                    showTransitionDialog = false
                    navController?.navigate("map") {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onSkipTutorial = {
                    showTransitionDialog = false
                    achScope.launch { app.settingsRepository.markAllGuidedToursComplete() }
                    app.launchDemoCleanup()
                },
            )
        }

        AchievementUnlockOverlay(achievementDef = replaying, onDismiss = { replaying = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AchievementsTopBar(onNavigateBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.achievements_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.achievements_cd_go_back),
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
// Main scrollable content
// ---------------------------------------------------------------------------

@Composable
private fun AchievementsContent(
    achievements: List<Achievement>,
    unlockedCount: Int,
    onReplay: (AchievementDef) -> Unit,
    modifier: Modifier = Modifier,
    boundsMap: MutableMap<String, androidx.compose.ui.geometry.Rect> = mutableMapOf(),
) {
    val achievementMap = remember(achievements) { achievements.associateBy { it.id } }
    val expanded = remember {
        mutableStateMapOf<AchievementCategory, Boolean>().apply {
            AchievementCategory.entries.forEach { put(it, true) }
        }
    }
    val groupedDefs = remember {
        AchievementCategory.entries.associateWith { category ->
            ALL_ACHIEVEMENTS.filter { it.category == category }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box(modifier = Modifier.spotlightTarget("achievements_progress", boundsMap)) {
                ProgressHeader(unlockedCount = unlockedCount)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        AchievementCategory.entries.forEachIndexed { catIndex, category ->
            val defs = groupedDefs[category].orEmpty()
            val isExpanded = expanded[category] ?: true

            item(key = category.name) {
                val tourModifier = if (catIndex == 0) {
                    Modifier.spotlightTarget("achievements_list", boundsMap)
                } else {
                    Modifier
                }
                Box(modifier = tourModifier) {
                    CategoryHeader(
                        category = category,
                        defs = defs,
                        achievementMap = achievementMap,
                        isExpanded = isExpanded,
                        onToggle = { expanded[category] = !isExpanded },
                    )
                }
            }

            if (isExpanded) {
                items(items = defs, key = { it.id }) { def ->
                    AchievementEntry(
                        def = def,
                        achievement = achievementMap[def.id],
                        index = defs.indexOf(def),
                        onReplay = { onReplay(def) },
                    )
                }
            }

            item(key = "gap_${category.name}") { Spacer(modifier = Modifier.height(4.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Progress header (keeps the screen's own gradient, ring and green bar)
// ---------------------------------------------------------------------------

@Composable
private fun ProgressHeader(unlockedCount: Int) {
    val isDark = LocalIsDarkTheme.current
    val onHero = if (isDark) SonarTheme.colors.ink else MaterialTheme.colorScheme.onPrimaryContainer
    val onHeroDim = if (isDark) SonarTheme.colors.ink2 else MaterialTheme.colorScheme.onPrimaryContainer
    val track = if (isDark) onHero.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    val animated by animateFloatAsState(
        targetValue = unlockedCount / ACHIEVEMENT_TOTAL.toFloat(),
        animationSpec = motionSpec(OceanMotion.softSpring()),
        label = "headerProgress",
    )
    val gradient = if (isDark) {
        listOf(AchievementColors.GradientStart, AchievementColors.GradientMid, AchievementColors.GradientEnd)
    } else {
        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(gradient))
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.achievements_progress_label),
                style = MaterialTheme.typography.labelLarge,
                color = onHeroDim,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.achievements_progress_count, unlockedCount, ACHIEVEMENT_TOTAL),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = onHero,
            )
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = AchievementColors.ProgressBar,
                trackColor = track,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.achievements_percent_complete, "%.0f".format(animated * 100)),
                style = MaterialTheme.typography.bodySmall,
                color = onHero,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.width(20.dp))
        CircularProgressRing(progress = animated, unlocked = unlockedCount, onHero = onHero, track = track)
    }
}

@Composable
private fun CircularProgressRing(progress: Float, unlocked: Int, onHero: Color, track: Color) {
    val isDark = LocalIsDarkTheme.current
    val background = if (isDark) onHero.copy(alpha = 0.1f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    val ring = AchievementColors.Ring
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .background(background)
            .border(
                width = 3.dp,
                brush = Brush.sweepGradient(
                    0f to track,
                    progress to ring,
                    progress to track,
                    1f to track,
                ),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "$unlocked", style = SonarTheme.type.dataLarge, color = onHero)
            Text(text = "/ $ACHIEVEMENT_TOTAL", style = SonarTheme.type.dataSmall, color = onHero)
        }
    }
}

// ---------------------------------------------------------------------------
// Category header
// ---------------------------------------------------------------------------

@Composable
private fun CategoryHeader(
    category: AchievementCategory,
    defs: List<AchievementDef>,
    achievementMap: Map<String, Achievement>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val unlocked = defs.count { (achievementMap[it.id]?.unlockedAt ?: 0L) > 0L }
    val label = stringResource(category.labelRes)
    val actionLabel = stringResource(
        if (isExpanded) R.string.achievements_cd_collapse else R.string.achievements_cd_expand,
        label,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouchTarget)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = TextUnit(1f, TextUnitType.Sp),
            )
            Text(
                text = stringResource(R.string.achievements_category_count, unlocked, defs.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// One achievement: staggered entry, then the unlocked or locked card
// ---------------------------------------------------------------------------

@Composable
private fun AchievementEntry(
    def: AchievementDef,
    achievement: Achievement?,
    index: Int,
    onReplay: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    var visible by remember { mutableStateOf(reduced) }
    LaunchedEffect(def.id) {
        if (!reduced) delay(index * OceanMotion.STAGGER_MS.toLong())
        visible = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = motionSpec(OceanMotion.standard()),
        label = "entryAlpha",
    )
    val lift by animateFloatAsState(
        targetValue = if (visible) 0f else ENTRY_LIFT_PX,
        animationSpec = motionSpec(OceanMotion.softSpring()),
        label = "entryLift",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .graphicsLayer {
                this.alpha = alpha
                translationY = lift
            },
    ) {
        if ((achievement?.unlockedAt ?: 0L) > 0L) {
            UnlockedAchievementCard(def = def, achievement = achievement!!, onReplay = onReplay)
        } else {
            LockedAchievementCard(def = def, achievement = achievement)
        }
    }
}

private const val ENTRY_LIFT_PX = 24f
