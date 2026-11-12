package com.oceanguard.ai.ui.screens.report

import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.inference.ReportType
import com.oceanguard.ai.inference.SpeciesReportContext
import com.oceanguard.ai.ui.components.sonar.SonarSection
import com.oceanguard.ai.ui.screens.SpeciesObservationMap
import com.oceanguard.ai.ui.screens.results.StatTile

private val SPECIES_THUMB = 88.dp
private val SPECIES_MAP_HEIGHT = 220.dp

/** Figures, photos and map of the species a species or combined report was written from. */
internal fun LazyListScope.speciesItems(species: ReportSpecies, onObservationClick: (Long) -> Unit) {
    item { SpeciesFigures(species) }
    item { SpeciesPhotos(species.observations, onObservationClick) }
    if (species.observations.any { it.location != null }) {
        item { SpeciesMap(species.observations, onObservationClick) }
    }
}

@Composable
private fun SpeciesFigures(species: ReportSpecies) {
    val facts = remember(species) {
        val names = species.observations.map { it.scientificName }.distinct()
        val threatened = names.count { species.catalog.byScientificName(it)?.iucnStatus?.uppercase() in SpeciesReportContext.THREATENED }
        Triple(species.observations.size, names.size, threatened)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(stringResource(R.string.report_figure_observations), facts.first.toString(), Modifier.weight(1f))
        StatTile(stringResource(R.string.report_figure_species), facts.second.toString(), Modifier.weight(1f))
        StatTile(stringResource(R.string.report_figure_threatened), facts.third.toString(), Modifier.weight(1f))
    }
}

/** One thumbnail per photo (the first observation of each); touching it opens the observation. */
@Composable
private fun SpeciesPhotos(observations: List<SpeciesObservation>, onObservationClick: (Long) -> Unit) {
    val photos = remember(observations) { observations.distinctBy { it.imageUri }.filter { it.imageUri.isNotBlank() } }
    if (photos.isEmpty()) return
    SonarSection(stringResource(R.string.report_species_photos_title)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos, key = { it.id }) { obs ->
                AsyncImage(
                    model = Uri.parse(obs.imageUri),
                    contentDescription = stringResource(R.string.report_cd_session_thumbnail),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(SPECIES_THUMB)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { onObservationClick(obs.id) },
                )
            }
        }
    }
}

@Composable
private fun SpeciesMap(observations: List<SpeciesObservation>, onObservationClick: (Long) -> Unit) {
    SonarSection(stringResource(R.string.report_species_map_title)) {
        Box(Modifier.fillMaxWidth().height(SPECIES_MAP_HEIGHT).clip(RoundedCornerShape(12.dp))) {
            SpeciesObservationMap(observations = observations, onObservationClick = onObservationClick, withGlobe = false)
        }
    }
}

/** "Species" or "Combined" next to the audience; debris reports (the original kind) carry none. */
@Composable
internal fun ReportTypeBadge(report: GeneratedReport) {
    val label = when (ReportType.fromKey(report.reportType)) {
        ReportType.SPECIES -> R.string.report_type_species
        ReportType.COMBINED -> R.string.report_type_combined
        ReportType.DEBRIS -> return
    }
    AudienceBadge(stringResource(label))
}
