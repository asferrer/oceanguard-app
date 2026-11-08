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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.oceanguard.ai.inference.DetectionResult
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.motionSpec
import com.oceanguard.ai.ui.motion.rememberOceanLoop
import com.oceanguard.ai.ui.theme.ClassPalette
import com.oceanguard.ai.ui.theme.MaterialFabric
import com.oceanguard.ai.ui.theme.MaterialFishingNet
import com.oceanguard.ai.ui.theme.MaterialGlass
import com.oceanguard.ai.ui.theme.MaterialMetal
import com.oceanguard.ai.ui.theme.MaterialOther
import com.oceanguard.ai.ui.theme.MaterialPlastic
import com.oceanguard.ai.ui.theme.MaterialRubber

// ---------------------------------------------------------------------------
// Colour mapping: RT-DETR class name -> material colour
// Mirrors the DebrisMaterial enum colour palette so overlays are consistent
// with DebrisCard material badges.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Cached Paint objects — allocated once, reused every frame.
// Canvas rendering is always on the main thread, so no synchronisation needed.
// ---------------------------------------------------------------------------

internal val cachedTextPaint = android.graphics.Paint().apply {
    color       = android.graphics.Color.WHITE
    isAntiAlias = true
    typeface    = android.graphics.Typeface.DEFAULT_BOLD
}

internal val cachedBgPaint = android.graphics.Paint().apply {
    style       = android.graphics.Paint.Style.FILL
    isAntiAlias = true
}

internal val cachedBorderPaint = android.graphics.Paint().apply {
    color       = android.graphics.Color.argb(51, 255, 255, 255)
    style       = android.graphics.Paint.Style.STROKE
    strokeWidth = 1f
    isAntiAlias = true
}

// New material colours not present in the shared theme -- defined locally
// so they are available for open-vocabulary detections without modifying the
// theme file.
private val MaterialWood     = Color(0xFF8D6E63) // brown 400
private val MaterialPaper    = Color(0xFFD7CCC8) // brown 100
private val MaterialCeramic  = Color(0xFFFF8A65) // deep-orange 300
private val MaterialChemical = Color(0xFFEF5350) // red 400

private val CLASS_COLOR_MAP: Map<String, Color> = mapOf(
    // Plastic-family
    "bottle"           to MaterialPlastic,
    "plastic_debris"   to MaterialPlastic,
    "mask"             to MaterialPlastic,
    "bottle_cap"       to MaterialPlastic,
    "plastic_bag"      to MaterialPlastic,
    "food_wrapper"     to MaterialPlastic,
    "styrofoam"        to MaterialPlastic,
    "plastic_cup"      to MaterialPlastic,
    "straw"            to MaterialPlastic,
    "plastic_utensil"  to MaterialPlastic,
    "six_pack_ring"    to MaterialPlastic,
    "plastic_sheeting" to MaterialPlastic,
    "diaper"           to MaterialPlastic,
    "cigarette_butt"   to MaterialPlastic,
    "cigarette_lighter" to MaterialPlastic,
    // Metal-family
    "can"              to MaterialMetal,
    "metal_debris"     to MaterialMetal,
    "aerosol_can"      to MaterialMetal,
    "metal_drum"       to MaterialMetal,
    "wire_cable"       to MaterialMetal,
    "battery"          to MaterialMetal,
    "electronics"      to MaterialMetal,
    // Fishing gear
    "fishing_net"      to MaterialFishingNet,
    "fishing_line"     to MaterialFishingNet,
    "rope"             to MaterialFishingNet,
    "fishing_buoy"     to MaterialFishingNet,
    "fishing_trap"     to MaterialFishingNet,
    // Glass
    "glass_debris"     to MaterialGlass,
    "glass_bottle"     to MaterialGlass,
    "glass_jar"        to MaterialGlass,
    "glass_fragment"   to MaterialGlass,
    "light_bulb"       to MaterialGlass,
    // Rubber
    "tire"             to MaterialRubber,
    "flip_flop"        to MaterialRubber,
    "rubber_hose"      to MaterialRubber,
    // Fabric
    "glove"            to MaterialFabric,
    "fabric_debris"    to MaterialFabric,
    "clothing"         to MaterialFabric,
    "shoe"             to MaterialFabric,
    // Paper
    "cardboard"        to MaterialPaper,
    "paper"            to MaterialPaper,
    // Wood
    "wood_pallet"      to MaterialWood,
    "lumber"           to MaterialWood,
    // Ceramic
    "ceramic_fragment" to MaterialCeramic,
    "brick"            to MaterialCeramic,
    // Chemical / hazmat
    "paint_can"        to MaterialChemical,
    "oil_container"    to MaterialChemical,
    "syringe"          to MaterialChemical,
    "chemical_drum"    to MaterialChemical,
)

/**
 * Resolve an overlay colour for a given [className] (case-insensitive).
 * Falls back to [MaterialOther] when no exact match is found.
 */
internal fun colorForClass(className: String): Color =
    ClassPalette.argbOrNull(className)?.let(::Color)
        ?: CLASS_COLOR_MAP[className.lowercase()] ?: MaterialOther

private val DarkText = Color(0xFF14181C)
private val LightText = Color(0xFFFFFFFF)
private const val CONTRAST_OFFSET = 0.05f

private fun contrastRatio(a: Color, b: Color): Float {
    val hi = maxOf(a.luminance(), b.luminance())
    val lo = minOf(a.luminance(), b.luminance())
    return (hi + CONTRAST_OFFSET) / (lo + CONTRAST_OFFSET)
}

/**
 * Text colour readable on top of [background] (a class colour): whichever of dark ink or white
 * has the higher WCAG contrast. The single source for every label that sits on a class colour.
 */
internal fun onClassColor(background: Color): Color =
    if (contrastRatio(background, DarkText) >= contrastRatio(background, LightText)) DarkText else LightText

// ---------------------------------------------------------------------------
// Core drawing helpers
// ---------------------------------------------------------------------------

/**
 * Draw L-shaped corner brackets for a bounding box.
 *
 * Each corner gets two perpendicular lines with rounded caps.
 * Line length is proportional to the box dimensions, clamped to avoid
 * overshoot on tiny boxes or excessive length on large ones.
 */
internal fun DrawScope.drawCornerBrackets(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    color: Color,
    strokeWidth: Float,
    bracketFraction: Float = 0.15f,
) {
    val boxW = right - left
    val boxH = bottom - top
    val arm = (minOf(boxW, boxH) * bracketFraction).coerceIn(6f, 40f)
    val cap = StrokeCap.Round

    // Top-left corner
    drawLine(color, Offset(left, top), Offset(left + arm, top), strokeWidth, cap)
    drawLine(color, Offset(left, top), Offset(left, top + arm), strokeWidth, cap)

    // Top-right corner
    drawLine(color, Offset(right, top), Offset(right - arm, top), strokeWidth, cap)
    drawLine(color, Offset(right, top), Offset(right, top + arm), strokeWidth, cap)

    // Bottom-left corner
    drawLine(color, Offset(left, bottom), Offset(left + arm, bottom), strokeWidth, cap)
    drawLine(color, Offset(left, bottom), Offset(left, bottom - arm), strokeWidth, cap)

    // Bottom-right corner
    drawLine(color, Offset(right, bottom), Offset(right - arm, bottom), strokeWidth, cap)
    drawLine(color, Offset(right, bottom), Offset(right, bottom - arm), strokeWidth, cap)
}

/**
 * Draw a multi-layer outer glow around corner brackets.
 *
 * Three concentric layers with increasing stroke width and decreasing
 * alpha produce a subtle neon-like glow behind the main brackets.
 * Must be called BEFORE drawCornerBrackets so glow renders underneath.
 */
internal fun DrawScope.drawGlow(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    color: Color,
    baseStroke: Float,
) {
    val layers = listOf(
        Pair(baseStroke + 6f, 0.03f),  // outermost: 3% alpha, widest
        Pair(baseStroke + 4f, 0.05f),  // middle:    5% alpha
        Pair(baseStroke + 2f, 0.08f),  // innermost: 8% alpha
    )
    for ((stroke, alpha) in layers) {
        drawCornerBrackets(
            left = left, top = top, right = right, bottom = bottom,
            color = color.copy(alpha = alpha),
            strokeWidth = stroke,
        )
    }
}

/**
 * Draw one detection's bounding box (brackets + glow + fill) and pill label.
 *
 * Visual pipeline per detection:
 * 1. Semi-transparent fill inside the box area
 * 2. Outer glow (3 concentric bracket layers)
 * 3. Corner brackets (foreground, rounded caps)
 * 4. Pill-shaped label chip with white border
 *
 * Confidence modulates visual intensity: higher confidence = brighter
 * brackets/fill/glow. Labels stay at fixed 85% alpha for readability.
 *
 * @param detection     The detection to render.
 * @param scaleX        Horizontal scale factor (display width; coords are [0,1]).
 * @param scaleY        Vertical scale factor (display height; coords are [0,1]).
 * @param strokeWidthPx Stroke width in pixels (caller converts from dp).
 * @param pulseAlpha    Animated alpha multiplier for the pulse effect [0.4, 1.0].
 * @param showConfidence Whether the label carries the percentage. Off when the detector has no
 *                      real confidence (Gemma), where the number would be invented.
 */
internal fun DrawScope.drawDetection(
    detection: DetectionResult,
    scaleX: Float,
    scaleY: Float,
    strokeWidthPx: Float,
    pulseAlpha: Float = 1f,
    showConfidence: Boolean = true,
) {
    val baseColor = colorForClass(detection.className)

    // Confidence-based intensity: map [0,1] -> [0.5, 1.0]
    // Low-confidence boxes appear dimmer but never invisible.
    val confidenceIntensity = 0.5f + (detection.confidence.coerceIn(0f, 1f) * 0.5f)

    // Combine pulse animation with confidence for final alpha
    val effectiveAlpha = (pulseAlpha * confidenceIntensity).coerceIn(0f, 1f)
    val color = baseColor.copy(alpha = effectiveAlpha)

    // Scale normalized [0,1] coordinates to display pixels
    val left   = detection.x1 * scaleX
    val top    = detection.y1 * scaleY
    val right  = detection.x2 * scaleX
    val bottom = detection.y2 * scaleY

    // 1. Semi-transparent fill inside the box
    val fillAlpha = 0.08f * confidenceIntensity * pulseAlpha
    drawRect(
        color   = baseColor.copy(alpha = fillAlpha),
        topLeft = Offset(left, top),
        size    = Size(right - left, bottom - top),
    )

    // 2. Outer glow (behind brackets)
    drawGlow(
        left = left, top = top, right = right, bottom = bottom,
        color = baseColor.copy(alpha = effectiveAlpha),
        baseStroke = strokeWidthPx,
    )

    // 3. Corner brackets (foreground)
    drawCornerBrackets(
        left = left, top = top, right = right, bottom = bottom,
        color = color,
        strokeWidth = strokeWidthPx,
    )

    drawLabelChip(detection, baseColor, left, top, showConfidence)
}

/**
 * Pill with the object name above its box. It prefers the raw label the model emitted, so the
 * person reads what was recognised and not the canonical bucket, and falls back to the class name.
 */
private fun DrawScope.drawLabelChip(
    detection: DetectionResult,
    baseColor: Color,
    left: Float,
    top: Float,
    showConfidence: Boolean,
) {
    val displayName = detection.rawLabel
        ?.replace('_', ' ')
        ?.split(' ')
        ?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        ?: detection.className
    val label      = if (showConfidence) {
        "$displayName ${(detection.confidence * 100).toInt()}%"
    } else {
        displayName
    }
    val textSizePx = 12.dp.toPx()
    val paddingH   = 6.dp.toPx()
    val paddingV   = 3.dp.toPx()

    drawIntoCanvas { canvas ->
        // Reuse cached Paint objects — update only the mutable property that varies per call.
        cachedTextPaint.textSize = textSizePx
        cachedBgPaint.color      = baseColor.copy(alpha = 0.85f).toArgb()

        val textWidth  = cachedTextPaint.measureText(label)
        val chipHeight = textSizePx + paddingV * 2
        val chipRadius = chipHeight / 2f   // full pill shape

        // Position above the box with a small gap, clamped to canvas top
        val chipTop    = (top - chipHeight - 4.dp.toPx()).coerceAtLeast(0f)
        val chipBottom = chipTop + chipHeight
        val chipLeft   = left
        val chipRight  = (left + textWidth + paddingH * 2).coerceAtMost(size.width)

        val chipRect = android.graphics.RectF(chipLeft, chipTop, chipRight, chipBottom)

        canvas.nativeCanvas.drawRoundRect(chipRect, chipRadius, chipRadius, cachedBgPaint)
        canvas.nativeCanvas.drawRoundRect(chipRect, chipRadius, chipRadius, cachedBorderPaint)

        // White label text centered vertically within the pill
        canvas.nativeCanvas.drawText(
            label,
            chipLeft + paddingH,
            chipBottom - paddingV,
            cachedTextPaint,
        )
    }
}
