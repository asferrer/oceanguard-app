package com.oceanguard.ai.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.oceanguard.ai.R
import com.oceanguard.ai.inference.DetectionResult
import com.oceanguard.ai.ui.components.analysis.SweepGeometry
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.motionSpec
import com.oceanguard.ai.ui.motion.rememberOceanLoop
import com.oceanguard.ai.ui.theme.MaterialFabric
import com.oceanguard.ai.ui.theme.MaterialFishingNet
import com.oceanguard.ai.ui.theme.MaterialGlass
import com.oceanguard.ai.ui.theme.MaterialMetal
import com.oceanguard.ai.ui.theme.MaterialOther
import com.oceanguard.ai.ui.theme.MaterialPlastic
import com.oceanguard.ai.ui.theme.MaterialRubber

// ---------------------------------------------------------------------------
// Shared animation state for the three BoundingBoxOverlay overloads.
// ---------------------------------------------------------------------------

private const val PULSE_PERIOD_MS = 1_600
private const val PULSE_MIN_ALPHA = 0.4f

private data class BboxAnimState(
    val pulseAlpha    : Float,
    val entranceScale : Float,
    val entranceAlpha : Float,
)

/** Triangle wave over one loop: alpha goes from the minimum up to 1 and back, like a pulse. */
internal fun pulseAlphaAt(phase: Float): Float {
    val wave = 1f - kotlin.math.abs(2f * phase - 1f)
    return PULSE_MIN_ALPHA + (1f - PULSE_MIN_ALPHA) * wave
}

@Composable
private fun rememberBboxAnimState(detections: List<DetectionResult>): BboxAnimState {
    // The loop stops off-screen and under "Remove animations", where the boxes stay at full alpha.
    val phase by rememberOceanLoop(PULSE_PERIOD_MS)
    val pulseAlpha = if (LocalReducedMotion.current) 1f else pulseAlphaAt(phase)
    var entranceTriggered by remember { mutableStateOf(false) }
    LaunchedEffect(detections) { entranceTriggered = detections.isNotEmpty() }
    val entranceScale by animateFloatAsState(
        targetValue   = if (entranceTriggered) 1f else 0.8f,
        animationSpec = motionSpec(OceanMotion.standard()),
        label         = "bboxEntranceScale",
    )
    val entranceAlpha by animateFloatAsState(
        targetValue   = if (entranceTriggered) 1f else 0f,
        animationSpec = motionSpec(OceanMotion.standard()),
        label         = "bboxEntranceAlpha",
    )
    return BboxAnimState(pulseAlpha, entranceScale, entranceAlpha)
}

/**
 * Boxes of [detections] over an image that reports its size, with the pulse and the entrance.
 * [imageSize] is the picture's own size; when unknown the picture is taken to fill the view.
 */
@Composable
private fun DetectionLayer(
    detections: List<DetectionResult>,
    displaySize: IntSize,
    imageSize: Size,
    anim: BboxAnimState,
    showConfidence: Boolean,
    modifier: Modifier,
) {
    if (displaySize == IntSize.Zero || detections.isEmpty()) return
    Canvas(
        modifier = modifier.graphicsLayer(
            scaleX = anim.entranceScale,
            scaleY = anim.entranceScale,
            alpha = anim.entranceAlpha,
        ),
    ) {
        val strokePx = 3.dp.toPx()
        // Boxes are normalised to the picture, which `Fit` letterboxes inside the container.
        val frame = SweepGeometry.fit(size, imageSize)
        translate(frame.left, frame.top) {
            for (detection in detections) {
                drawDetection(detection, frame.width, frame.height, strokePx, anim.pulseAlpha, showConfidence)
            }
        }
    }
}

@Composable
private fun NoImagePlaceholder(modifier: Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.overlay_no_image),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// Overload 1 — ImageBitmap
// ---------------------------------------------------------------------------

/**
 * Overlays bounding boxes from [detections] on top of [imageBitmap].
 *
 * Each box is drawn with corner brackets, outer glow, and semi-transparent
 * fill, coloured by the material type inferred from [DetectionResult.className].
 * A pill-shaped label chip above each box shows the class name and, when
 * [showConfidence] is on, the confidence.
 *
 * Animations: a pulse (stopped off-screen and under "Remove animations") and an entrance
 * when detections first appear. When [imageBitmap] is null a placeholder is shown instead.
 */
@Composable
fun BoundingBoxOverlay(
    imageBitmap: ImageBitmap?,
    detections : List<DetectionResult>,
    modifier   : Modifier = Modifier,
    showConfidence: Boolean = true,
) {
    var displaySize by remember { mutableStateOf(IntSize.Zero) }
    val anim = rememberBboxAnimState(detections)
    val imageSize = imageBitmap?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size.Unspecified

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (imageBitmap == null) {
            NoImagePlaceholder(Modifier.fillMaxSize())
            return@Box
        }
        androidx.compose.foundation.Image(
            bitmap = imageBitmap,
            contentDescription = stringResource(R.string.overlay_image_cd),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().onSizeChanged { displaySize = it },
        )
        DetectionLayer(detections, displaySize, imageSize, anim, showConfidence, Modifier.fillMaxSize())
    }
}

// ---------------------------------------------------------------------------
// Overload 2 — URI string (loaded with Coil)
// ---------------------------------------------------------------------------

/**
 * URI-based overload: loads the image asynchronously with Coil and renders
 * the bounding box overlay with corner brackets, glow, and animations.
 */
@Composable
fun BoundingBoxOverlay(
    imageUri  : String?,
    detections: List<DetectionResult>,
    modifier  : Modifier = Modifier,
    showConfidence: Boolean = true,
) {
    var displaySize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(imageUri) { mutableStateOf(Size.Unspecified) }
    val anim = rememberBboxAnimState(detections)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (imageUri == null) {
            NoImagePlaceholder(Modifier.fillMaxSize())
            return@Box
        }
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(imageUri).build(),
            contentDescription = stringResource(R.string.overlay_image_cd),
            contentScale = ContentScale.Fit,
            onSuccess = { imageSize = it.painter.intrinsicSize },
            modifier = Modifier.fillMaxSize().onSizeChanged { displaySize = it },
        )
        DetectionLayer(detections, displaySize, imageSize, anim, showConfidence, Modifier.fillMaxSize())
    }
}

// ---------------------------------------------------------------------------
// Overload 3 — FillWidth bounding-box overlay
// ---------------------------------------------------------------------------

/**
 * URI-based overload that renders with [ContentScale.FillWidth] and lets the
 * height grow to match the image's aspect ratio. Used by the Results screen
 * while the inference is still running, so the captured photo is shown
 * uncropped (matching the look of the annotated thumbnail of a saved analysis).
 *
 * The caller is responsible for placing this inside a `Modifier.fillMaxWidth()`
 * container — height adapts automatically to the loaded image.
 */
@Composable
fun FitWidthBoundingBoxOverlay(
    imageUri  : String?,
    detections: List<DetectionResult>,
    modifier  : Modifier = Modifier,
    showConfidence: Boolean = true,
) {
    if (imageUri == null) {
        // A 4:3 placeholder gives the missing image a sensible height.
        NoImagePlaceholder(modifier.fillMaxWidth().aspectRatio(4f / 3f))
        return
    }
    var displaySize by remember { mutableStateOf(IntSize.Zero) }
    val anim = rememberBboxAnimState(detections)

    Box(modifier = modifier.fillMaxWidth()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(imageUri).build(),
            contentDescription = stringResource(R.string.overlay_image_cd),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().onSizeChanged { displaySize = it },
        )
        DetectionLayer(detections, displaySize, Size.Unspecified, anim, showConfidence, Modifier.matchParentSize())
    }
}
