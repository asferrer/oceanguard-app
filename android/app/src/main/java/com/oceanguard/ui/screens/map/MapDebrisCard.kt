package com.oceanguard.ai.ui.screens.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.DexSpriteImage
import com.oceanguard.ai.ui.components.dex.debrisDisplayName
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.theme.SonarTheme
import com.oceanguard.ai.ui.theme.healthScoreColor

private val SPRITE_SIZE = 64.dp
private val LIST_SPRITE = 36.dp
private val LIST_MAX_HEIGHT = 220.dp

/** What the debris list card can do besides picking a type. */
class DebrisPlaceActions(
    val onSelect: (DebrisTypeHere) -> Unit,
    val onClose: () -> Unit,
    /** Opens the zone sheet (days, report); null for a single photo. */
    val onOpenZone: (() -> Unit)?,
    /** Opens the analysis of the photo; only for a single photo. */
    val onOpenPhoto: (() -> Unit)?,
    /** Corrects the location of the photo; only for a single photo. */
    val onFixLocation: (() -> Unit)?,
)

/**
 * The debris detected at the place that was tapped, one row per MarineDex type with its sprite and
 * how many objects, as the species list does; a row opens that type's card.
 */
@Composable
fun MapDebrisListCard(place: DebrisPlace, actions: DebrisPlaceActions, modifier: Modifier = Modifier) {
    val photos = place.sessions.size
    MapCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = pluralStringResource(R.plurals.map_photos_here, photos, photos),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.history_health_score, place.healthScore),
                    style = MaterialTheme.typography.bodyMedium,
                    color = healthScoreColor(place.healthScore),
                )
            }
            IconButton(onClick = actions.onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
            }
        }
        if (place.types.isEmpty()) {
            Text(
                text = stringResource(R.string.map_debris_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.heightIn(max = LIST_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
            place.types.forEach { here -> DebrisListRow(here, actions.onSelect) }
        }
        DebrisPlaceButtons(actions)
    }
}

@Composable
private fun DebrisListRow(here: DebrisTypeHere, onSelect: (DebrisTypeHere) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onSelect(here) }.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DexSpriteImage(debrisType = here.type, size = LIST_SPRITE)
        Text(
            text = debrisDisplayName(here.type),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text("×" + here.objectCount, style = SonarTheme.type.data, color = SonarTheme.colors.echo)
    }
}

/** The steps the old cards offered: the zone sheet for a group, the analysis and its location for one photo. */
@Composable
private fun DebrisPlaceButtons(actions: DebrisPlaceActions) {
    actions.onOpenZone?.let {
        SonarButton(
            text = stringResource(R.string.map_debris_view_zone),
            onClick = it,
            style = SonarButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val open = actions.onOpenPhoto ?: return
    val fix = actions.onFixLocation ?: return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SonarButton(
            text = stringResource(R.string.map_pin_open_detail),
            onClick = open,
            style = SonarButtonStyle.Secondary,
            modifier = Modifier.weight(1f),
        )
        SonarButton(
            text = stringResource(R.string.map_pin_fix_location),
            onClick = fix,
            style = SonarButtonStyle.Secondary,
            icon = Icons.Filled.Edit,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Card of one debris type at the place: its sprite and name, how many objects in how many photos,
 * when last seen, the photos it was detected in (each opens its analysis) and its MarineDex page.
 */
@Composable
fun MapDebrisCard(
    here: DebrisTypeHere,
    onOpenPhoto: (sessionId: Long) -> Unit,
    onOpenMarineDex: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MapCard(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            BackToListButton(onBack)
            DexSpriteImage(debrisType = here.type, size = SPRITE_SIZE)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(debrisDisplayName(here.type), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = pluralStringResource(R.plurals.map_debris_objects_here, here.objectCount, here.objectCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonarTheme.colors.echo,
                )
                Text(
                    text = pluralStringResource(R.plurals.map_in_photos, here.photos.size, here.photos.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LastSeenLine(here.lastSeenMs)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
            }
        }
        CapturedPhotoStrip(
            photos = here.photos.map { it.sessionId to it.uri },
            description = stringResource(R.string.map_debris_photo_cd),
            onOpen = onOpenPhoto,
        )
        SonarButton(
            text = stringResource(R.string.map_debris_open),
            onClick = onOpenMarineDex,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
