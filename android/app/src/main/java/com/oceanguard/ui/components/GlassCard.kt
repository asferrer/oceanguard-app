package com.oceanguard.ai.ui.components

import androidx.compose.foundation.BorderStroke
import com.oceanguard.ai.ui.theme.LocalIsDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.rememberOceanLoop
import com.oceanguard.ai.ui.theme.SonarTheme
import com.oceanguard.ai.ui.theme.GlassHighlight
import com.oceanguard.ai.ui.theme.GlassInnerGlow
import kotlin.math.cos
import kotlin.math.sin

private val CardShape = RoundedCornerShape(20.dp)
private const val BORDER_LOOP_MS = 8_000

/**
 * Premium glassmorphism card with animated gradient border.
 *
 * Uses alpha-based transparency (no real-time blur) for performance on Exynos.
 * The animated border rotates through ocean colours over an 8-second cycle.
 * Adapts automatically to light and dark themes.
 *
 * @param animate Set to false when rendering inside a LazyColumn to avoid
 *                dozens of concurrent infinite animations on screen.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isDark = LocalIsDarkTheme.current

    // Theme-aware border colors
    val borderColors = listOf(
        SonarTheme.colors.trace.copy(alpha = 0.45f),
        SonarTheme.colors.line,
        SonarTheme.colors.line,
        Color.Transparent,
    )

    // --- Animated border rotation ---
    val borderBrush = if (animate && !LocalReducedMotion.current) {
        val phase by rememberOceanLoop(BORDER_LOOP_MS)
        val angle = phase * 360f
        val rad = Math.toRadians(angle.toDouble())
        Brush.linearGradient(
            colors = borderColors,
            start = Offset(
                x = (cos(rad) * 500f + 500f).toFloat(),
                y = (sin(rad) * 500f).toFloat(),
            ),
            end = Offset(
                x = (cos(rad + Math.PI) * 500f + 500f).toFloat(),
                y = (sin(rad + Math.PI) * 500f + 500f).toFloat(),
            ),
        )
    } else {
        val staticColor = MaterialTheme.colorScheme.outlineVariant
        Brush.linearGradient(colors = listOf(staticColor, Color.Transparent))
    }

    // Theme-aware container color
    val containerColor = MaterialTheme.colorScheme.surface

    Card(
        modifier = modifier
            .fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(width = 1.dp, brush = borderBrush),
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .then(
                    if (isDark) {
                        Modifier.drawWithContent {
                            drawContent()
                            // Top highlight edge: simulates refracted light on glass
                            drawLine(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        GlassHighlight,
                                        Color.Transparent,
                                    ),
                                ),
                                start = Offset(0f, 0f),
                                end = Offset(size.width, 0f),
                                strokeWidth = 1f,
                            )
                            // Corner radial glow: subtle light patch
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        GlassInnerGlow,
                                        Color.Transparent,
                                    ),
                                    center = Offset(0f, 0f),
                                    radius = size.width * 0.55f,
                                ),
                                radius = size.width * 0.55f,
                                center = Offset(0f, 0f),
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
            content = content,
        )
    }
}
