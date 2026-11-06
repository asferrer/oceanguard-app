package com.oceanguard.ai.ui.components.sonar

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.motionSpec

enum class SonarButtonStyle { Primary, Secondary, Quiet, Danger }

private val BUTTON_MIN_HEIGHT = 52.dp
private val BUTTON_ICON_SIZE = 20.dp
private val BUTTON_ICON_GAP = 8.dp

/**
 * Press feedback of the whole app: the element sinks with the `snap` spring while it is held.
 * Pass the same [interactionSource] to the `clickable`. Does nothing under "Remove animations".
 */
@Composable
fun Modifier.springPress(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) OceanMotion.PRESS_SCALE else 1f,
        animationSpec = motionSpec(OceanMotion.snapSpring()),
        label = "springPress",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** The app's button: 52 dp tall, with the spring press. Long labels wrap instead of clipping. */
@Composable
fun SonarButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SonarButtonStyle = SonarButtonStyle.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = BUTTON_MIN_HEIGHT).springPress(interaction),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = sonarButtonColors(style),
        border = if (style == SonarButtonStyle.Secondary) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        } else {
            null
        },
        interactionSource = interaction,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(BUTTON_ICON_SIZE))
            Spacer(Modifier.width(BUTTON_ICON_GAP))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun sonarButtonColors(style: SonarButtonStyle): ButtonColors {
    val scheme = MaterialTheme.colorScheme
    return when (style) {
        SonarButtonStyle.Primary -> ButtonDefaults.buttonColors()
        SonarButtonStyle.Secondary -> ButtonDefaults.buttonColors(
            containerColor = scheme.surfaceVariant,
            contentColor = scheme.onSurface,
        )
        SonarButtonStyle.Quiet -> ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = scheme.primary,
        )
        SonarButtonStyle.Danger -> ButtonDefaults.buttonColors(
            containerColor = scheme.error,
            contentColor = scheme.onError,
        )
    }
}
