package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.ui.components.dex.DexGalleryTile
import com.oceanguard.ai.ui.components.dex.tileTextColor
import com.oceanguard.ai.ui.components.sonar.SonarEmptyState
import com.oceanguard.ai.ui.theme.SonarDarkColors
import com.oceanguard.ai.ui.theme.SonarTheme
import java.text.SimpleDateFormat
import java.util.Locale

/** Confidence from which an observation is framed as reliable, and the lower "uncertain" bound. */
private const val HIGH_CONFIDENCE = 0.75f
private const val MEDIUM_CONFIDENCE_PCT = 50
private const val HIGH_CONFIDENCE_PCT = 75

/**
 * 2-column grid of [SpeciesObservation] thumbnails for a single species.
 *
 * Each tile shows the observation photo, its confidence, an "out of range" badge when it
 * applies, and the date. Tapping a tile opens the observation.
 *
 * @param observations Observations of the species being displayed.
 * @param onObservationClick Invoked with the observation id when a tile is tapped.
 */
@Composable
fun SpeciesDexGallery(
    observations: List<SpeciesObservation>,
    speciesDisplayName: String,
    onObservationClick: (observationId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (observations.isEmpty()) {
        SonarEmptyState(
            icon = Icons.Filled.PhotoLibrary,
            title = stringResource(R.string.biodex_gallery_empty_title),
            message = stringResource(R.string.biodex_gallery_empty_message, speciesDisplayName),
            modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = observations, key = { it.id }) { obs ->
            SpeciesGalleryItem(observation = obs, onClick = { onObservationClick(obs.id) })
        }
    }
}

@Composable
private fun SpeciesGalleryItem(
    observation: SpeciesObservation,
    onClick: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }
    val colors = SonarTheme.colors
    val confidencePct = (observation.confidence * 100).toInt()
    val borderColor = when {
        observation.outOfRange -> colors.alert
        observation.confidence >= HIGH_CONFIDENCE -> colors.trace
        else -> Color.Transparent
    }
    // Over the dark scrim the dark-theme tokens are the ones that keep their contrast.
    val confidenceColor = when {
        confidencePct >= HIGH_CONFIDENCE_PCT -> SonarDarkColors.trace
        confidencePct >= MEDIUM_CONFIDENCE_PCT -> SonarDarkColors.echo
        else -> SonarDarkColors.alert
    }

    DexGalleryTile(
        imageModel = observation.thumbnailUri ?: observation.imageUri,
        contentDescription = stringResource(R.string.biodex_gallery_cd_observation),
        onClick = onClick,
        borderColor = borderColor,
        badge = if (observation.outOfRange) {
            { OutOfRangeBadge(Modifier.align(Alignment.TopEnd)) }
        } else {
            null
        },
    ) {
        Text(
            text = stringResource(R.string.sonar_percent, confidencePct),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = confidenceColor,
        )
        Text(
            text = dateFormat.format(observation.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = tileTextColor(),
        )
    }
}

@Composable
private fun OutOfRangeBadge(modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(bottomStart = 8.dp),
        color = SonarTheme.colors.alertContainer,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.biodex_badge_out_of_range),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = SonarTheme.colors.onAlertContainer,
        )
    }
}
