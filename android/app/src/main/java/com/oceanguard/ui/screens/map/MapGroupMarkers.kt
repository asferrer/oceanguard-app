package com.oceanguard.ai.ui.screens.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.ui.components.SpeciesSpriteImage
import com.oceanguard.ai.ui.theme.SonarTheme
import com.oceanguard.ai.ui.theme.healthScoreColor

/** Size of a study-zone marker: a little larger than a single pin, to read as a place. */
internal val GROUP_MARKER = 60.dp
private val GROUP_THUMB = 34.dp
private val GROUP_STEP = 9.dp
private const val STACK_LIMIT = 3

/**
 * A study zone: up to three thumbnails stacked, a ring in the average health colour on the top
 * one, and the number of photos. It opens the zone sheet for those photos.
 */
@Composable
internal fun StudyZoneMarker(group: PinGroupMarker, order: Int, onClick: () -> Unit) {
    val count = group.members.size
    val health = group.healthScore
    val description = pluralStringResource(R.plurals.map_group_cd, count, count, health)
    Box(
        modifier = Modifier
            .size(GROUP_MARKER)
            .popIn(order)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val shown = group.members.take(STACK_LIMIT)
        shown.asReversed().forEachIndexed { depth, pin ->
            val top = depth == shown.lastIndex
            val ring = if (top) healthScoreColor(health) else SonarTheme.colors.line
            val shift = GROUP_STEP * (shown.lastIndex - depth)
            AsyncImage(
                model = pin.imageUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .offset(x = -shift / 2, y = shift / 2)
                    .size(GROUP_THUMB)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(if (top) 3.dp else 2.dp, ring, CircleShape),
            )
        }
        CountBadge(count, Modifier.align(Alignment.BottomEnd))
    }
}

/** Species seen close together: their sprites overlapped, and how many observations in all. */
@Composable
internal fun SpeciesGroupBadge(group: SpeciesGroupMarker, catalog: SpeciesCatalog?, order: Int, onClick: () -> Unit) {
    val species = group.members.map { it.speciesKey ?: it.scientificName }.distinct().size
    val description = pluralStringResource(R.plurals.map_species_group_cd, group.observationCount, group.observationCount, species)
    Box(
        modifier = Modifier
            .size(GROUP_MARKER)
            .popIn(order)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val shown = group.members.take(STACK_LIMIT)
        shown.asReversed().forEachIndexed { depth, pin ->
            val shift = GROUP_STEP * (shown.lastIndex - depth)
            Box(
                modifier = Modifier
                    .offset(x = -shift / 2, y = shift / 2)
                    .size(GROUP_THUMB)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(2.dp, SonarTheme.colors.trace, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                SpeciesSpriteImage(spritePath = pin.speciesKey?.let { catalog?.byKey(it) }?.sprite, size = 26.dp)
            }
        }
        CountBadge(group.observationCount, Modifier.align(Alignment.BottomEnd), SonarTheme.colors.trace)
    }
}

@Composable
private fun CountBadge(count: Int, modifier: Modifier, color: androidx.compose.ui.graphics.Color = SonarTheme.colors.echo) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), style = SonarTheme.type.dataSmall, color = SonarTheme.colors.abyss)
    }
}
