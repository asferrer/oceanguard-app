package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesNames
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.data.species.displayNameFor
import com.oceanguard.ai.data.species.speciesKeyFor
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.theme.SonarTheme
import java.text.DateFormat
import java.util.Date

/**
 * Detail of one species identification of the history: each photo with the species found in it,
 * their confidence, the place and the date. A species opens its BioDex sheet; the photo opens the
 * observation. [observations] are the rows of the analysis, newest first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeciesAnalysisDetailScreen(
    observations: List<SpeciesObservation>,
    catalog: SpeciesCatalog,
    onNavigateBack: () -> Unit,
    onOpenSpecies: (String) -> Unit,
    onOpenObservation: (Long) -> Unit,
) {
    val language = remember { SpeciesNames.currentLanguage() }
    val photos = remember(observations) { observations.groupBy { it.imageUri }.toList() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.species_analysis_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.biodex_obs_detail_cd_go_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { AnalysisSummary(observations) }
            items(photos, key = { it.first }) { (uri, rows) ->
                PhotoBlock(uri, rows, catalog, language, onOpenSpecies, onOpenObservation)
            }
        }
    }
}

@Composable
private fun AnalysisSummary(observations: List<SpeciesObservation>) {
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val newest = observations.maxOfOrNull { it.timestamp.time } ?: 0L
    SonarCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(format.format(Date(newest)), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(
                    R.string.history_species_counts,
                    observations.map { it.scientificName }.distinct().size,
                    observations.map { it.imageUri }.distinct().size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PhotoBlock(
    uri: String,
    rows: List<SpeciesObservation>,
    catalog: SpeciesCatalog,
    language: String,
    onOpenSpecies: (String) -> Unit,
    onOpenObservation: (Long) -> Unit,
) {
    SonarCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(
                model = uri,
                contentDescription = stringResource(R.string.biodex_obs_detail_cd_image),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { onOpenObservation(rows.first().id) },
            )
            rows.forEach { SpeciesRow(it, catalog, language, onOpenSpecies, onOpenObservation) }
        }
    }
}

@Composable
private fun SpeciesRow(
    observation: SpeciesObservation,
    catalog: SpeciesCatalog,
    language: String,
    onOpenSpecies: (String) -> Unit,
    onOpenObservation: (Long) -> Unit,
) {
    val key = catalog.speciesKeyFor(observation)
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenObservation(observation.id) },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = catalog.displayNameFor(observation, language),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = observation.scientificName,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.species_analysis_confidence, (observation.confidence * 100).toInt()),
                style = MaterialTheme.typography.labelMedium,
                color = SonarTheme.colors.trace,
            )
            if (observation.outOfRange) {
                Text(
                    text = stringResource(R.string.biodex_badge_out_of_range),
                    style = MaterialTheme.typography.labelMedium,
                    color = SonarTheme.colors.alert,
                )
            }
        }
        observation.location?.let {
            Text(
                text = "%.4f, %.4f".format(it.latitude, it.longitude),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (key != null) {
            TextButton(onClick = { onOpenSpecies(key) }) { Text(stringResource(R.string.species_analysis_open_biodex)) }
        }
    }
}
