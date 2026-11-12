package com.oceanguard.ai.ui.screens.map.species

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.ui.screens.map.MapFilterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Species observations the map shows for the current date range, grouped into spots. */
@Stable
class SpeciesMapData(
    val spots: List<SpeciesSpot>,
    val catalog: SpeciesCatalog?,
) {
    val observationCount: Int = SpeciesAggregator.observationCount(spots)
    val speciesCount: Int = SpeciesAggregator.speciesCount(spots)

    /** Every spot's centre, for framing and for the globe. */
    val points: List<Pair<Double, Double>> = spots.map { it.latitude to it.longitude }

    companion object {
        val EMPTY = SpeciesMapData(emptyList(), null)
    }
}

private const val CATALOG_WAIT_POLLS = 20
private const val CATALOG_WAIT_STEP_MS = 500L

/**
 * Species observations with a position, filtered by the map's date range. The catalogue may not
 * be loaded when the map opens, so it is loaded off the main thread, and the spots are rebuilt
 * once it is there to give them their BioDex keys.
 */
@Composable
fun rememberSpeciesMapData(filters: MapFilterState): SpeciesMapData {
    val app = LocalContext.current.applicationContext as OceanGuardApp
    val catalog = app.speciesCatalog
    val observations by app.speciesCollectionRepository.observationsWithLocation
        .collectAsStateWithLifecycle(initialValue = emptyList<SpeciesObservation>())
    val revision = rememberCatalogRevision(app)
    return remember(observations, filters.dateRangeStartMs, filters.dateRangeEndMs, revision) {
        val filtered = SpeciesMapFilter.apply(observations, filters.dateRangeStartMs, filters.dateRangeEndMs)
        val keyOf = speciesKeyLookup(catalog)
        SpeciesMapData(SpeciesAggregator.spots(filtered, keyOf), catalog)
    }
}

/** Catalogue key of an observation: by scientific name, else by AphiaID; null when it is not catalogued. */
private fun speciesKeyLookup(catalog: SpeciesCatalog): (SpeciesObservation) -> String? {
    val byAphia = catalog.all().filter { it.aphiaId != null }.associateBy({ it.aphiaId }, { it.speciesKey })
    return { obs ->
        catalog.byScientificName(obs.scientificName)?.speciesKey ?: obs.aphiaId?.let { byAphia[it] }
    }
}

@Composable
private fun rememberCatalogRevision(app: OceanGuardApp): Int {
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(app) {
        if (withContext(Dispatchers.IO) { app.ensureSpeciesCatalogLoaded() }) {
            revision++
            return@LaunchedEffect
        }
        repeat(CATALOG_WAIT_POLLS) {
            if (app.speciesCatalog.isLoaded()) {
                revision++
                return@LaunchedEffect
            }
            delay(CATALOG_WAIT_STEP_MS)
        }
    }
    return revision
}
