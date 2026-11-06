package com.oceanguard.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.valentinilk.shimmer.shimmer
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.rememberOceanLoop
import com.oceanguard.ai.ui.theme.ShimmerBase

private val ShimmerShape = RoundedCornerShape(14.dp)

private const val PULSE_PERIOD_MS = 2_400
private const val PULSE_MIN_ALPHA = 0.6f

/** Triangle wave over one loop: opacity goes from 0.6 up to 1 and back. */
internal fun shimmerPulseAlphaAt(phase: Float): Float =
    PULSE_MIN_ALPHA + (1f - PULSE_MIN_ALPHA) * (1f - kotlin.math.abs(2f * phase - 1f))

/**
 * Single shimmer placeholder block with wave-wash effect and subtle pulse.
 *
 * The pulse gently modulates opacity (0.6 - 1.0) alongside the shimmer
 * sweep, producing a more organic loading feel than shimmer alone.
 */
@Composable
fun ShimmerCard(
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
) {
    val phase by rememberOceanLoop(PULSE_PERIOD_MS)
    val pulseAlpha = if (LocalReducedMotion.current) 1f else shimmerPulseAlphaAt(phase)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { alpha = pulseAlpha }
            .clip(ShimmerShape)
            .shimmer()
            .background(
                color = ShimmerBase,
                shape = ShimmerShape,
            ),
    )
}

/**
 * Full-screen shimmer loading layout mimicking the expected content structure.
 * Shows a hero card, stat row, and N list item placeholders.
 */
@Composable
fun ShimmerLoadingScreen(
    modifier: Modifier = Modifier,
    itemCount: Int = 3,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Hero placeholder
        ShimmerCard(height = 180.dp)

        // Stat row placeholder
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(3) {
                ShimmerCard(
                    modifier = Modifier.weight(1f),
                    height = 80.dp,
                )
            }
        }

        // List item placeholders
        repeat(itemCount) {
            ShimmerCard(height = 100.dp)
        }
    }
}
