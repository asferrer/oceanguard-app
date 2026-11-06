package com.oceanguard.ai.ui.components.analysis

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.inference.DetectionResult
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.motionSpec
import com.oceanguard.ai.ui.motion.rememberOceanLoop
import kotlinx.coroutines.delay

private const val REDUCED_TINT_ALPHA = 0.10f
private const val ONE_SECOND_MS = 1_000L

/**
 * The signature moment: the sonar line sweeps the photo for as long as the inference runs, and
 * each detection answers with an amber echo, a box that draws itself and a label that jumps.
 *
 * Under "Remove animations" the line gives way to a still tint of the photo, and the boxes are
 * simply there when they arrive.
 *
 * @param running True only while the pipeline is really working.
 * @param sweepPeriodMs Length of one pass of the line while it works.
 * @param showConfidence False for detectors whose score is not a model confidence.
 * @param imageModifier Applied to the photo alone: this is the element shared with the detail.
 */
@Composable
fun AnalysisSweepStage(
    imageUri: Uri?,
    reveal: SweepReveal,
    running: Boolean,
    sweepPeriodMs: Int,
    showConfidence: Boolean,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier,
) {
    var imageSize by remember { mutableStateOf(Size.Unspecified) }
    Box(modifier) {
        AsyncImage(
            model = imageUri,
            contentDescription = stringResource(R.string.cd_captured_photo_preview),
            contentScale = ContentScale.Fit,
            onSuccess = { imageSize = it.painter.intrinsicSize },
            modifier = imageModifier.fillMaxSize(),
        )
        SweepCanvas(imageSize, reveal, running, sweepPeriodMs, showConfidence)
    }
}

@Composable
private fun SweepCanvas(
    imageSize: Size,
    reveal: SweepReveal,
    running: Boolean,
    sweepPeriodMs: Int,
    showConfidence: Boolean,
) {
    val reduced = LocalReducedMotion.current
    val loop by rememberOceanLoop(sweepPeriodMs, running = running && !reveal.passing)
    val sweepAlpha by animateFloatAsState(
        targetValue = if (running || reveal.passing) 1f else 0f,
        animationSpec = motionSpec(OceanMotion.exit()),
        label = "sweepAlpha",
    )
    val labels = rememberEchoLabels(reveal.ordered, showConfidence)

    Canvas(Modifier.fillMaxSize()) {
        val frame = SweepGeometry.fit(size, imageSize)
        clipRect(frame.left, frame.top, frame.right, frame.bottom) {
            if (sweepAlpha > 0f) {
                if (reduced) {
                    drawRect(
                        color = OnPhoto.trace.copy(alpha = REDUCED_TINT_ALPHA * sweepAlpha),
                        topLeft = frame.topLeft,
                        size = frame.size,
                    )
                } else {
                    val fraction = RevealRules.lineFraction(
                        running = running,
                        passing = reveal.passing,
                        hasDetections = reveal.ordered.isNotEmpty(),
                        loopPhase = loop,
                        passFraction = reveal.lineFraction,
                    )
                    drawSweepLine(frame, fraction, OnPhoto.trace, sweepAlpha)
                }
            }
            drawEchoes(reveal, labels, frame)
        }
    }
}

private fun DrawScope.drawEchoes(
    reveal: SweepReveal,
    labels: List<TextLayoutResult>,
    frame: Rect,
) {
    reveal.ordered.forEachIndexed { index, detection ->
        val echo = reveal.echoes.getOrNull(index) ?: return@forEachIndexed
        val rect = SweepGeometry.boxRect(detection, frame)
        drawEchoRings(rect, echo.ring.value, OnPhoto.echo)
        drawTracedBox(rect, echo.box.value, OnPhoto.echo)
        labels.getOrNull(index)?.let {
            drawEchoLabel(it, rect, frame, echo.label.value, OnPhoto.echo)
        }
    }
}

/** Measured once per list: text in sp, so it follows the system font size like any other text. */
@Composable
internal fun rememberEchoLabels(
    detections: List<DetectionResult>,
    showConfidence: Boolean,
): List<TextLayoutResult> {
    val measurer = rememberTextMeasurer()
    val base = MaterialTheme.typography.labelMedium
    val style = remember(base) {
        TextStyle(
            color = OnPhoto.onEcho,
            fontFamily = base.fontFamily,
            fontSize = base.fontSize,
            fontWeight = FontWeight.Bold,
        )
    }
    return remember(detections, showConfidence, style, measurer) {
        detections.map { measurer.measure(RevealRules.label(it, showConfidence), style) }
    }
}

/** Whole seconds since [running] became true; holds its value after it stops. */
@Composable
fun rememberElapsedSeconds(running: Boolean): Int {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        seconds = 0
        while (true) {
            delay(ONE_SECOND_MS)
            seconds += 1
        }
    }
    return seconds
}
