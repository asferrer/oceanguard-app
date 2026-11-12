package com.oceanguard.ai.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.data.species.SpeciesDexEntry
import kotlinx.coroutines.delay

/**
 * What the Home BioDex card shows.
 *
 * @param sprites    Catalogue sprite paths of the most recently discovered species (null path: placeholder).
 * @param discovered Species discovered so far.
 * @param total      Catalogue size; 0 while the catalogue is not loaded.
 */
data class BioDexHomeState(
    val sprites: List<String?> = emptyList(),
    val discovered: Int = 0,
    val total: Int = 0,
)

private const val RECENT_SPECIES = 5
private const val CATALOG_WAIT_POLLS = 60
private const val CATALOG_WAIT_STEP_MS = 500L

/** Sprite paths of the [limit] species discovered last (newest first), looked up in the catalogue with [entryOf]. */
fun recentSpeciesSprites(
    entries: List<SpeciesDexEntry>,
    entryOf: (String) -> SpeciesCatalogEntry?,
    limit: Int = RECENT_SPECIES,
): List<String?> =
    entries.sortedByDescending { it.firstSeenAt }.take(limit).map { entryOf(it.speciesKey)?.sprite }

/**
 * State of the BioDex card: discoveries from the species repository and the catalogue size. The catalogue
 * may still be loading when Home first composes, so it is polled (like the BioDex screen does) and the
 * state is rebuilt once it is there.
 */
@Composable
fun rememberBioDexHome(app: OceanGuardApp): BioDexHomeState {
    val repo = app.speciesCollectionRepository
    val catalog = app.speciesCatalog
    val entries by repo.allDexEntries.collectAsStateWithLifecycle(initialValue = emptyList())
    val discovered by repo.discoveredCount().collectAsStateWithLifecycle(initialValue = 0)
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(catalog) {
        repeat(CATALOG_WAIT_POLLS) {
            if (catalog.isLoaded()) {
                revision++
                return@LaunchedEffect
            }
            delay(CATALOG_WAIT_STEP_MS)
        }
    }
    return remember(entries, discovered, revision) {
        BioDexHomeState(recentSpeciesSprites(entries, catalog::byKey), discovered, catalog.totalCount())
    }
}
