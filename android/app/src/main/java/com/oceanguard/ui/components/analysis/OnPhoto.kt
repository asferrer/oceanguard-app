package com.oceanguard.ai.ui.components.analysis

import androidx.compose.ui.graphics.Color
import com.oceanguard.ai.ui.theme.SonarDarkColors

/**
 * Colours of whatever is drawn on a photo or on the viewfinder. A photo is the same in both app
 * themes, so these are always the dark tokens: the light ones are tuned for a light surface.
 */
internal object OnPhoto {
    private const val SCRIM_ALPHA = 0.62f

    val trace: Color = SonarDarkColors.trace
    val onTrace: Color = SonarDarkColors.onTrace
    val echo: Color = SonarDarkColors.echo
    val onEcho: Color = SonarDarkColors.onEcho
    val alert: Color = SonarDarkColors.alert
    val ink: Color = SonarDarkColors.ink
    val ink2: Color = SonarDarkColors.ink2
    val scrim: Color = SonarDarkColors.abyss.copy(alpha = SCRIM_ALPHA)
}
