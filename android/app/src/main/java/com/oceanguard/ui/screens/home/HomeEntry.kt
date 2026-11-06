package com.oceanguard.ai.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import kotlinx.coroutines.delay

private val ENTRY_OFFSET = 16.dp

/**
 * Block of Home that rises into place, staggered by [index].
 *
 * Under "Remove animations" it only cross-fades: no offset and no stagger, so nothing moves.
 * Put spotlight targets outside this wrapper, so the tour measures the final position.
 */
@Composable
fun HomeEntry(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!reduced) delay(HomeLogic.entryDelayMs(index).toLong())
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = if (reduced) OceanMotion.reducedFade() else OceanMotion.standard(),
        label = "homeEntry",
    )
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress
            if (!reduced) translationY = (1f - progress) * ENTRY_OFFSET.toPx()
        },
    ) {
        content()
    }
}
