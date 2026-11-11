package com.oceanguard.ai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.rememberOceanLoop

// ---------------------------------------------------------------------------
// Species bounding-box overlay
//
// Distinct from the debris [BoundingBoxOverlay]: species detections carry a
// pixel-space [com.oceanguard.ai.data.BoundingBox] (left/top + width/height in
// ORIGINAL image pixels) and an already-localised display label, NOT an
// RT-DETR class name. This file converts those to normalised [0,1] rects and
// draws them with the BioDex accent palette, reusing the bracket/glow drawing
// primitives from [BoundingBoxOverlay] so the visual language stays consistent.
// ---------------------------------------------------------------------------

/** BioDex accent for organism boxes — cyan, distinct from debris material hues. */
private val SpeciesBoxColor = Color(0xFF00D4FF)

/**
 * A single organism box in normalised [0,1] image coordinates plus its label.
 *
 * @param left/top/right/bottom Fractions of image width/height, each in [0,1].
 * @param label                 Localised species name shown in the pill chip.
 * @param confidence            Identification confidence in [0,1] (0 = unknown).
 */
data class NormBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val label: String,
    val confidence: Float,
)

/**
 * Pure conversion of a pixel-space organism box into a normalised [NormBox].
 *
 * The source [com.oceanguard.ai.data.BoundingBox] stores top-left origin
 * coordinates plus width/height in ORIGINAL image pixels; this divides by the
 * intrinsic image dimensions to produce [0,1] fractions that the overlay can
 * scale to whatever display size the image is laid out at. Coordinates are
 * clamped to [0,1] so a padded box that slightly overshoots the frame still
 * renders inside the canvas.
 *
 * Android-free (no Bitmap, no Canvas) so the math is unit-testable on plain JVM.
 *
 * @return null when the image dimensions are non-positive or the box is
 *         degenerate (zero/negative area) — caller renders the image box-less.
 */
fun bboxToNormBox(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    imgWidth: Int,
    imgHeight: Int,
    label: String,
    confidence: Float,
): NormBox? {
    if (imgWidth <= 0 || imgHeight <= 0) return null
    if (width <= 0f || height <= 0f) return null
    val w = imgWidth.toFloat()
    val h = imgHeight.toFloat()
    val left   = (x / w).coerceIn(0f, 1f)
    val top    = (y / h).coerceIn(0f, 1f)
    val right  = ((x + width) / w).coerceIn(0f, 1f)
    val bottom = ((y + height) / h).coerceIn(0f, 1f)
    if (right <= left || bottom <= top) return null
    return NormBox(left, top, right, bottom, label, confidence)
}

/**
 * Draw one organism box: glow + corner brackets + pill label, in [color].
 *
 * Reuses [drawGlow] / [drawCornerBrackets] from [BoundingBoxOverlay] and the
 * shared cached Paint objects so the label chip matches the debris overlay
 * style. [box] coords are normalised [0,1]; [scaleX]/[scaleY] map them to the
 * rendered image's pixel size.
 */
internal fun DrawScope.drawSpeciesBox(
    box: NormBox,
    scaleX: Float,
    scaleY: Float,
    strokeWidthPx: Float,
    pulseAlpha: Float,
    color: Color,
) {
    val left   = box.left * scaleX
    val top    = box.top * scaleY
    val right  = box.right * scaleX
    val bottom = box.bottom * scaleY

    drawRect(
        color   = color.copy(alpha = 0.08f * pulseAlpha),
        topLeft = Offset(left, top),
        size    = Size(right - left, bottom - top),
    )
    drawGlow(left, top, right, bottom, color.copy(alpha = pulseAlpha), strokeWidthPx)
    drawCornerBrackets(left, top, right, bottom, color.copy(alpha = pulseAlpha), strokeWidthPx)

    drawSpeciesLabel(box, left, top, color)
}

/**
 * Text of the label chip so that it fits in [maxWidth]: the percentage is always kept and, when the whole text does not
 * fit, the name is shortened with an ellipsis ("Possible group: Sharks… 74%"). [measure] gives the drawn width of a text.
 * Android-free so it can be unit-tested.
 */
internal fun fitSpeciesLabel(name: String, confidence: Float, maxWidth: Float, measure: (String) -> Float): String {
    val conf = if (confidence > 0f) " ${(confidence * 100).toInt()}%" else ""
    val full = "$name$conf"
    if (measure(full) <= maxWidth) return full
    var n = name.length
    while (n > 1) {
        n--
        val candidate = name.take(n).trimEnd() + "…" + conf
        if (measure(candidate) <= maxWidth) return candidate
    }
    return "…$conf".trimStart()
}

/**
 * Horizontal start of a chip of [chipWidth] that wants to start at [left] on a canvas of [canvasWidth]: moved left when
 * it would run past the right edge, never before 0.
 */
internal fun speciesChipLeft(left: Float, chipWidth: Float, canvasWidth: Float): Float =
    left.coerceAtMost(canvasWidth - chipWidth).coerceAtLeast(0f)

/** Draw the pill-shaped label chip above the box, kept inside the canvas and with the percentage always visible. */
private fun DrawScope.drawSpeciesLabel(
    box: NormBox,
    left: Float,
    top: Float,
    color: Color,
) {
    val textSizePx = 12.dp.toPx()
    val paddingH   = 6.dp.toPx()
    val paddingV   = 3.dp.toPx()

    drawIntoCanvas { canvas ->
        cachedTextPaint.textSize = textSizePx
        cachedBgPaint.color      = color.copy(alpha = 0.85f).toArgb()

        val label      = fitSpeciesLabel(box.label, box.confidence, size.width - paddingH * 2) {
            cachedTextPaint.measureText(it)
        }
        val chipWidth  = cachedTextPaint.measureText(label) + paddingH * 2
        val chipHeight = textSizePx + paddingV * 2
        val chipRadius = chipHeight / 2f
        val chipTop    = (top - chipHeight - 4.dp.toPx()).coerceAtLeast(0f)
        val chipBottom = chipTop + chipHeight
        val chipLeft   = speciesChipLeft(left, chipWidth, size.width)
        val chipRect   = android.graphics.RectF(chipLeft, chipTop, chipLeft + chipWidth, chipBottom)

        canvas.nativeCanvas.drawRoundRect(chipRect, chipRadius, chipRadius, cachedBgPaint)
        canvas.nativeCanvas.drawRoundRect(chipRect, chipRadius, chipRadius, cachedBorderPaint)
        canvas.nativeCanvas.drawText(label, chipLeft + paddingH, chipBottom - paddingV, cachedTextPaint)
    }
}

/**
 * Overlays species [boxes] on the image at [imageUri].
 *
 * Renders the photo with the given [contentScale] (default [ContentScale.Fit])
 * and draws every box on top with a pulsing BioDex-cyan bracket. An empty
 * [boxes] list (null-bbox / whole-frame fallback) shows the plain image with no
 * box — never crashes. The boxes' [NormBox] coordinates are produced by
 * [bboxToNormBox] from the result's pixel bbox + the analysed image dimensions.
 */
@Composable
fun SpeciesBoundingBoxOverlay(
    imageUri: String?,
    boxes: List<NormBox>,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    if (imageUri == null) return
    var displaySize by remember { mutableStateOf(IntSize.Zero) }
    val pulseAlpha = rememberSpeciesPulse()

    Box(modifier = modifier) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(imageUri).build(),
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { displaySize = it },
        )
        if (displaySize != IntSize.Zero && boxes.isNotEmpty()) {
            val scaleX = displaySize.width.toFloat()
            val scaleY = displaySize.height.toFloat()
            Canvas(modifier = Modifier.matchParentSize()) {
                val strokePx = 3.dp.toPx()
                for (box in boxes) {
                    drawSpeciesBox(box, scaleX, scaleY, strokePx, pulseAlpha, SpeciesBoxColor)
                }
            }
        }
    }
}

/**
 * FillWidth variant: the image keeps its aspect ratio and the height grows to
 * fit, matching the look the BioDex result screen uses while a photo is shown
 * uncropped. Caller places it inside a `Modifier.fillMaxWidth()` container.
 */
@Composable
fun SpeciesFitWidthBoxOverlay(
    imageUri: String?,
    boxes: List<NormBox>,
    modifier: Modifier = Modifier,
) {
    if (imageUri == null) return
    var displaySize by remember { mutableStateOf(IntSize.Zero) }
    val pulseAlpha = rememberSpeciesPulse()

    Box(modifier = modifier.fillMaxWidth()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(imageUri).build(),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { displaySize = it },
        )
        if (displaySize != IntSize.Zero && boxes.isNotEmpty()) {
            val scaleX = displaySize.width.toFloat()
            val scaleY = displaySize.height.toFloat()
            Canvas(modifier = Modifier.matchParentSize()) {
                val strokePx = 3.dp.toPx()
                for (box in boxes) {
                    drawSpeciesBox(box, scaleX, scaleY, strokePx, pulseAlpha, SpeciesBoxColor)
                }
            }
        }
    }
}

/** Continuous alpha pulse [0.4, 1.0] shared by the species overlays. */
@Composable
private fun rememberSpeciesPulse(): Float {
    // The loop stops off-screen and under "Remove animations", where the boxes stay at full alpha.
    val phase by rememberOceanLoop(SPECIES_PULSE_PERIOD_MS)
    return if (LocalReducedMotion.current) 1f else speciesPulseAlphaAt(phase)
}

private const val SPECIES_PULSE_PERIOD_MS = 1_800
private const val SPECIES_PULSE_MIN_ALPHA = 0.45f

/** Triangle wave over one loop: alpha goes from the minimum up to 1 and back. */
internal fun speciesPulseAlphaAt(phase: Float): Float =
    SPECIES_PULSE_MIN_ALPHA + (1f - SPECIES_PULSE_MIN_ALPHA) * (1f - kotlin.math.abs(2f * phase - 1f))
