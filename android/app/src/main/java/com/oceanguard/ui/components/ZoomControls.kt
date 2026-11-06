package com.oceanguard.ai.ui.components

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.components.analysis.OnPhoto
import com.oceanguard.ai.ui.theme.SonarTheme

private const val MIN_ZOOM = 1f
private const val MIN_SLIDER_RANGE_END = 1.1f
private const val ACTIVE_TRACK_ALPHA = 0.8f
private const val INACTIVE_TRACK_ALPHA = 0.3f

private val RATIO_WIDTH = 56.dp
private val SIDE_PADDING = 32.dp

/**
 * Horizontal zoom slider with ratio display (e.g. "2.5x"). It lives on the viewfinder, so it uses
 * the on-photo colours in both themes.
 */
@Composable
fun ZoomSlider(
    zoomRatio: Float,
    maxZoomRatio: Float,
    onZoomChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SIDE_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%.1fx".format(zoomRatio),
            style = SonarTheme.type.dataSmall,
            color = OnPhoto.ink,
            modifier = Modifier.width(RATIO_WIDTH),
        )
        Slider(
            value = zoomRatio,
            onValueChange = onZoomChanged,
            valueRange = MIN_ZOOM..maxZoomRatio.coerceAtLeast(MIN_SLIDER_RANGE_END),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = OnPhoto.ink,
                activeTrackColor = OnPhoto.ink.copy(alpha = ACTIVE_TRACK_ALPHA),
                inactiveTrackColor = OnPhoto.ink.copy(alpha = INACTIVE_TRACK_ALPHA),
            ),
        )
    }
}

/**
 * Pinch-to-zoom. The gesture detector is keyed on nothing and reads the latest values: keying it
 * on the zoom would restart it, and cut the pinch, on every step of the pinch itself.
 */
@Composable
fun Modifier.pinchToZoom(
    currentZoom: Float,
    maxZoom: Float,
    onZoomChanged: (Float) -> Unit,
): Modifier {
    val zoom by rememberUpdatedState(currentZoom)
    val limit by rememberUpdatedState(maxZoom)
    val onChanged by rememberUpdatedState(onZoomChanged)
    return pointerInput(Unit) {
        detectTransformGestures { _, _, gestureZoom, _ ->
            onChanged((zoom * gestureZoom).coerceIn(MIN_ZOOM, limit))
        }
    }
}
