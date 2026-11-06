package com.oceanguard.ai.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.ui.components.sonar.AnalysisModePicker
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.components.sonar.SonarRadar
import com.oceanguard.ai.ui.components.sonar.rememberSonarRadarState
import com.oceanguard.ai.ui.components.sonar.springPress
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.motionSpec
import com.oceanguard.ai.ui.theme.SonarTheme

private val SELECTOR_MIN_HEIGHT = 48.dp
private val RADAR_MAX_SIZE = 260.dp
private val SCAN_BUTTON_SIZE = 96.dp
private val SCAN_ICON_SIZE = 28.dp

/** Chooses between scanning debris or species. Both stay visible, as tabs of one group. */
@Composable
fun ScanTargetSelector(
    target: ScanTarget,
    onTarget: (ScanTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, SonarTheme.colors.lineStrong),
    ) {
        Row(Modifier.selectableGroup().padding(4.dp)) {
            TargetOption(
                selected = target == ScanTarget.DEBRIS,
                label = stringResource(R.string.home_target_debris),
                onClick = { onTarget(ScanTarget.DEBRIS) },
                modifier = Modifier.weight(1f),
            ) { tint -> Icon(Icons.Filled.DeleteOutline, null, Modifier.size(20.dp), tint) }
            TargetOption(
                selected = target == ScanTarget.SPECIES,
                label = stringResource(R.string.home_target_species),
                onClick = { onTarget(ScanTarget.SPECIES) },
                modifier = Modifier.weight(1f),
            ) { tint -> Icon(painterResource(R.drawable.ic_fish), null, Modifier.size(20.dp), tint) }
        }
    }
}

@Composable
private fun TargetOption(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (selected) scheme.primary else Color.Transparent,
        animationSpec = motionSpec(OceanMotion.fast()),
        label = "targetContainer",
    )
    val content = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
    Surface(
        modifier = modifier
            .heightIn(min = SELECTOR_MIN_HEIGHT)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.heightIn(min = SELECTOR_MIN_HEIGHT),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon(content)
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * The radar with the scan button in its middle. The sweep speed comes from the mode, and the line
 * only runs while scanning is possible, so a sweeping radar always means "ready".
 */
@Composable
fun ScanRadar(
    mode: AnalysisMode,
    target: ScanTarget,
    enabled: Boolean,
    onScan: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val radar = rememberSonarRadarState()
    Box(
        modifier = modifier.widthIn(max = RADAR_MAX_SIZE).fillMaxWidth().aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        SonarRadar(
            radPerSec = mode.radarRadPerSec,
            modifier = Modifier.fillMaxSize(),
            state = radar,
            running = enabled,
        )
        ScanButton(target, enabled) { center ->
            radar.ping()
            onScan(center)
        }
    }
}

@Composable
private fun ScanButton(target: ScanTarget, enabled: Boolean, onClick: (Offset) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    var center by remember { mutableStateOf(Offset.Zero) }
    val scheme = MaterialTheme.colorScheme
    val description = stringResource(
        if (target == ScanTarget.DEBRIS) R.string.home_scan_cd_debris else R.string.home_scan_cd_species,
    )
    Surface(
        onClick = { onClick(center) },
        enabled = enabled,
        interactionSource = interaction,
        shape = CircleShape,
        color = if (enabled) scheme.primary else scheme.surfaceVariant,
        contentColor = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant,
        modifier = Modifier
            .size(SCAN_BUTTON_SIZE)
            .springPress(interaction)
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .semantics { contentDescription = description },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(SCAN_ICON_SIZE))
            Text(
                text = stringResource(if (enabled) R.string.home_scan_action else R.string.home_scan_preparing),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** "Choose photos" and the mode chip. Debris offers gallery or files; species go straight to photos. */
@Composable
fun ScanSources(
    target: ScanTarget,
    enabled: Boolean,
    onPick: (PhotoSource) -> Unit,
    onDownload: (AnalysisMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth()) {
            SonarButton(
                text = stringResource(R.string.home_pick_photos),
                onClick = {
                    if (target == ScanTarget.SPECIES) onPick(PhotoSource.SPECIES) else menuOpen = true
                },
                modifier = Modifier.fillMaxWidth(),
                style = SonarButtonStyle.Secondary,
                enabled = enabled,
                icon = Icons.Filled.PhotoLibrary,
            )
            PhotoSourceMenu(menuOpen, { menuOpen = false }) { source ->
                menuOpen = false
                onPick(source)
            }
        }
        AnalysisModePicker(onDownload = onDownload)
    }
}

/** Gallery or files, for debris. */
@Composable
private fun PhotoSourceMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSource: (PhotoSource) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.home_btn_gallery_multi)) },
            leadingIcon = { Icon(Icons.Filled.Photo, contentDescription = null) },
            onClick = { onSource(PhotoSource.GALLERY) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.home_btn_files_multi)) },
            leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
            onClick = { onSource(PhotoSource.FILES) },
        )
    }
}
