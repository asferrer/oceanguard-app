package com.oceanguard.ai.ui.components.dex

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.collection.Achievement
import com.oceanguard.ai.data.collection.AchievementDef
import com.oceanguard.ai.ui.components.sonar.springPress
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.motionSpec
import com.oceanguard.ai.ui.theme.AchievementColors
import com.oceanguard.ai.ui.theme.SonarTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CardShape = RoundedCornerShape(16.dp)
private val IconBadgeSize = 48.dp
private val AccentBarWidth = 4.dp

/** Share of [target] reached by [progress], always inside 0..1 and 0 for a target of 0. */
internal fun achievementFraction(progress: Int, target: Int): Float =
    if (target > 0) (progress / target.toFloat()).coerceIn(0f, 1f) else 0f

/**
 * Card of an unlocked achievement. Tapping it replays the unlock effect of its category through
 * [onReplay]; the whole card is the touch target.
 */
@Composable
fun UnlockedAchievementCard(
    def: AchievementDef,
    achievement: Achievement,
    onReplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateString = remember(achievement.unlockedAt) {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(achievement.unlockedAt))
    }
    val interaction = remember { MutableInteractionSource() }
    val accent = AchievementColors.Ring
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .springPress(interaction)
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = stringResource(R.string.achievement_replay_action),
                onClick = onReplay,
            ),
    ) {
        Box(
            modifier = Modifier
                .width(AccentBarWidth)
                .fillMaxHeight()
                .background(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.5f)))),
        )
        AchievementRow(
            def = def,
            iconTint = accent,
            iconBackground = accent.copy(alpha = 0.15f),
            nameWeight = FontWeight.Bold,
        ) {
            Text(
                text = stringResource(R.string.achievements_unlocked_date, dateString),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Card of a locked achievement. Text keeps full-strength theme colours, so the card reads at the
 * same contrast as an unlocked one; the lock icon and the progress bar say it is pending.
 */
@Composable
fun LockedAchievementCard(
    def: AchievementDef,
    achievement: Achievement?,
    modifier: Modifier = Modifier,
) {
    val progress = achievement?.progress ?: 0
    val fraction by animateFloatAsState(
        targetValue = achievementFraction(progress, def.target),
        animationSpec = motionSpec(OceanMotion.softSpring()),
        label = "achievementProgress",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outline, CardShape),
    ) {
        Box(
            modifier = Modifier
                .width(AccentBarWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline),
        )
        AchievementRow(
            def = def,
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            iconBackground = MaterialTheme.colorScheme.surfaceVariant,
            nameWeight = FontWeight.SemiBold,
            trailing = { LockBadge() },
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = AchievementColors.ProgressBar,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                text = "$progress / ${def.target}",
                style = SonarTheme.type.dataSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RowScope.AchievementRow(
    def: AchievementDef,
    iconTint: Color,
    iconBackground: Color,
    nameWeight: FontWeight,
    trailing: (@Composable () -> Unit)? = null,
    footer: @Composable () -> Unit,
) {
    val name = stringResource(def.nameRes)
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(IconBadgeSize)
                .clip(CircleShape)
                .background(iconBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = def.icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(26.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = nameWeight,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(def.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            footer()
        }
        trailing?.invoke()
    }
}

@Composable
private fun LockBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.Lock,
        contentDescription = stringResource(R.string.achievement_locked),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(18.dp),
    )
}
