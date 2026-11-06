package com.oceanguard.ai.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.oceanguard.ai.R
import com.oceanguard.ai.data.collection.AchievementDef
import com.oceanguard.ai.ui.components.dex.AchievementFx
import com.oceanguard.ai.ui.components.dex.AchievementFxMath
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CardShape = RoundedCornerShape(20.dp)
private val TrophySize = 84.dp

/** Time the celebration stays on screen before it closes by itself. */
private const val AUTO_DISMISS_MS = 3_500L

/**
 * Full-screen overlay that celebrates an unlocked achievement with the effect of its category
 * (wave, sonar sweep, orbiting sprites, rising tide, network or golden rays).
 *
 * Shown while [achievementDef] is not null. It closes itself after [AUTO_DISMISS_MS]; tapping
 * the scrim closes it earlier. The same overlay replays an achievement the user taps in the
 * list. Under "Remove animations" the trophy appears still, with no effect behind it.
 *
 * @param achievementDef Achievement to celebrate, or null while nothing is shown.
 * @param onDismiss      Called when the overlay should be removed (timeout or tap).
 */
@Composable
fun AchievementUnlockOverlay(
    achievementDef: AchievementDef?,
    onDismiss: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val view = LocalView.current
    val timeline = remember(achievementDef) { Animatable(0f) }
    val cardScale = remember(achievementDef) { Animatable(if (reduced) 1f else 0.6f) }
    val currentDismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(achievementDef, reduced) {
        if (achievementDef == null) return@LaunchedEffect
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (!reduced) {
            launch { cardScale.animateTo(1f, OceanMotion.popSpring()) }
            launch {
                timeline.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(AchievementFxMath.DURATION_MS, easing = LinearEasing),
                )
            }
        }
        delay(AUTO_DISMISS_MS)
        currentDismiss()
    }

    AnimatedVisibility(
        visible = achievementDef != null,
        enter = fadeIn(if (reduced) OceanMotion.reducedFade() else OceanMotion.standard()),
        exit = fadeOut(if (reduced) OceanMotion.reducedFade() else OceanMotion.exit()),
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f),
    ) {
        if (achievementDef == null) return@AnimatedVisibility
        UnlockContent(
            achievementDef = achievementDef,
            reduced = reduced,
            progress = { timeline.value },
            cardScale = { cardScale.value },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun UnlockContent(
    achievementDef: AchievementDef,
    reduced: Boolean,
    progress: () -> Float,
    cardScale: () -> Float,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!reduced) {
            AchievementFx(
                category = achievementDef.category,
                progress = progress,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = cardScale()
                    scaleY = cardScale()
                }
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            UnlockCard(achievementDef)
        }
    }
}

@Composable
private fun UnlockCard(achievementDef: AchievementDef) {
    val gold = SonarTheme.colors.echo
    Box(
        modifier = Modifier
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), CardShape)
            .border(width = 2.dp, color = gold, shape = CardShape)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.achievement_unlock_overlay_title),
                style = MaterialTheme.typography.labelLarge,
                color = gold,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Icon(
                imageVector = Icons.Filled.EmojiEvents,
                contentDescription = null,
                tint = gold,
                modifier = Modifier.size(TrophySize),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(achievementDef.nameRes),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(achievementDef.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
