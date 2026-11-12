package com.oceanguard.ai.ui.screens.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesNames
import com.oceanguard.ai.ui.components.SpeciesSpriteImage
import com.oceanguard.ai.ui.theme.SonarTheme

internal val SPECIES_TOUCH_TARGET = 48.dp
private val SPECIES_BADGE = 40.dp
private val SPECIES_SPRITE = 32.dp
private val SPECIES_RING = 2.dp
private val SPECIES_CLUSTER_RADIUS = 14.dp

/**
 * A cell of species observations that is still closed. It is a rounded square in the trace colour,
 * where debris clusters are amber circles, so the two kinds are told apart at a glance.
 */
@Composable
internal fun SpeciesClusterBubble(marker: SpeciesClusterMarker, size: Dp, onClick: () -> Unit) {
    val description = pluralStringResource(
        R.plurals.map_species_cluster_cd,
        marker.observationCount,
        marker.observationCount,
        marker.speciesCount,
    )
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(SPECIES_CLUSTER_RADIUS))
            .background(SonarTheme.colors.trace)
            .border(SPECIES_RING, SonarTheme.colors.ink, RoundedCornerShape(SPECIES_CLUSTER_RADIUS))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = marker.observationCount.toString(),
            style = SonarTheme.type.data,
            color = SonarTheme.colors.onTrace,
        )
    }
}

/** One species at one place: its sprite in a round badge, with a small count when seen more than once. */
@Composable
internal fun SpeciesPinBadge(
    pin: SpeciesPinMarker,
    catalog: SpeciesCatalog?,
    order: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val entry = pin.speciesKey?.let { catalog?.byKey(it) }
    val name = SpeciesNames.commonName(entry, fallback = pin.scientificName)
    val description = pluralStringResource(R.plurals.map_species_pin_cd, pin.observationCount, name, pin.observationCount)
    val ring = if (selected) SonarTheme.colors.echo else SonarTheme.colors.trace
    Box(
        modifier = Modifier
            .size(SPECIES_TOUCH_TARGET)
            .popIn(order)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(SPECIES_BADGE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(SPECIES_RING, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            SpeciesSpriteImage(spritePath = entry?.sprite, size = SPECIES_SPRITE)
        }
        if (pin.observationCount > 1) CountChip(pin.observationCount, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun CountChip(count: Int, modifier: Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(SonarTheme.colors.trace),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), style = SonarTheme.type.dataSmall, color = SonarTheme.colors.onTrace)
    }
}
