package com.oceanguard.ai.ui.components.analysis

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.oceanguard.ai.inference.DetectionResult
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val BUMP_SCALE = 1.3f

/** Pure rules of the reveal: no animation state, so they can be tested on their own. */
internal object RevealRules {

    /** Top to bottom, the order a line going down the photo meets the boxes. */
    fun order(detections: List<DetectionResult>): List<DetectionResult> =
        detections.sortedWith(compareBy({ it.y1 }, { it.x1 }))

    /** How many of [ordered] the line has reached at [lineFraction] of the photo's height. */
    fun fired(ordered: List<DetectionResult>, lineFraction: Float): Int =
        if (lineFraction >= 1f) ordered.size else ordered.count { it.y1 <= lineFraction }

    /**
     * Position of the visible line. The final pass owns it while it plays and afterwards, so the
     * line does not jump back to the loop as it fades out; with nothing to reveal the loop keeps it.
     */
    fun lineFraction(
        running: Boolean,
        passing: Boolean,
        hasDetections: Boolean,
        loopPhase: Float,
        passFraction: Float,
    ): Float = if (passing || (!running && hasDetections)) passFraction else loopPhase

    /** Text of a box. A percentage appears only when the detector gives a real score. */
    fun label(detection: DetectionResult, showConfidence: Boolean): String {
        val name = (detection.rawLabel ?: detection.className)
            .replace('_', ' ')
            .lowercase()
            .replaceFirstChar { it.uppercase() }
        return if (showConfidence) "$name ${(detection.confidence * 100).toInt()}%" else name
    }
}

/** Life of one detection: rings that expand, box that draws itself, label that jumps. */
@Stable
class EchoAnimation {
    val ring = Animatable(0f)
    val box = Animatable(0f)
    val label = Animatable(0f)

    suspend fun play() = coroutineScope {
        launch { ring.animateTo(1f, OceanMotion.signature()) }
        launch { box.animateTo(1f, OceanMotion.standard()) }
        delay(OceanMotion.FAST_MS.toLong())
        label.animateTo(1f, OceanMotion.popSpring())
    }

    suspend fun finish() {
        ring.snapTo(1f)
        box.snapTo(1f)
        label.snapTo(1f)
    }
}

/** State of the final pass of the line over the boxes that the model has just delivered. */
@Stable
class SweepReveal internal constructor(
    val ordered: List<DetectionResult>,
    val echoes: List<EchoAnimation>,
    private val line: Animatable<Float, AnimationVector1D>,
) {
    val lineFraction: Float get() = line.value
    val passing: Boolean get() = line.isRunning

    /** Detections shown so far: the counter of the screen follows this, not the raw list. */
    val fired: Int by derivedStateOf { RevealRules.fired(ordered, line.value) }
}

/**
 * Starts the pass as soon as [detections] arrive, whenever that is: boxes reach the UI all at once
 * from either detector, so the pass is the reveal and never stands for progress of the inference.
 * Under "Remove animations" everything is simply there.
 */
@Composable
fun rememberSweepReveal(detections: List<DetectionResult>): SweepReveal {
    val reduced = LocalReducedMotion.current
    val ordered = remember(detections) { RevealRules.order(detections) }
    val count = ordered.size
    val line = remember { Animatable(0f) }
    val echoes = remember(count) { List(count) { EchoAnimation() } }
    val latestOrdered by rememberUpdatedState(ordered)

    LaunchedEffect(count, reduced) {
        if (count == 0) {
            line.snapTo(0f)
            return@LaunchedEffect
        }
        if (reduced) {
            line.snapTo(1f)
            echoes.forEach { it.finish() }
            return@LaunchedEffect
        }
        launch {
            var played = 0
            snapshotFlow { RevealRules.fired(latestOrdered, line.value) }.collect { fired ->
                while (played < fired) {
                    val echo = echoes[played++]
                    launch { echo.play() }
                }
            }
        }
        line.animateTo(1f, OceanMotion.signature())
    }
    return remember(ordered, echoes) { SweepReveal(ordered, echoes, line) }
}

/**
 * Scale of a counter that has just gone up: it jumps and settles on the pop spring. Holds at 1
 * under "Remove animations".
 */
@Composable
fun rememberCountBump(count: Int): State<Float> {
    val reduced = LocalReducedMotion.current
    val scale = remember { Animatable(1f) }
    LaunchedEffect(count, reduced) {
        if (count > 0 && !reduced) {
            scale.snapTo(BUMP_SCALE)
            scale.animateTo(1f, OceanMotion.popSpring())
        }
    }
    return scale.asState()
}
