package com.oceanguard.ai.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Design tokens of the app. Read them with [SonarTheme.colors]; the Material roles are derived
 * from them in [sonarColorScheme], so `MaterialTheme.colorScheme` stays coherent.
 *
 * - [trace]: the sonar trace, primary action.
 * - [echo]: detections and findings, never decoration.
 * - [alert]: error and risk.
 * - [line] is the decorative outline; [lineStrong] reaches 3:1 for borders that identify a control.
 */
@Immutable
data class SonarColors(
    val abyss: Color,
    val deep: Color,
    val hull: Color,
    val hull2: Color,
    val hullHigh: Color,
    val line: Color,
    val lineStrong: Color,
    val ink: Color,
    val ink2: Color,
    val trace: Color,
    val onTrace: Color,
    val traceContainer: Color,
    val onTraceContainer: Color,
    val echo: Color,
    val onEcho: Color,
    val echoContainer: Color,
    val onEchoContainer: Color,
    val alert: Color,
    val onAlert: Color,
    val alertContainer: Color,
    val onAlertContainer: Color,
)

val SonarDarkColors = SonarColors(
    abyss = Color(0xFF04121D),
    deep = Color(0xFF071B2B),
    hull = Color(0xFF0C2639),
    hull2 = Color(0xFF123349),
    hullHigh = Color(0xFF0F2D43),
    line = Color(0xFF1E4660),
    lineStrong = Color(0xFF5B8199),
    ink = Color(0xFFE9F3F8),
    ink2 = Color(0xFFA9C4D2),
    trace = Color(0xFF3CE8C4),
    onTrace = Color(0xFF03261F),
    traceContainer = Color(0xFF0E4A41),
    onTraceContainer = Color(0xFFB5F7E7),
    echo = Color(0xFFFFC24D),
    onEcho = Color(0xFF2B1C00),
    echoContainer = Color(0xFF4A3610),
    onEchoContainer = Color(0xFFFFE2A6),
    alert = Color(0xFFFF9C8A),
    onAlert = Color(0xFF3D0A02),
    alertContainer = Color(0xFF5C2117),
    onAlertContainer = Color(0xFFFFDAD3),
)

/** Same roles for daylight: every text pair meets WCAG AA (see `SonarContrastTest`). */
val SonarLightColors = SonarColors(
    abyss = Color(0xFFD8E7EF),
    deep = Color(0xFFF1F7FA),
    hull = Color(0xFFFFFFFF),
    hull2 = Color(0xFFE2EDF3),
    hullHigh = Color(0xFFEAF2F6),
    line = Color(0xFFB7CAD5),
    lineStrong = Color(0xFF5F7C8D),
    ink = Color(0xFF0A2230),
    ink2 = Color(0xFF3A5565),
    trace = Color(0xFF006D5B),
    onTrace = Color(0xFFFFFFFF),
    traceContainer = Color(0xFFB8F2E3),
    onTraceContainer = Color(0xFF00201A),
    echo = Color(0xFF875900),
    onEcho = Color(0xFFFFFFFF),
    echoContainer = Color(0xFFFFE0A3),
    onEchoContainer = Color(0xFF2B1B00),
    alert = Color(0xFFB3261E),
    onAlert = Color(0xFFFFFFFF),
    alertContainer = Color(0xFFFFDAD6),
    onAlertContainer = Color(0xFF410002),
)

val LocalSonarColors = staticCompositionLocalOf { SonarDarkColors }

/** Entry point for what Material 3 has no role for: `SonarTheme.colors.echo`, `SonarTheme.type.data`. */
object SonarTheme {
    val colors: SonarColors
        @Composable @ReadOnlyComposable get() = LocalSonarColors.current

    val type: SonarTypography
        @Composable @ReadOnlyComposable get() = LocalSonarTypography.current
}

/** Maps the tokens onto Material 3 roles one to one. */
fun sonarColorScheme(c: SonarColors, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.trace, onPrimary = c.onTrace,
        primaryContainer = c.traceContainer, onPrimaryContainer = c.onTraceContainer,
        secondary = c.ink2, onSecondary = c.deep,
        secondaryContainer = c.hull2, onSecondaryContainer = c.ink,
        tertiary = c.echo, onTertiary = c.onEcho,
        tertiaryContainer = c.echoContainer, onTertiaryContainer = c.onEchoContainer,
        error = c.alert, onError = c.onAlert,
        errorContainer = c.alertContainer, onErrorContainer = c.onAlertContainer,
        background = c.deep, onBackground = c.ink,
        surface = c.hull, onSurface = c.ink,
        surfaceVariant = c.hull2, onSurfaceVariant = c.ink2,
        surfaceTint = c.trace,
        outline = c.line, outlineVariant = c.line,
        inverseSurface = c.ink, inverseOnSurface = c.deep, inversePrimary = c.traceContainer,
        scrim = Color.Black,
        surfaceDim = c.abyss, surfaceBright = c.hull2,
        surfaceContainerLowest = c.abyss, surfaceContainerLow = c.deep,
        surfaceContainer = c.hull, surfaceContainerHigh = c.hullHigh,
        surfaceContainerHighest = c.hull2,
    )
}
