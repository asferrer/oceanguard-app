package com.oceanguard.ai.ui.screens.map

import androidx.annotation.StringRes
import com.oceanguard.ai.R

/**
 * Map style URLs for MapLibre vector tiles.
 *
 * Uses OpenFreeMap: free, no API key, no registration, no usage limits.
 * For production with premium aesthetics, swap to Stadia Maps (free tier,
 * requires API key for mobile apps).
 */
object MapStyles {
    /** Light minimalist style — clean white background */
    const val POSITRON = "https://tiles.openfreemap.org/styles/positron"

    /** Dark mode style — dark background, subtle labels */
    const val DARK = "https://tiles.openfreemap.org/styles/dark"

    /** Colorful general-purpose style */
    const val LIBERTY = "https://tiles.openfreemap.org/styles/liberty"

    // Stadia Maps (uncomment + add API key for production):
    // const val ALIDADE_SMOOTH = "https://tiles.stadiamaps.com/styles/alidade_smooth.json?api_key=KEY"
    // const val ALIDADE_SMOOTH_DARK = "https://tiles.stadiamaps.com/styles/alidade_smooth_dark.json?api_key=KEY"

    /** Returns the appropriate style URL based on dark mode preference. */
    fun forDarkMode(isDark: Boolean): String = if (isDark) DARK else POSITRON
}

/** The three base maps the user can choose between, named as they appear in the layers menu. */
enum class MapStyle(val url: String, @StringRes val labelRes: Int) {
    LIGHT(MapStyles.POSITRON, R.string.map_style_light),
    DARK(MapStyles.DARK, R.string.map_style_dark),
    TERRAIN(MapStyles.LIBERTY, R.string.map_style_terrain);

    companion object {
        /** Style the map opens with, before the user picks one. */
        fun forDarkMode(isDark: Boolean): MapStyle = if (isDark) DARK else LIGHT
    }
}
