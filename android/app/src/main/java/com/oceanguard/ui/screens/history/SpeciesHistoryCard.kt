package com.oceanguard.ai.ui.screens.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.displayNameFor
import com.oceanguard.ai.ui.theme.SonarTheme
import com.oceanguard.ai.utils.PhotonGeocoderClient
import java.text.DateFormat

private val SPECIES_THUMBNAIL = 100.dp
private const val NAMES_SHOWN = 2

/**
 * One species identification of the history (a photo or a gallery batch): cover photo, when, the
 * species found, how many photos it covers and the place of the first located observation.
 */
@Composable
fun SpeciesHistoryCard(
    group: SpeciesHistoryGroup,
    catalog: SpeciesCatalog,
    language: String,
    geocoder: PhotonGeocoderClient,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SpeciesCover(group.cover.imageUri)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SpeciesCardHeadline(group)
                SpeciesNames(group, catalog, language)
                SpeciesCounts(group)
                SpeciesPlace(group, geocoder)
            }
            SpeciesUploadButton(group)
        }
    }
}

@Composable
private fun SpeciesCover(uri: String) {
    Box(
        modifier = Modifier
            .size(SPECIES_THUMBNAIL)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (uri.isNotEmpty()) {
            AsyncImage(
                model = uri,
                contentDescription = stringResource(R.string.cd_session_thumbnail),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(Icons.Filled.Pets, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpeciesCardHeadline(group: SpeciesHistoryGroup) {
    val date = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = date.format(group.timeMs),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Old observations are grouped by day, so one time of day would be made up.
        if (group.analysisId != null) {
            Text(
                text = time.format(group.timeMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        text = stringResource(
            if (group.analysisId != null) R.string.history_species_card_kind else R.string.history_species_card_kind_legacy,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = SonarTheme.colors.trace,
    )
}

@Composable
private fun SpeciesNames(group: SpeciesHistoryGroup, catalog: SpeciesCatalog, language: String) {
    val names = remember(group, language) {
        group.observations.map { catalog.displayNameFor(it, language) }.distinct()
    }
    val shown = names.take(NAMES_SHOWN).joinToString(", ")
    val extra = names.size - NAMES_SHOWN
    Text(
        text = if (extra > 0) stringResource(R.string.history_species_names_more, shown, extra) else shown,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SpeciesCounts(group: SpeciesHistoryGroup) {
    Text(
        text = stringResource(R.string.history_species_counts, group.speciesCount, group.photoCount),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SpeciesPlace(group: SpeciesHistoryGroup, geocoder: PhotonGeocoderClient) {
    val location = group.observations.firstNotNullOfOrNull { it.location } ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.LocationOn,
            contentDescription = stringResource(R.string.cd_gps_location),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        com.oceanguard.ai.ui.components.PlaceNameText(
            location = location,
            geocoder = geocoder,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
