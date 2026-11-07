package com.oceanguard.ai.inference.species

import android.graphics.Bitmap

/**
 * Detects frames that carry no visual information (black lens cap, white-out,
 * flat colour).
 *
 * Why it exists: [OrganismLocator] never returns an empty list (it falls back to a
 * centre crop), so a featureless frame still reaches the encoder. Its embedding is
 * a constant vector whose cosine against some prototype can be high, and the
 * Platt sigmoid then turns that into a very confident species. No threshold on the
 * calibrated score can tell this apart from a real match, so the frame is rejected
 * before encoding.
 */
internal object ImageContentCheck {

    /** Side of the grid the frame is sampled on; 32x32 is enough for global statistics. */
    const val SAMPLE_SIDE = 32

    /** Standard deviation of luminance (0..255) below which the frame is considered flat. */
    const val MIN_LUMA_STD = 6.0

    /** Mean luminance below which a frame is an unlit capture whatever its noise. */
    const val MAX_DARK_MEAN = 12.0

    /** True when [bitmap] has no usable content. Samples a downscaled copy. */
    fun isBlank(bitmap: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIDE, SAMPLE_SIDE, true)
        val pixels = IntArray(SAMPLE_SIDE * SAMPLE_SIDE)
        small.getPixels(pixels, 0, SAMPLE_SIDE, 0, 0, SAMPLE_SIDE, SAMPLE_SIDE)
        if (small !== bitmap) small.recycle()
        return isBlank(pixels)
    }

    /** Pure statistics over ARGB [pixels]; JVM-testable. */
    fun isBlank(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return true
        var sum = 0.0
        var sumSq = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            sum += luma
            sumSq += luma * luma
        }
        val n = pixels.size
        val mean = sum / n
        val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
        return mean < MAX_DARK_MEAN || Math.sqrt(variance) < MIN_LUMA_STD
    }
}
