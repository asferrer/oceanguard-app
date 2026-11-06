package com.oceanguard.ai.ui.components.analysis

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.oceanguard.ai.inference.DetectionResult
import kotlin.math.max
import kotlin.math.min

/**
 * Where a picture really lands on screen. Detections are normalised to the picture, not to the
 * view that shows it, so mapping them needs the rectangle the picture occupies.
 */
internal object SweepGeometry {

    /** Rectangle of an image shown with `ContentScale.Fit`: whole, letterboxed if needed. */
    fun fit(container: Size, image: Size): Rect = scaled(container, image, ::min)

    /** Rectangle of a frame shown centre-cropped, as `PreviewView` does: it overflows the view. */
    fun fillCenter(container: Size, image: Size): Rect = scaled(container, image, ::max)

    /** Screen rectangle of [detection] inside [frame]. */
    fun boxRect(detection: DetectionResult, frame: Rect): Rect = Rect(
        left = frame.left + detection.x1 * frame.width,
        top = frame.top + detection.y1 * frame.height,
        right = frame.left + detection.x2 * frame.width,
        bottom = frame.top + detection.y2 * frame.height,
    )

    private fun scaled(container: Size, image: Size, pick: (Float, Float) -> Float): Rect {
        val whole = Rect(Offset.Zero, container)
        if (image.isUnspecifiedOrEmpty()) return whole
        val scale = pick(container.width / image.width, container.height / image.height)
        val width = image.width * scale
        val height = image.height * scale
        return Rect(
            offset = Offset((container.width - width) / 2f, (container.height - height) / 2f),
            size = Size(width, height),
        )
    }

    private fun Size.isUnspecifiedOrEmpty(): Boolean =
        this == Size.Unspecified || width <= 0f || height <= 0f
}
