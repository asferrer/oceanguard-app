package com.oceanguard.ai.ui.screens.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesNames
import com.oceanguard.ai.ui.components.SpeciesSpriteImage
import com.oceanguard.ai.ui.screens.map.species.SpeciesPhoto
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.theme.SonarTheme
import java.text.DateFormat
import java.util.Date

private val SPRITE_SIZE = 72.dp
private val LIST_SPRITE = 36.dp
private val LIST_MAX_HEIGHT = 220.dp
private val SUMMARY_SHAPE = RoundedCornerShape(12.dp)
private val PHOTO_SIZE = 72.dp
private val PHOTO_SHAPE = RoundedCornerShape(10.dp)
private const val SUMMARY_ALPHA = 0.92f

/**
 * Card of the species pin that was tapped: its sprite, its name in the language of the app, when it
 * was last seen here and how many times, the photos it was identified in here (as the zone sheet
 * does for debris) and the way to its BioDex page when it has one.
 *
 * @param onOpenSpecies Null for an identification with no catalogue entry, which has no page.
 * @param onOpenPhoto Opens the identification of one photo, by observation id.
 */
@Composable
fun MapSpeciesCard(
    pin: SpeciesPinMarker,
    catalog: SpeciesCatalog?,
    onOpenSpecies: (() -> Unit)?,
    onOpenPhoto: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = pin.speciesKey?.let { catalog?.byKey(it) }
    val name = SpeciesNames.commonName(entry, fallback = pin.scientificName)
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    SonarCard(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            SpeciesSpriteImage(spritePath = entry?.sprite, size = SPRITE_SIZE)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                if (name != pin.scientificName) {
                    Text(
                        text = pin.scientificName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = pluralStringResource(R.plurals.map_species_card_count, pin.observationCount, pin.observationCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonarTheme.colors.trace,
                )
                Text(
                    text = stringResource(R.string.map_species_last_seen, dateFormat.format(Date(pin.lastSeenMs))),
                    style = SonarTheme.type.dataSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
            }
        }
        if (pin.photos.isNotEmpty()) {
            SpeciesPhotoStrip(pin.photos, onOpenPhoto)
        }
        if (onOpenSpecies != null) {
            SonarButton(
                text = stringResource(R.string.map_species_open),
                onClick = onOpenSpecies,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The photos a species was identified in, newest first, in a row that scrolls; each opens its identification. */
@Composable
private fun SpeciesPhotoStrip(photos: List<SpeciesPhoto>, onOpenPhoto: (Long) -> Unit) {
    val description = stringResource(R.string.map_species_photo_cd)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.map_species_photos),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos, key = { it.observationId }) { photo ->
                AsyncImage(
                    model = photo.uri,
                    contentDescription = description,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(PHOTO_SIZE)
                        .clip(PHOTO_SHAPE)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(role = Role.Button) { onOpenPhoto(photo.observationId) },
                )
            }
        }
    }
}

/** The species of a group of nearby observations, one row each; a row opens that species' card. */
@Composable
fun MapSpeciesListCard(
    group: SpeciesGroupMarker,
    catalog: SpeciesCatalog?,
    onSelect: (SpeciesPinMarker) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SonarCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pluralStringResource(R.plurals.map_species_card_count, group.observationCount, group.observationCount),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
            }
        }
        Column(Modifier.heightIn(max = LIST_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
            group.members.forEach { pin -> SpeciesListRow(pin, catalog, onSelect) }
        }
    }
}

@Composable
private fun SpeciesListRow(pin: SpeciesPinMarker, catalog: SpeciesCatalog?, onSelect: (SpeciesPinMarker) -> Unit) {
    val entry = pin.speciesKey?.let { catalog?.byKey(it) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onSelect(pin) }.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeciesSpriteImage(spritePath = entry?.sprite, size = LIST_SPRITE)
        Text(
            text = SpeciesNames.commonName(entry, fallback = pin.scientificName),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text("\u00d7" + pin.observationCount, style = SonarTheme.type.data, color = SonarTheme.colors.trace)
    }
}

/** Top-start card when only species are shown: what the zone card does for debris. */
@Composable
fun SpeciesSummaryCard(observations: Int, species: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = SUMMARY_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = SUMMARY_ALPHA),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(10.dp).background(SonarTheme.colors.trace, CircleShape))
            Column {
                Text(
                    text = pluralStringResource(R.plurals.map_subtitle_observations, observations, observations),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = pluralStringResource(R.plurals.map_subtitle_species, species, species),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
