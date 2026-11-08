package com.oceanguard.ai.ui.theme

/**
 * One colour per detector class, shared by live overlay, photo boxes, annotated exports and
 * video lists. Picked from the colour-blind-safe Okabe-Ito set and kept away from blue and green
 * so boxes stand out over water; the pairwise distance is enforced by a unit test.
 */
object ClassPalette {
    val ARGB: Map<String, Long> = mapOf(
        "bottle" to 0xFFE69F00,
        "can" to 0xFFF0E442,
        "fishing_net" to 0xFFD55E00,
        "glove" to 0xFFCC79A7,
        "mask" to 0xFFF2F2F2,
        "metal_debris" to 0xFF9467BD,
        "plastic_debris" to 0xFFFF8A80,
        "tire" to 0xFFA1887F,
    )

    /** Colour of a detector class, or null when it is not one of the eight. */
    fun argbOrNull(className: String): Long? = ARGB[className.lowercase()]
}
