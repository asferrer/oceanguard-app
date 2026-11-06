package com.oceanguard.ai.ui.screens.models

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.BuildConfig
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.theme.SonarTheme

/** Taps on the logo that turn the advanced options on, kept as the alternative way in. */
const val ADVANCED_TAP_TARGET = 21
private const val COUNTDOWN_FROM = 5
private val LOGO_SIZE = 48.dp
private val BLOCK_GAP = 12.dp

/** What the logo tap does: nothing yet, a countdown for the last taps, or turn them on. */
internal enum class LogoTapResult { NONE, COUNTDOWN, ACTIVATE }

internal fun logoTapResult(tapCount: Int, alreadyEnabled: Boolean): LogoTapResult {
    val remaining = ADVANCED_TAP_TARGET - tapCount
    return when {
        tapCount >= ADVANCED_TAP_TARGET && !alreadyEnabled -> LogoTapResult.ACTIVATE
        remaining in 1..COUNTDOWN_FROM -> LogoTapResult.COUNTDOWN
        else -> LogoTapResult.NONE
    }
}

/** Version, description and the 21-tap access to advanced options. */
@Composable
fun AboutBlock(
    advancedEnabled: Boolean,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
        Row(horizontalArrangement = Arrangement.spacedBy(BLOCK_GAP), verticalAlignment = Alignment.CenterVertically) {
            Logo(advancedEnabled, onActivate)
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = SonarTheme.type.dataSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_about_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (advancedEnabled) {
            SonarButton(
                text = stringResource(R.string.settings_dev_mode_disable),
                onClick = onDeactivate,
                style = SonarButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Logo(advancedEnabled: Boolean, onActivate: () -> Unit) {
    val context = LocalContext.current
    var taps by remember { mutableIntStateOf(0) }
    Image(
        painter = painterResource(R.drawable.logo),
        contentDescription = stringResource(R.string.settings_cd_logo),
        modifier = Modifier
            .size(LOGO_SIZE)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                taps++
                when (logoTapResult(taps, advancedEnabled)) {
                    LogoTapResult.ACTIVATE -> {
                        taps = 0
                        onActivate()
                        Toast.makeText(context, R.string.settings_dev_mode_activated, Toast.LENGTH_LONG).show()
                    }
                    LogoTapResult.COUNTDOWN -> Toast.makeText(
                        context,
                        context.getString(R.string.settings_dev_mode_countdown, ADVANCED_TAP_TARGET - taps),
                        Toast.LENGTH_SHORT,
                    ).show()
                    LogoTapResult.NONE -> Unit
                }
            },
    )
}
