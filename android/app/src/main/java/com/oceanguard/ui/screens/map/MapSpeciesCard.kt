package com.oceanguard.ai.ui.screens.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesNames
import com.oceanguard.ai.ui.components.SpeciesSpriteImage
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.theme.SonarTheme
import java.text.DateFormat
import java.util.Date

private val SPRITE_SIZE = 72.dp
private val SUMMARY_SHAPE = RoundedCornerShape(12.dp)
private const val SUMMARY_ALPHA = 0.92f

/**
 * Card of the species pin that was tapped: its sprite, its name in the language of the app, when it
 * was last seen here and how many times, with the way to its BioDex page when it has one.
 *
 * @param onOpenSpecies Null for an identification with no catalogue entry, which has no page.
 */
@Composable
fun MapSpeciesCard(
    pin: SpeciesPinMarker,
    catalog: SpeciesCatalog?,
    onOpenSpecies: (() -> Unit)?,
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
        if (onOpenSpecies != null) {
            SonarButton(
                text = stringResource(R.string.map_species_open),
                onClick = onOpenSpecies,
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
