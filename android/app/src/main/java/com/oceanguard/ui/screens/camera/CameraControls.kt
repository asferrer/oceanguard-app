package com.oceanguard.ai.ui.screens.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.analysis.OnPhoto
import com.oceanguard.ai.ui.components.sonar.springPress
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.rememberOceanLoop
import kotlin.math.abs

private const val SHUTTER_PULSE_MS = 1_600
private const val SHUTTER_RING_MIN = 3f
private const val SHUTTER_RING_SWING = 1f
private const val MUTED_ALPHA = 0.85f
private const val DISABLED_ALPHA = 0.5f

private val CIRCLE_BUTTON_SIZE = 50.dp
private val CIRCLE_ICON_SIZE = 26.dp
private val SHUTTER_SIZE = 80.dp
private val SHUTTER_ICON_SIZE = 36.dp
private val SHUTTER_SPINNER_SIZE = 32.dp
private val SHUTTER_SPINNER_WIDTH = 3.dp
private val SEGMENT_MIN_HEIGHT = 48.dp
private val SEGMENT_PADDING = 18.dp
private val TOGGLE_PADDING = 4.dp
private val TOGGLE_GAP = 4.dp
private val PILL_PADDING_X = 12.dp
private val PILL_PADDING_Y = 6.dp
private val DOCK_END_PADDING = 24.dp
private val DOCK_BOTTOM_PADDING = 28.dp
private val DOCK_GAP = 10.dp

/** The two ways of working with the camera. Switching is lateral, not a deeper screen. */
internal enum class CaptureMode { PHOTO, CONTINUOUS }

/** Circular icon button that stays legible on any scene, without a bar over the viewfinder. */
@Composable
internal fun CameraCircleButton(
    icon: ImageVector,
    contentDesc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stateDesc: String? = null,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(CIRCLE_BUTTON_SIZE)
            .background(color = OnPhoto.scrim, shape = CircleShape)
            .semantics { stateDesc?.let { stateDescription = it } },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = OnPhoto.ink,
            modifier = Modifier.size(CIRCLE_ICON_SIZE),
        )
    }
}

/**
 * Round shutter of the camera and of the continuous scan. Its ring breathes while it is ready, in
 * the draw phase, so the loop does not recompose the viewfinder's controls on every frame. The
 * ring stays still under "Remove animations".
 */
@Composable
internal fun ShutterButton(
    isCapturing: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val phase by rememberOceanLoop(SHUTTER_PULSE_MS, running = !isCapturing)
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = !isCapturing,
        shape = CircleShape,
        color = OnPhoto.scrim,
        interactionSource = interaction,
        modifier = modifier
            .size(SHUTTER_SIZE)
            .springPress(interaction)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .drawWithContent {
                drawContent()
                val swing = 1f - abs(phase * 2f - 1f)
                val width = (SHUTTER_RING_MIN + SHUTTER_RING_SWING * swing).dp.toPx()
                drawCircle(OnPhoto.ink, size.minDimension / 2f - width / 2f, style = Stroke(width))
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isCapturing && !LocalReducedMotion.current) {
                CircularProgressIndicator(
                    modifier = Modifier.size(SHUTTER_SPINNER_SIZE),
                    color = OnPhoto.ink,
                    strokeWidth = SHUTTER_SPINNER_WIDTH,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = null,
                    tint = OnPhoto.ink,
                    modifier = Modifier.size(SHUTTER_ICON_SIZE),
                )
            }
        }
    }
}

/** Selector between photo and continuous scan, present in both modes. */
@Composable
internal fun CaptureModeToggle(
    selected: CaptureMode,
    continuousEnabled: Boolean,
    onSelect: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(OnPhoto.scrim, CircleShape)
            .padding(TOGGLE_PADDING)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(TOGGLE_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSegment(
            label = stringResource(R.string.camera_mode_capture),
            selected = selected == CaptureMode.PHOTO,
            enabled = true,
            onClick = { onSelect(CaptureMode.PHOTO) },
        )
        ModeSegment(
            label = stringResource(R.string.camera_mode_live),
            selected = selected == CaptureMode.CONTINUOUS,
            enabled = continuousEnabled,
            onClick = { onSelect(CaptureMode.CONTINUOUS) },
        )
    }
}

@Composable
private fun ModeSegment(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val text = when {
        selected -> OnPhoto.onTrace
        enabled -> OnPhoto.ink
        else -> OnPhoto.ink.copy(alpha = DISABLED_ALPHA)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = SEGMENT_MIN_HEIGHT)
            .springPress(interaction)
            .clip(CircleShape)
            .background(if (selected) OnPhoto.trace else Color.Transparent)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                interactionSource = interaction,
                indication = ripple(),
                onClick = { if (!selected) onClick() },
            )
            .padding(horizontal = SEGMENT_PADDING),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = text)
    }
}

/** Short text on the viewfinder, on its own scrim so it does not depend on the scene. */
@Composable
internal fun CameraPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = OnPhoto.ink.copy(alpha = MUTED_ALPHA),
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(OnPhoto.scrim, CircleShape)
            .padding(horizontal = PILL_PADDING_X, vertical = PILL_PADDING_Y),
    )
}

/** [fallback] as a hint, or the failure of the last capture, which TalkBack also announces. */
@Composable
internal fun CaptureHint(failed: Boolean, fallback: String) {
    if (failed) {
        CameraPill(
            text = stringResource(R.string.sonar_camera_capture_failed),
            color = OnPhoto.alert,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    } else {
        CameraPill(text = fallback)
    }
}

/**
 * Column of capture controls. At the bottom in portrait; against the right edge in landscape, so
 * the hand that holds the phone falls on the shutter. The shutter is the visual anchor, with the
 * hint under it in both orientations.
 */
@Composable
internal fun BoxScope.CaptureDock(
    isLandscape: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val placement = if (isLandscape) {
        Modifier.align(Alignment.CenterEnd).padding(end = DOCK_END_PADDING)
    } else {
        Modifier.align(Alignment.BottomCenter).padding(bottom = DOCK_BOTTOM_PADDING)
    }
    Column(
        modifier = Modifier.navigationBarsPadding().then(placement),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DOCK_GAP),
        content = content,
    )
}
