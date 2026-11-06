package com.oceanguard.ai.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.R
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.ui.components.sonar.SonarRadar
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.motionSpec
import kotlinx.coroutines.delay

private const val HOLD_MS = 1_600L
private const val REDUCED_HOLD_MS = 600L
private const val RADAR_SPEED = 0.8f
private const val RADAR_ALPHA = 0.45f
private val LOGO_SIZE = 120.dp
private val RADAR_SIZE = 320.dp

/** Reveal stages: nothing, logo, name, subtitle. */
private const val STAGE_LOGO = 1
private const val STAGE_NAME = 2
private const val STAGE_SUBTITLE = 3

/**
 * Splash: the logo pops in over a slow radar, then the name and the subtitle appear, and the app
 * goes to Home or to the welcome once it knows whether the welcome was already completed.
 */
@Composable
fun SplashScreen(
    settingsRepository: SettingsRepository,
    onNavigateToHome: () -> Unit,
    onNavigateToOnboarding: () -> Unit,
) {
    val onboardingComplete by settingsRepository.onboardingComplete
        .collectAsStateWithLifecycle(initialValue = null)
    val reduced = LocalReducedMotion.current
    var stage by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        stage = STAGE_LOGO
        delay(OceanMotion.STANDARD_MS.toLong())
        stage = STAGE_NAME
        delay(OceanMotion.STANDARD_MS.toLong())
        stage = STAGE_SUBTITLE
    }
    LaunchedEffect(onboardingComplete) {
        val complete = onboardingComplete ?: return@LaunchedEffect
        delay(if (reduced) REDUCED_HOLD_MS else HOLD_MS)
        if (complete) onNavigateToHome() else onNavigateToOnboarding()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        SonarRadar(RADAR_SPEED, Modifier.size(RADAR_SIZE).alpha(RADAR_ALPHA))
        SplashContent(stage)
    }
}

@Composable
private fun SplashContent(stage: Int) {
    val logo by animateFloatAsState(
        targetValue = if (stage >= STAGE_LOGO) 1f else 0f,
        animationSpec = motionSpec(OceanMotion.popSpring()),
        label = "splashLogo",
    )
    val name by revealAlpha(stage >= STAGE_NAME, "splashName")
    val subtitle by revealAlpha(stage >= STAGE_SUBTITLE, "splashSubtitle")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = null,
            modifier = Modifier.size(LOGO_SIZE).graphicsLayer {
                scaleX = logo
                scaleY = logo
                alpha = logo.coerceIn(0f, 1f)
            },
        )
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 16.dp).alpha(name),
        )
        Text(
            text = stringResource(R.string.home_header_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(subtitle),
        )
    }
}

/** Fade-in of one stage. Under "Remove animations" it is the short cross-fade, with no movement. */
@Composable
private fun revealAlpha(visible: Boolean, label: String) = animateFloatAsState(
    targetValue = if (visible) 1f else 0f,
    animationSpec = OceanMotion.standard(),
    label = label,
)
