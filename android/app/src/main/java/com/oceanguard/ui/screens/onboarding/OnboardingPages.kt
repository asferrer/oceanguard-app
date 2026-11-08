package com.oceanguard.ai.ui.screens.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.ui.components.ContributionPrivacyLink
import com.oceanguard.ai.ui.components.sonar.SonarRadar
import com.oceanguard.ai.ui.components.sonar.modeTimeLabel
import com.oceanguard.ai.ui.screens.home.HomeLogic
import com.oceanguard.ai.ui.theme.SonarTheme

private val PAGE_PADDING = 24.dp
private val BADGE_SIZE = 88.dp
private val RADAR_SIZE = 240.dp
private val OPTION_MIN_HEIGHT = 72.dp
private const val RADAR_ALPHA = 0.4f
private const val INTRO_RADAR_SPEED = 0.8f

/** Shared frame of the pages: centred, scrollable on short screens. */
@Composable
private fun PageFrame(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PAGE_PADDING, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

/** Icon on a faint radar, with title and text. */
@Composable
fun InfoPage(icon: ImageVector, @StringRes title: Int, @StringRes body: Int) {
    PageFrame {
        Box(contentAlignment = Alignment.Center) {
            SonarRadar(INTRO_RADAR_SPEED, Modifier.size(RADAR_SIZE).alpha(RADAR_ALPHA))
            IconBadge(icon)
        }
        PageText(stringResource(title), stringResource(body))
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, SonarTheme.colors.lineStrong),
        modifier = Modifier.size(BADGE_SIZE),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun PageText(title: String, body: String) {
    Column(
        modifier = Modifier.padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Radio list of the modes with what each one costs, so the choice is informed. */
@Composable
fun ModelsPage(options: List<WelcomeOption>, selected: AnalysisMode, onSelect: (AnalysisMode) -> Unit) {
    PageFrame {
        Text(
            text = stringResource(R.string.welcome_models_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.welcome_models_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                ModelOptionRow(option, option.mode == selected) { onSelect(option.mode) }
            }
        }
        Text(
            text = stringResource(R.string.welcome_models_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ModelOptionRow(option: WelcomeOption, selected: Boolean, onClick: () -> Unit) {
    val alpha = if (option.enabled) 1f else 0.6f
    val border = if (selected) MaterialTheme.colorScheme.primary else SonarTheme.colors.lineStrong
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected, enabled = option.enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.heightIn(min = OPTION_MIN_HEIGHT).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = option.enabled)
            OptionTexts(option, alpha, Modifier.weight(1f))
            Text(
                text = modeTimeLabel(option.mode),
                style = SonarTheme.type.dataSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
            )
        }
    }
}

@Composable
private fun OptionTexts(option: WelcomeOption, alpha: Float, modifier: Modifier) {
    val cost = when {
        !option.enabled -> stringResource(R.string.welcome_option_unavailable)
        option.needsDownload ->
            stringResource(R.string.welcome_option_download, HomeLogic.formatGb(WelcomeLogic.downloadGb(option)))
        else -> stringResource(R.string.welcome_option_included)
    }
    Column(modifier) {
        Text(
            text = stringResource(option.mode.labelRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
        Text(
            text = stringResource(option.mode.descriptionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
        Text(
            text = cost,
            style = MaterialTheme.typography.labelMedium,
            color = if (option.needsDownload) SonarTheme.colors.echo else MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Optional contribution of analysed images. Off by default; the switch is the whole row.
 * The body says what is sent (photo with its EXIF, location included, and the detections).
 * Without a destination in this build the switch is disabled and the page says so.
 */
@Composable
fun ContributePage(enabled: Boolean, available: Boolean, onToggle: (Boolean) -> Unit) {
    PageFrame {
        IconBadge(Icons.Filled.Science)
        PageText(stringResource(R.string.onboarding_contribute_title), stringResource(R.string.onboarding_contribute_body))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .heightIn(min = 56.dp)
                .alpha(if (available) 1f else DISABLED_ALPHA)
                .toggleable(value = enabled, enabled = available, role = Role.Switch, onValueChange = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.onboarding_contribute_toggle), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = enabled && available, onCheckedChange = null, enabled = available)
        }
        if (available) {
            ContributionPrivacyLink()
        } else {
            Text(
                stringResource(R.string.contribute_unavailable_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val DISABLED_ALPHA = 0.5f

/** Offer of the guided tour. The two buttons live in the shared controls. */
@Composable
fun TourPage() {
    PageFrame {
        IconBadge(Icons.Filled.Explore)
        PageText(stringResource(R.string.welcome_tour_title), stringResource(R.string.welcome_tour_body))
    }
}
