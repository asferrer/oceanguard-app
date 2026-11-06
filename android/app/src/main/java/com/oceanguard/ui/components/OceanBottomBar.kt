package com.oceanguard.ai.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.motionSpec

/**
 * Data class representing a single bottom navigation tab.
 *
 * @param route   Navigation destination route string.
 * @param label   User-visible label shown below the icon.
 * @param icon    Material icon vector drawn as the tab icon.
 */
data class BottomBarTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

/**
 * Custom animated bottom navigation bar for OceanGuard AI.
 *
 * Features:
 * - Indicator pill that fades in behind the selected tab icon.
 * - Bouncy spring scale animation applied to the selected icon.
 * - Smooth tween colour transitions for icon and label tints.
 * - Subtle top-edge gradient border drawn via [drawBehind].
 * - Semi-transparent surface background respecting edge-to-edge insets.
 *
 * The bar does not manage navigation internally; callers supply [onTabSelected]
 * and drive the selected state via [currentRoute].
 *
 * @param tabs          Ordered list of tabs to render.
 * @param currentRoute  Active navigation route; determines the selected tab.
 * @param onTabSelected Callback invoked with the route of a newly tapped tab.
 * @param modifier      Optional [Modifier] for the outer container.
 */
@Composable
fun OceanBottomBar(
    tabs: List<BottomBarTab>,
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = tabs.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    val borderColor = MaterialTheme.colorScheme.outline

    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind { drawTopEdgeBorder(borderColor) }
            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.95f))
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 8.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                TabItem(
                    tab = tab,
                    isSelected = index == selectedIndex,
                    onTabSelected = onTabSelected,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

/**
 * Renders a single tab item with animated icon, indicator pill, and label.
 */
@Composable
private fun TabItem(
    tab: BottomBarTab,
    isSelected: Boolean,
    onTabSelected: (String) -> Unit,
) {
    val iconColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = motionSpec(OceanMotion.standard()),
        label = "iconColor_${tab.route}",
    )
    val labelColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = motionSpec(OceanMotion.standard()),
        label = "labelColor_${tab.route}",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.12f else 1f,
        animationSpec = motionSpec(OceanMotion.popSpring()),
        label = "iconScale_${tab.route}",
    )
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = motionSpec(OceanMotion.standard()),
        label = "indicatorAlpha_${tab.route}",
    )

    Column(
        modifier = Modifier
            .width(72.dp)
            .selectable(
                selected = isSelected,
                role = Role.Tab,
                onClick = { if (!isSelected) onTabSelected(tab.route) },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TabIconWithPill(
            tab = tab,
            iconColor = iconColor,
            iconScale = iconScale,
            indicatorAlpha = indicatorAlpha,
        )

        Text(
            text = stringResource(tab.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Icon layered on top of its selection indicator pill.
 */
@Composable
private fun TabIconWithPill(
    tab: BottomBarTab,
    iconColor: Color,
    iconScale: Float,
    indicatorAlpha: Float,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center) {
        // Glow dot behind selected icon
        Box(
            modifier = Modifier
                .size(36.dp)
                .graphicsLayer { alpha = indicatorAlpha * 0.4f }
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.35f),
                                Color.Transparent,
                            ),
                        ),
                        radius = size.width / 2f,
                    )
                },
        )

        // Selection indicator pill
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(28.dp)
                .graphicsLayer { alpha = indicatorAlpha }
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.15f)),
        )

        // Tab icon with spring scale
        Icon(
            imageVector = tab.icon,
            // The label below already names the tab: TalkBack must not read it twice.
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
        )
    }
}

/**
 * Draws a horizontal gradient line across the top edge of the composable.
 * Used via [Modifier.drawBehind] on the bar container.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTopEdgeBorder(color: Color) {
    drawLine(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, color, Color.Transparent),
        ),
        start = Offset(0f, 0f),
        end = Offset(size.width, 0f),
        strokeWidth = 1.dp.toPx(),
    )
}
