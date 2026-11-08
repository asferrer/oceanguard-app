package com.oceanguard.ai.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.inference.VlmModelManager
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.motionSpec
import com.oceanguard.ai.ui.screens.onboarding.ContributePage
import com.oceanguard.ai.ui.screens.onboarding.InfoPage
import com.oceanguard.ai.ui.screens.onboarding.ModelsPage
import com.oceanguard.ai.ui.screens.onboarding.TourPage
import com.oceanguard.ai.ui.screens.onboarding.WelcomeLogic
import com.oceanguard.ai.ui.screens.onboarding.WelcomeResult
import kotlinx.coroutines.launch

private sealed interface WelcomePage {
    data class Info(val icon: ImageVector, val title: Int, val body: Int) : WelcomePage
    data object Models : WelcomePage
    data object Contribute : WelcomePage
    data object Tour : WelcomePage
}

private val PAGES: List<WelcomePage> = listOf(
    WelcomePage.Info(Icons.Filled.WaterDrop, R.string.onboarding_page1_title, R.string.onboarding_page1_desc),
    WelcomePage.Info(Icons.Filled.CameraAlt, R.string.onboarding_page2_title, R.string.onboarding_page2_desc),
    WelcomePage.Info(Icons.Filled.Psychology, R.string.onboarding_page3_title, R.string.onboarding_page3_desc),
    WelcomePage.Info(Icons.Filled.Security, R.string.onboarding_page4_title, R.string.onboarding_page4_desc),
    WelcomePage.Info(Icons.Filled.EmojiEvents, R.string.onboarding_page5_title, R.string.onboarding_page5_desc),
    WelcomePage.Models,
    WelcomePage.Contribute,
    WelcomePage.Tour,
)
private val MODELS_INDEX = PAGES.indexOf(WelcomePage.Models)
private val LAST_INDEX = PAGES.lastIndex

private val INDICATOR_ACTIVE = 28.dp
private val INDICATOR_IDLE = 8.dp

/**
 * First-run welcome: what the app does, which model to download and what it costs, whether to
 * contribute images, and an optional guided tour. Nothing here promises accuracy or speed the app
 * has not measured.
 *
 * @param onComplete Called once with the chosen mode and whether to start the tour.
 */
@Composable
fun OnboardingScreen(onComplete: (WelcomeResult) -> Unit) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OceanGuardApp }
    val options = remember {
        WelcomeLogic.options(app::isModelDownloaded, VlmModelManager.FINETUNED_LITERTLM_PUBLISHED)
    }
    var selected by rememberSaveable { mutableStateOf(WelcomeLogic.DEFAULT_MODE) }
    val contribute by app.settingsRepository.contributeConsentGiven.collectAsStateWithLifecycle(false)
    val pager = rememberPagerState(pageCount = { PAGES.size })
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
            when (val page = PAGES[index]) {
                is WelcomePage.Info -> InfoPage(page.icon, page.title, page.body)
                WelcomePage.Models -> ModelsPage(options, selected) { selected = it }
                WelcomePage.Contribute -> {
                    val available = app.contributionRepository.isAvailable
                    ContributePage(contribute, available) { enabled ->
                        if (available) scope.launch { app.settingsRepository.setContributeConsentGiven(enabled) }
                    }
                }
                WelcomePage.Tour -> TourPage()
            }
        }
        WelcomeControls(pager) { onComplete(WelcomeResult(selected, it)) }
    }
}

@Composable
private fun WelcomeControls(pager: PagerState, finish: (startTour: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val onLast = pager.currentPage == LAST_INDEX
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageDots(pager.currentPage)
        if (onLast) {
            SonarButton(
                text = stringResource(R.string.welcome_tour_start),
                onClick = { finish(true) },
                modifier = Modifier.fillMaxWidth(),
            )
            SonarButton(
                text = stringResource(R.string.welcome_tour_later),
                onClick = { finish(false) },
                modifier = Modifier.fillMaxWidth(),
                style = SonarButtonStyle.Quiet,
            )
        } else {
            SonarButton(
                text = stringResource(R.string.onboarding_btn_continue),
                onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.AutoMirrored.Filled.ArrowForward,
            )
            SkipButton(pager.currentPage) { scope.launch { pager.animateScrollToPage(MODELS_INDEX) } }
        }
    }
}

/** Skipping the introduction lands on the model choice, which still has to be made. */
@Composable
private fun SkipButton(current: Int, onSkip: () -> Unit) {
    if (current >= MODELS_INDEX) return
    SonarButton(
        text = stringResource(R.string.onboarding_btn_skip),
        onClick = onSkip,
        modifier = Modifier.fillMaxWidth(),
        style = SonarButtonStyle.Quiet,
    )
}

@Composable
private fun PageDots(current: Int) {
    val description = stringResource(R.string.welcome_page_cd, current + 1, PAGES.size)
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PAGES.indices.forEach { index -> PageDot(active = index == current) }
    }
}

@Composable
private fun PageDot(active: Boolean) {
    val width by animateDpAsState(
        targetValue = if (active) INDICATOR_ACTIVE else INDICATOR_IDLE,
        animationSpec = motionSpec(OceanMotion.softSpring()),
        label = "dotWidth",
    )
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(Modifier.width(width).height(INDICATOR_IDLE).clip(CircleShape).background(color))
}
