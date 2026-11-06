package com.oceanguard.ai.ui.screens.camera

import android.content.Context
import android.hardware.display.DisplayManager
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.inference.DetectionResult
import com.oceanguard.ai.inference.LiveDetectionManager
import com.oceanguard.ai.ui.components.analysis.OnPhoto
import com.oceanguard.ai.ui.components.analysis.SweepGeometry
import com.oceanguard.ai.ui.components.analysis.drawEchoLabel
import com.oceanguard.ai.ui.components.analysis.drawSweepLine
import com.oceanguard.ai.ui.components.analysis.drawTracedBox
import com.oceanguard.ai.ui.components.analysis.rememberCountBump
import com.oceanguard.ai.ui.components.analysis.rememberEchoLabels
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.motionSpec
import com.oceanguard.ai.ui.motion.rememberOceanLoop
import com.oceanguard.ai.ui.theme.SonarTheme
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val ANALYSIS_FRAME_SIDE = 640
private const val LIVE_SWEEP_MS = 2_000
private const val LIVE_SWEEP_ALPHA = 0.6f
private const val MILLIS_PER_SECOND = 1_000f

private val HUD_PADDING = 12.dp
private val HUD_GAP = 8.dp
private val BADGE_GAP = 6.dp
private val BADGE_PADDING_X = 10.dp
private val BADGE_PADDING_Y = 5.dp

/** Latest frame only: the model takes seconds per pass, so older frames have no value. */
internal fun buildLiveAnalysis(): ImageAnalysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .setResolutionSelector(
        ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(ANALYSIS_FRAME_SIDE, ANALYSIS_FRAME_SIDE),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build(),
    )
    .build()

/**
 * Hands a camera frame to the detector the right way up: frames come in the sensor's orientation,
 * and both the model and the boxes on screen need the orientation of the display. Frames that
 * would be dropped are not converted, because that work would compete with the pass in progress.
 */
internal fun submitLiveFrame(
    proxy: ImageProxy,
    manager: LiveDetectionManager,
    scope: CoroutineScope,
) {
    if (manager.isBusy) {
        proxy.close()
        return
    }
    val degrees = proxy.imageInfo.rotationDegrees.toFloat()
    val bitmap = proxy.toBitmap()
    proxy.close()
    val upright = rotateBy(bitmap, degrees)
    scope.launch(Dispatchers.Default) {
        try {
            manager.processFrame(upright)
        } finally {
            upright.recycle()
        }
    }
}

/** Keeps the frames in the display's orientation. No configuration change reports a 180° turn. */
@Composable
internal fun DisplayRotationEffect(analysis: ImageAnalysis) {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(analysis) {
        val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val sync = { view.display?.rotation?.let { analysis.targetRotation = it } }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                sync()
            }
        }
        sync()
        displays.registerDisplayListener(listener, view.handler)
        onDispose { displays.unregisterDisplayListener(listener) }
    }
}

/**
 * Boxes of the last completed pass, kept until the next one replaces them, and the sonar line
 * while a pass is really running. The preview is centre-cropped, so boxes are mapped through the
 * same crop. Under "Remove animations" there is no line: the cadence badge tells it is alive.
 */
@Composable
internal fun LiveDetectionOverlay(
    detections: List<DetectionResult>,
    frameSize: Size,
    isProcessing: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val sweep by rememberOceanLoop(LIVE_SWEEP_MS, running = isProcessing)
    val sweepAlpha by animateFloatAsState(
        targetValue = if (isProcessing) LIVE_SWEEP_ALPHA else 0f,
        animationSpec = motionSpec(OceanMotion.exit()),
        label = "liveSweepAlpha",
    )
    val labels = rememberEchoLabels(detections, showConfidence = true)
    Canvas(modifier = modifier) {
        val frame = SweepGeometry.fillCenter(size, frameSize)
        detections.forEachIndexed { index, detection ->
            val rect = SweepGeometry.boxRect(detection, frame)
            drawTracedBox(rect, progress = 1f, OnPhoto.echo)
            labels.getOrNull(index)?.let {
                drawEchoLabel(it, rect, Rect(Offset.Zero, size), progress = 1f, OnPhoto.echo)
            }
        }
        if (!reduced && sweepAlpha > 0f) {
            drawSweepLine(Rect(Offset.Zero, size), sweep, OnPhoto.trace, sweepAlpha)
        }
    }
}

/** Seconds with one decimal in the user's locale: this is a measurement, not a label. */
private fun secondsText(milliseconds: Long): String =
    String.format(Locale.getDefault(), "%.1f", milliseconds / MILLIS_PER_SECOND)

/**
 * Name of the mode, the model that runs it and what was really measured on this phone. A nominal
 * rate would be meaningless here: the cadence depends on the device and its temperature.
 */
@Composable
internal fun LiveHud(
    state: LiveDetectionManager.LiveDetectionState,
    modelName: String,
    onBack: () -> Unit,
    boundsMap: MutableMap<String, Rect>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(OnPhoto.scrim, Color.Transparent)))
            .statusBarsPadding()
            .padding(HUD_PADDING),
        verticalArrangement = Arrangement.spacedBy(HUD_GAP),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(HUD_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraCircleButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDesc = stringResource(R.string.cd_go_back),
                onClick = onBack,
            )
            Column {
                Text(
                    text = stringResource(R.string.camera_mode_live),
                    style = MaterialTheme.typography.titleMedium,
                    color = OnPhoto.ink,
                )
                Text(
                    text = stringResource(R.string.sonar_live_engine, modelName),
                    style = MaterialTheme.typography.labelMedium,
                    color = OnPhoto.ink2,
                )
            }
        }
        LiveBadges(state, Modifier.spotlightTarget("live_fps", boundsMap))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveBadges(
    state: LiveDetectionManager.LiveDetectionState,
    modifier: Modifier = Modifier,
) {
    val cadence = state.cadenceMs
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(BADGE_GAP),
        verticalArrangement = Arrangement.spacedBy(BADGE_GAP),
    ) {
        HudBadge(
            if (cadence == null) {
                stringResource(R.string.sonar_live_cadence_measuring)
            } else {
                stringResource(R.string.sonar_live_cadence, secondsText(cadence))
            },
        )
        if (state.frameCount > 0) {
            HudBadge(stringResource(R.string.sonar_live_latency, secondsText(state.inferenceTimeMs)))
        }
        DetectionCountBadge(state.detections.size)
        if (state.savedCount > 0) {
            HudBadge(stringResource(R.string.live_saved_count, state.savedCount))
        }
    }
}

@Composable
private fun DetectionCountBadge(count: Int) {
    if (count == 0) return
    val bump by rememberCountBump(count)
    HudBadge(
        text = stringResource(R.string.live_detection_count, count),
        background = OnPhoto.echo,
        color = OnPhoto.onEcho,
        modifier = Modifier.graphicsLayer {
            scaleX = bump
            scaleY = bump
        },
    )
}

@Composable
private fun HudBadge(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = OnPhoto.scrim,
    color: Color = OnPhoto.ink,
) {
    Text(
        text = text,
        style = SonarTheme.type.dataSmall,
        color = color,
        modifier = modifier
            .background(background, CircleShape)
            .padding(horizontal = BADGE_PADDING_X, vertical = BADGE_PADDING_Y),
    )
}
