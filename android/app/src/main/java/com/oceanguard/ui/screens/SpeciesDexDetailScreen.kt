package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesCollectionRepository
import com.oceanguard.ai.data.species.SpeciesNames
import com.oceanguard.ai.data.species.enrichment.SpeciesEnrichment
import com.oceanguard.ai.ui.components.SpeciesSpriteImage
import com.oceanguard.ai.ui.components.dex.DexHero
import com.oceanguard.ai.ui.components.dex.DexTabContent
import com.oceanguard.ai.ui.navigation.OceanSharedKeys
import com.oceanguard.ai.ui.navigation.oceanSharedElement
import kotlinx.coroutines.launch

/**
 * BioDex detail of one species: hero with the sprite that flew in from the grid and the favourite
 * heart, then the Info, Gallery and Map tabs.
 *
 * - [onObservationClick] opens one observation, from the gallery or from a map marker.
 * - [initialTab] picks the tab to land on: 0 = Info, 1 = Gallery, 2 = Map.
 * - [enrichmentProvider] fetches the optional online data; it returns null when the user has
 *   switched online enrichment off, and the screen degrades to a hint.
 */
@Composable
fun SpeciesDexDetailScreen(
    speciesKey: String,
    repo: SpeciesCollectionRepository,
    catalog: SpeciesCatalog,
    onNavigateBack: () -> Unit,
    onObservationClick: (observationId: Long) -> Unit = {},
    initialTab: Int = 0,
    enrichmentProvider: (suspend (aphiaId: Long?, scientificName: String) -> SpeciesEnrichment?)? = null,
) {
    val vm: SpeciesDexViewModel = viewModel(factory = SpeciesDexViewModel.Factory(repo, catalog))
    val scope = rememberCoroutineScope()

    val catalogEntry = remember(speciesKey) { vm.catalogEntry(speciesKey) }
    val observations by vm.observationsForSpecies(speciesKey)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val dexEntry = vm.dexItems.collectAsStateWithLifecycle().value.firstOrNull { it.key == speciesKey }

    val lang = remember { SpeciesNames.currentLanguage() }
    val displayName = remember(catalogEntry, lang) {
        SpeciesNames.commonName(catalogEntry, lang, fallback = speciesKey)
    }
    val isFavorite = dexEntry?.isFavorite ?: false
    var selectedTab by remember { mutableIntStateOf(initialTab.coerceIn(0, 2)) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DexHero(
            isFavorite = isFavorite,
            onNavigateBack = onNavigateBack,
            onToggleFavorite = { scope.launch { vm.toggleFavorite(speciesKey, isFavorite) } },
            backDescription = stringResource(R.string.biodex_detail_cd_go_back),
            favoriteDescription = stringResource(
                if (isFavorite) R.string.biodex_detail_cd_remove_favorite else R.string.biodex_detail_cd_add_favorite,
            ),
        ) { spriteModifier, size ->
            SpeciesSpriteImage(
                spritePath = catalogEntry?.spritePath,
                size = size,
                modifier = spriteModifier.oceanSharedElement(OceanSharedKeys.speciesSprite(speciesKey)),
            )
        }

        SpeciesTabRow(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
        DexTabContent(selectedTab = selectedTab, modifier = Modifier.fillMaxSize()) { tab ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> SpeciesInfoTabContent(
                        speciesKey = speciesKey,
                        displayName = displayName,
                        catalogEntry = catalogEntry,
                        dexEntry = dexEntry,
                        lang = lang,
                        enrichmentProvider = enrichmentProvider,
                    )
                    1 -> SpeciesDexGallery(
                        observations = observations,
                        speciesDisplayName = displayName,
                        onObservationClick = onObservationClick,
                    )
                    else -> SpeciesObservationMap(
                        observations = observations,
                        onObservationClick = onObservationClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeciesTabRow(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val tabs = listOf(
        stringResource(R.string.biodex_detail_tab_info),
        stringResource(R.string.biodex_detail_tab_gallery),
        stringResource(R.string.biodex_detail_tab_map),
    )
    PrimaryTabRow(selectedTabIndex = selectedTab) {
        tabs.forEachIndexed { index, title ->
            Tab(
                selected = selectedTab == index,
                onClick = { onTabSelected(index) },
                text = { Text(title) },
            )
        }
    }
}
