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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesNames
import com.oceanguard.ai.ui.components.SpeciesSpriteImage
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.theme.SonarTheme

private val SPRITE_SIZE = 64.dp
private val LIST_SPRITE = 36.dp
private val LIST_MAX_HEIGHT = 220.dp
private val SUMMARY_SHAPE = RoundedCornerShape(12.dp)
private val PHOTO_SIZE = 56.dp
private val PHOTO_SHAPE = RoundedCornerShape(10.dp)
private const val SUMMARY_ALPHA = 0.92f

/**
 * Card of the species pin that was tapped: its sprite, its name in the language of the app, when it
 * was last seen here and how many times, the photos it was identified in here (as the zone sheet
 * does for debris) and the way to its BioDex page when it has one.
 *
 * @param onOpenSpecies Null for an identification with no catalogue entry, which has no page.
 * @param onOpenPhoto Opens the identification of one photo, by observation id.
 * @param onBack Back to the list the species was picked from; null when it was tapped on the map.
 */
@Composable
fun MapSpeciesCard(
    pin: SpeciesPinMarker,
    catalog: SpeciesCatalog?,
    onOpenSpecies: (() -> Unit)?,
    onOpenPhoto: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val entry = pin.speciesKey?.let { catalog?.byKey(it) }
    val name = SpeciesNames.commonName(entry, fallback = pin.scientificName)
    MapCard(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            onBack?.let { BackToListButton(it) }
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
                    text = pluralStringResource(R.plurals.map_in_photos, pin.photos.size, pin.photos.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LastSeenLine(pin.lastSeenMs)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
            }
        }
        if (pin.photos.isNotEmpty()) {
            CapturedPhotoStrip(
                photos = pin.photos.map { it.observationId to it.uri },
                description = stringResource(R.string.map_species_photo_cd),
                onOpen = onOpenPhoto,
            )
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

/**
 * Photos taken at a place, newest first, in a row that scrolls: the species card and the debris card
 * use it alike. Each photo is (id, picture) and [onOpen] gets the id of the one tapped.
 */
@Composable
internal fun CapturedPhotoStrip(photos: List<Pair<Long, String>>, description: String, onOpen: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.map_species_photos),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos, key = { it.first }) { (id, uri) ->
                AsyncImage(
                    model = uri,
                    contentDescription = description,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(PHOTO_SIZE)
                        .clip(PHOTO_SHAPE)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(role = Role.Button) { onOpen(id) },
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
    MapCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pluralStringResource(R.plurals.map_photos_here, group.photoCount, group.photoCount),
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

/** Back from one item's card to the list of the place it was picked from. */
@Composable
internal fun BackToListButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.map_back_to_list))
    }
}

/** Gap between the blocks of a map card: header, photos and button never touch. */
private val CARD_GAP = 12.dp

/** A [SonarCard] whose blocks are spaced, for the cards that open over the map. */
@Composable
internal fun MapCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SonarCard(modifier = modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CARD_GAP), content = content)
    }
}

/** "Last seen: 11 Nov" in one small line; the year only when it is not the current one. */
@Composable
internal fun LastSeenLine(lastSeenMs: Long) {
    val date = remember(lastSeenMs) { shortDate(lastSeenMs) }
    Text(
        text = stringResource(R.string.map_last_seen_short, date),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Day and short month in the order of the locale, with the year only for another year. */
private fun shortDate(ms: Long): String {
    val locale = java.util.Locale.getDefault()
    val year = { t: Long -> java.util.Calendar.getInstance().apply { timeInMillis = t }.get(java.util.Calendar.YEAR) }
    val skeleton = if (year(ms) == year(System.currentTimeMillis())) "dMMM" else "dMMMyyyy"
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    return java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(ms))
}
