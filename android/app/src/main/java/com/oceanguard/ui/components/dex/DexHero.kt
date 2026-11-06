package com.oceanguard.ai.ui.components.dex

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.rememberOceanLoop
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.launch

private val HeroHeight = 200.dp
private val HeroSpriteSize = 120.dp
private const val FLOAT_PERIOD_MS = 4_800
private const val FLOAT_AMPLITUDE_DP = 6f
private const val HEART_PEAK = 1.25f

/** Vertical offset, in dp, of the floating sprite for a loop [phase] in 0..1. */
internal fun heroFloatOffset(phase: Float): Float =
    sin(phase * 2f * PI.toFloat()) * FLOAT_AMPLITUDE_DP

/** Direction of a tab change: 1 when moving to a later tab, -1 to an earlier one, 0 if equal. */
internal fun tabDirection(from: Int, to: Int): Int = to.compareTo(from)

/**
 * Header of a dex detail: the sprite floating over a sonar-tinted gradient, back arrow and the
 * favourite heart. The sprite comes from [sprite], so the caller can mark it as the shared
 * element that flew in from the grid. The float runs only while the screen is resumed and
 * stops under "Remove animations".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexHero(
    isFavorite: Boolean,
    onNavigateBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    backDescription: String,
    favoriteDescription: String,
    modifier: Modifier = Modifier,
    sprite: @Composable (modifier: Modifier, size: Dp) -> Unit,
) {
    val colors = SonarTheme.colors
    val phase by rememberOceanLoop(FLOAT_PERIOD_MS)
    val heartScale = remember { Animatable(1f) }
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HeroHeight)
            .background(Brush.verticalGradient(listOf(colors.abyss, colors.deep, colors.traceContainer.copy(alpha = 0.35f)))),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.graphicsLayer { translationY = heroFloatOffset(phase) * density }) {
                sprite(Modifier, HeroSpriteSize)
            }
        }
        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, backDescription, tint = colors.ink)
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        if (!reduced) {
                            scope.launch {
                                heartScale.snapTo(1f)
                                heartScale.animateTo(HEART_PEAK, OceanMotion.snapSpring())
                                heartScale.animateTo(1f, OceanMotion.popSpring())
                            }
                        }
                        onToggleFavorite()
                    },
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = favoriteDescription,
                        tint = if (isFavorite) colors.alert else colors.ink,
                        modifier = Modifier
                            .size(28.dp)
                            .graphicsLayer {
                                scaleX = heartScale.value
                                scaleY = heartScale.value
                            },
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
    }
}

/**
 * Content of a tab row with the shared-axis transition: the new tab slides in along X from the
 * side it sits on while the old one leaves the other way, both cross-fading. Under "Remove
 * animations" it is only a short cross-fade.
 */
@Composable
fun DexTabContent(
    selectedTab: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = selectedTab,
        modifier = modifier,
        transitionSpec = { tabTransition(tabDirection(initialState, targetState), reduced) },
        label = "dexTabs",
    ) { tab -> content(tab) }
}

private fun tabTransition(direction: Int, reduced: Boolean): ContentTransform {
    if (reduced || direction == 0) {
        return fadeIn(OceanMotion.reducedFade()) togetherWith fadeOut(OceanMotion.reducedFade())
    }
    val shift = { width: Int -> (width * OceanMotion.AXIS_OFFSET_FRACTION * direction).toInt() }
    return (slideInHorizontally(OceanMotion.standard()) { shift(it) } + fadeIn(OceanMotion.standard())) togetherWith
        (slideOutHorizontally(OceanMotion.exit()) { -shift(it) } + fadeOut(OceanMotion.exit()))
}
