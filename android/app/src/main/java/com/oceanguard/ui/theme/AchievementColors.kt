package com.oceanguard.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Achievements keeps its own look, apart from the app-wide palette. These are the
 * colours it always had; nothing else in the app may use them.
 */
object AchievementColors {
    val GradientStart = Color(0xFF0A1929)
    val GradientMid = Color(0xFF003D5C)
    val GradientEnd = Color(0xFF00A896)
    val Ring = Color(0xFF00A896)
    val ProgressBar = Color(0xFF05C9A8)
}

/** Wraps the Achievements screen so it keeps the Material roles it was designed with. */
@Composable
fun AchievementsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (LocalIsDarkTheme.current) {
            AchievementsDarkColorScheme
        } else {
            AchievementsLightColorScheme
        },
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}

// ---------------------------------------------------------------------------
// Material 3 neutral surfaces — deep-ocean dark mode
// ---------------------------------------------------------------------------

private val Neutral10 = Color(0xFF0A1929)  // deepest ocean background
private val Neutral20 = Color(0xFF0D2137)
private val Neutral30 = Color(0xFF112C47)
private val Neutral80 = Color(0xFFB0C8D4)
private val Neutral90 = Color(0xFFCCDDE8)
private val Neutral95 = Color(0xFFE5EFF5)
private val Neutral99 = Color(0xFFF5FAFC)

private val NeutralVariant30 = Color(0xFF1A3549)
private val NeutralVariant50 = Color(0xFF4A6880)
private val NeutralVariant80 = Color(0xFFA0BCC8)

private val ErrorBase = Color(0xFFCF6679)
private val ErrorContainer = Color(0xFF93000A)

// ---------------------------------------------------------------------------
// Dark colour scheme — default for underwater use
// High contrast on dark backgrounds mirrors diving conditions.
// ---------------------------------------------------------------------------

val AchievementsDarkColorScheme = darkColorScheme(
    // Primary: ocean blue
    primary = OceanBlueLight,
    onPrimary = Color(0xFF00293D),
    primaryContainer = OceanBlueDark,
    onPrimaryContainer = Color(0xFFC9E6FF),

    // Secondary: teal / bioluminescent
    secondary = OceanGreenLight,
    onSecondary = Color(0xFF003730),
    secondaryContainer = OceanGreenDark,
    onSecondaryContainer = Color(0xFFA8F5E8),

    // Tertiary: coral accent for alerts
    tertiary = CoralRed,
    onTertiary = Color(0xFF4A0000),
    tertiaryContainer = Color(0xFF6B1E1E),
    onTertiaryContainer = Color(0xFFFFDAD5),

    // Error
    error = ErrorBase,
    onError = Color(0xFF690005),
    errorContainer = ErrorContainer,
    onErrorContainer = Color(0xFFFFDAD6),

    // Backgrounds — deep ocean dark
    background = Neutral10,
    onBackground = Neutral90,

    // Surface hierarchy
    surface = Neutral20,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,

    // Outlines
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant30,

    // Inverse (used for snackbars, tooltips)
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    inversePrimary = OceanBlue,

    // Scrim for modal dialogs
    scrim = Color(0xFF000000),

    // Surface containers — used by cards, bottom sheets, etc.
    surfaceContainerLowest = Color(0xFF060F18),
    surfaceContainerLow = Neutral10,
    surfaceContainer = Neutral20,
    surfaceContainerHigh = Neutral30,
    surfaceContainerHighest = Color(0xFF1A3449),
)

// ---------------------------------------------------------------------------
// Light colour scheme — for surface environments / post-dive review
// ---------------------------------------------------------------------------

val AchievementsLightColorScheme = lightColorScheme(
    primary = OceanBlue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC9E6FF),
    onPrimaryContainer = Color(0xFF001E30),

    secondary = OceanGreen,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFA8F5E8),
    onSecondaryContainer = Color(0xFF002019),

    tertiary = Color(0xFFC0392B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD5),
    onTertiaryContainer = Color(0xFF410002),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Neutral99,
    onBackground = Color(0xFF1A1C1E),

    surface = Neutral99,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDCE3EB),
    onSurfaceVariant = Color(0xFF42484F),

    outline = Color(0xFF72787E),
    outlineVariant = Color(0xFFC2C8CE),

    inverseSurface = Color(0xFF2F3133),
    inverseOnSurface = Neutral95,
    inversePrimary = OceanBlueLight,

    scrim = Color(0xFF000000),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5FAFE),
    surfaceContainer = Color(0xFFEFF4F8),
    surfaceContainerHigh = Color(0xFFE9EFF3),
    surfaceContainerHighest = Color(0xFFE3E9ED),
)
