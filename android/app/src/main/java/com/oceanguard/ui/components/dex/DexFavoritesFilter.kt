package com.oceanguard.ai.ui.components.dex

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R

/** The "favourites only" filter of the BioDex and the MarineDex, apart from Compose so it can be tested. */
object DexFavorites {
    /** [items] unchanged, or only the favourite ones when [onlyFavorites] is on. */
    fun <T> keep(items: List<T>, onlyFavorites: Boolean, isFavorite: (T) -> Boolean): List<T> =
        if (onlyFavorites) items.filter(isFavorite) else items
}

/**
 * Chip that shows only the favourite cards of a dex, with how many there are. Favourites are marked
 * with the heart of each card's page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexFavoritesChip(selected: Boolean, count: Int, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(stringResource(R.string.dex_filter_favorites, count)) },
        leadingIcon = {
            Icon(
                imageVector = if (selected) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        modifier = modifier,
    )
}

/** Shown instead of the grid when the favourites filter is on and nothing is marked yet. */
@Composable
fun DexNoFavorites(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.dex_no_favorites),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(top = 24.dp, start = 16.dp, end = 16.dp),
    )
}
