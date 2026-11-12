package com.oceanguard.ai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.oceanguard.ai.ui.screens.models.SpeciesPackPlacement
import com.oceanguard.ai.ui.screens.models.SpeciesPackPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesCollectionRepository
import com.oceanguard.ai.data.species.SpeciesNames
import com.oceanguard.ai.ui.components.dex.DexCard
import com.oceanguard.ai.ui.components.dex.DexCollectionHeader
import com.oceanguard.ai.ui.components.SpeciesSpriteImage
import com.oceanguard.ai.ui.components.dex.DexItem
import com.oceanguard.ai.ui.components.dex.DexSpriteSlot
import com.oceanguard.ai.ui.navigation.OceanSharedKeys
import com.oceanguard.ai.ui.navigation.oceanSharedElement
import com.oceanguard.ai.ui.theme.SonarTheme

/**
 * BioDex — Pokédex-style grid of discovered marine species.
 *
 * Mirrors [MarineDexScreen] visually but is driven by [SpeciesCollectionRepository]
 * and [SpeciesCatalog]. Undiscovered entries from the catalog are appended as
 * locked cells so the user sees the full catalog size at a glance.
 *
 * Navigation into the detail screen is delegated via [onNavigateToDetail] so
 * this composable stays route-agnostic (MainActivity wires the route at
 * integration time).
 *
 * TODO(integration): Wire navigation routes "biodex" / "biodex/{speciesKey}?tab="
 *                    in MainActivity.kt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeciesDexScreen(
    repo: SpeciesCollectionRepository,
    catalog: SpeciesCatalog,
    onNavigateToDetail: (speciesKey: String) -> Unit,
    onNavigateBack: () -> Unit,
    onPackInstalled: suspend () -> Unit = {},
) {
    val vm: SpeciesDexViewModel = viewModel(factory = SpeciesDexViewModel.Factory(repo, catalog))

    val dexItems by vm.dexItems.collectAsStateWithLifecycle()
    val discoveredCount by vm.discoveredCount.collectAsStateWithLifecycle()
    // The catalogue is read from the species pack: when the pack finishes downloading here, load it
    // and redraw so the grid fills in without leaving the screen.
    var catalogVersion by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val catalogTotal = remember(catalogVersion) { vm.catalogTotal }
    // Opened right after the app starts, the catalogue may still be loading in the background: redraw once it is
    // there (it showed "3 / 0" and the discovered cards without names or sprites).
    val app = LocalContext.current.applicationContext as OceanGuardApp
    LaunchedEffect(Unit) {
        if (catalog.isLoaded()) return@LaunchedEffect
        if (withContext(Dispatchers.IO) { app.ensureSpeciesCatalogLoaded() }) {
            catalogVersion++
            vm.catalogChanged()
            return@LaunchedEffect
        }
        repeat(CATALOG_WAIT_POLLS) {
            delay(CATALOG_WAIT_STEP_MS)
            if (catalog.isLoaded()) {
                catalogVersion++
                vm.catalogChanged()
                return@LaunchedEffect
            }
        }
    }

    // Build the full grid: discovered items first, then locked placeholders for
    // entries in the catalog that the user has not yet seen.
    val discoveredKeys = dexItems.map { it.key }.toSet()
    val lockedItems = remember(catalogVersion, discoveredKeys) { buildLockedItems(catalog, discoveredKeys) }
    val allItems = dexItems + lockedItems
    var query by rememberSaveable { mutableStateOf("") }
    val visibleItems = remember(allItems, query, catalogVersion) {
        SpeciesDexSearch.filter(allItems, query, catalog::byKey)
    }

    // Derive favourites count from the already-collected items.
    val favoriteCount = dexItems.count { it.isFavorite }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.biodex_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.biodex_cd_go_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(3) }) {
                DexCollectionHeader(
                    discoveredCount = discoveredCount,
                    totalCount = catalogTotal,
                    favoriteCount = favoriteCount,
                    observationCount = dexItems.sumOf { it.count },
                    caption = stringResource(R.string.dex_progress_caption, discoveredCount, catalogTotal),
                    discoveredLabel = stringResource(R.string.biodex_stat_discovered),
                    favoritesLabel = stringResource(R.string.biodex_stat_favorites),
                    observationsLabel = stringResource(R.string.biodex_stat_observations),
                )
            }

            if (allItems.isEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    BioDexEmptyState(onPackInstalled = { scope.launch { onPackInstalled(); catalogVersion++; vm.catalogChanged() } })
                }
            }

            if (allItems.isNotEmpty()) {
                item(span = { GridItemSpan(3) }, key = "search") {
                    SpeciesDexSearchField(query = query, onQueryChange = { query = it }, modifier = Modifier.fillMaxWidth())
                }
            }

            if (allItems.isNotEmpty() && visibleItems.isEmpty()) {
                item(span = { GridItemSpan(3) }, key = "search_empty") { SearchNoResults(query.trim()) }
            }

            itemsIndexed(items = visibleItems, key = { _, item -> item.key }) { index, item ->
                DexCard(
                    item = item,
                    animationIndex = index,
                    onNavigateToDetail = onNavigateToDetail,
                    sprite = speciesGridSprite(item),
                )
            }
        }
    }
}

/** Short message shown when no discovered species matches [query]. */
@Composable
private fun SearchNoResults(query: String) {
    Text(
        text = stringResource(R.string.biodex_search_empty, query),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, start = 16.dp, end = 16.dp),
    )
}

/** Species sprite of a card; a discovered one is the shared element of the detail header. */
private fun speciesGridSprite(item: DexItem): DexSpriteSlot = { modifier, size, silhouette ->
    SpeciesSpriteImage(
        spritePath = item.spritePath,
        size = size,
        silhouette = silhouette,
        silhouetteColor = SonarTheme.colors.lineStrong,
        modifier = if (silhouette) {
            modifier
        } else {
            modifier.oceanSharedElement(OceanSharedKeys.speciesSprite(item.key))
        },
    )
}

// ---------------------------------------------------------------------------
// Helper — produce locked DexItem stubs for catalog entries not yet seen
// ---------------------------------------------------------------------------

/**
 * Produces locked [DexItem] stubs for catalog entries the user has not yet seen.
 *
 * TODO(integration): Requires SpeciesCatalog.allKeys(): List<String> — a read-only
 * enumeration of every speciesKey in the loaded catalog JSON. Add this alongside
 * SpeciesCatalog.byKey() and SpeciesCatalog.totalCount().
 */
private fun buildLockedItems(
    catalog: SpeciesCatalog,
    discoveredKeys: Set<String>,
): List<DexItem> {
    val lang = SpeciesNames.currentLanguage()
    return catalog.allKeys()
        .filter { key -> key !in discoveredKeys }
        .map { key ->
            val entry = catalog.byKey(key)
            DexItem(
                key = key,
                displayName = SpeciesNames.commonName(entry, lang),
                spritePath = entry?.sprite,
                discovered = false,
                count = 0,
                isFavorite = false,
                subtitle = entry?.scientificName,
            )
        }
}

// ---------------------------------------------------------------------------
// Empty state — shown when no species discovered and no catalog downloaded yet
// ---------------------------------------------------------------------------

@Composable
private fun BioDexEmptyState(onPackInstalled: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_fish),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.biodex_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.biodex_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        SpeciesPackPrompt(placement = SpeciesPackPlacement.INLINE, onInstalled = onPackInstalled)
    }
}

/** The BioDex waits up to CATALOG_WAIT_POLLS x CATALOG_WAIT_STEP_MS (30 s) for the catalogue to load. */
private const val CATALOG_WAIT_POLLS = 60
private const val CATALOG_WAIT_STEP_MS = 500L
