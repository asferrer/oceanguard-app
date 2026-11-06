package com.oceanguard.ai.ui.screens.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.ui.components.DateRangeFilterChip
import com.oceanguard.ai.ui.components.MenuFilterChip
import com.oceanguard.ai.ui.components.filterLabel
import com.oceanguard.ai.ui.theme.SonarTheme

/** The text field that filters the list as the user types. */
@Composable
fun HistorySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text(stringResource(R.string.history_search_placeholder)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.cd_search)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_clear))
                }
            }
        },
        shape = MaterialTheme.shapes.medium,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = SonarTheme.colors.lineStrong,
        ),
        singleLine = true,
    )
}

/** Chips for debris type, place and date range. A chip that is on clears itself when tapped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryFilterChips(
    filter: HistoryFilter,
    onFilterChange: (HistoryFilter) -> Unit,
    availableTypes: List<DebrisType>,
    availablePlaces: List<String>,
    onDateRangeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MenuFilterChip(
            selectedLabel = filter.debrisType?.filterLabel(),
            placeholder = stringResource(R.string.history_filter_debris_type),
            options = availableTypes.map { it.filterLabel() },
            onSelect = { index -> onFilterChange(filter.copy(debrisType = availableTypes[index])) },
            onClear = { onFilterChange(filter.copy(debrisType = null)) },
        )
        if (availablePlaces.isNotEmpty()) {
            MenuFilterChip(
                selectedLabel = filter.placeName,
                placeholder = stringResource(R.string.history_filter_location),
                options = availablePlaces,
                onSelect = { index -> onFilterChange(filter.copy(placeName = availablePlaces[index])) },
                onClear = { onFilterChange(filter.copy(placeName = null)) },
            )
        }
        DateRangeFilterChip(
            startMs = filter.dateRangeStartMs,
            endMs = filter.dateRangeEndMs,
            onClear = { onFilterChange(filter.copy(dateRangeStartMs = null, dateRangeEndMs = null)) },
            onClick = onDateRangeClick,
        )
    }
}
