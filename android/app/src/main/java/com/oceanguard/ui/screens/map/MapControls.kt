package com.oceanguard.ai.ui.screens.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.theme.SonarTheme

private val CONTROL_SIZE = 48.dp
private val CONTROL_ICON = 24.dp

/**
 * Buttons over the map: heat view, frame everything, and the layers menu with the three named
 * styles. Each is 48 dp, the minimum touch target.
 */
@Composable
fun MapFabControls(
    showHeatmap: Boolean,
    onToggleHeatmap: () -> Unit,
    onFitAll: () -> Unit,
    style: MapStyle,
    onStyleSelected: (MapStyle) -> Unit,
    modifier: Modifier = Modifier,
    boundsMap: MutableMap<String, Rect> = mutableMapOf(),
    showHeatmapButton: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The heat view counts debris only, so it has no button while only species are shown.
        if (showHeatmapButton) {
            MapControlButton(
                icon = if (showHeatmap) Icons.Filled.PushPin else Icons.Filled.Whatshot,
                description = stringResource(
                    if (showHeatmap) R.string.map_fab_cd_show_pins else R.string.map_fab_cd_show_heatmap,
                ),
                active = showHeatmap,
                onClick = onToggleHeatmap,
                modifier = Modifier.spotlightTarget("map_heatmap", boundsMap),
            )
        }
        MapControlButton(
            icon = Icons.Filled.FitScreen,
            description = stringResource(R.string.map_fab_cd_fit_all),
            onClick = onFitAll,
            modifier = Modifier.spotlightTarget("map_fit_all", boundsMap),
        )
        StyleMenuButton(style, onStyleSelected, Modifier.spotlightTarget("map_style", boundsMap))
    }
}

@Composable
private fun StyleMenuButton(
    style: MapStyle,
    onStyleSelected: (MapStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        MapControlButton(
            icon = Icons.Filled.Layers,
            description = stringResource(R.string.map_fab_cd_map_style),
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MapStyle.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    leadingIcon = {
                        if (option == style) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = {
                        onStyleSelected(option)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MapControlButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.size(CONTROL_SIZE),
        shape = CircleShape,
        color = if (active) colors.primary else colors.surfaceContainerHigh,
        contentColor = if (active) colors.onPrimary else colors.onSurface,
        border = BorderStroke(1.dp, SonarTheme.colors.lineStrong),
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(CONTROL_ICON))
        }
    }
}
