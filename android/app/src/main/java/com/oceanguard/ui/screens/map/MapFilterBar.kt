package com.oceanguard.ai.ui.screens.map

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.ui.components.DateRangeFilterChip
import com.oceanguard.ai.ui.components.MenuFilterChip
import com.oceanguard.ai.ui.components.filterLabel

/**
 * Horizontal scrollable row of filter chips for the map: debris type and date range. It stays on
 * screen with no data too, where the type chip is disabled, so the guided tour always finds it.
 */
@Composable
fun MapFilterBar(
    filterState: MapFilterState,
    onFilterChanged: (MapFilterState) -> Unit,
    availableDebrisTypes: List<DebrisType>,
    onDateRangeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MenuFilterChip(
            selectedLabel = filterState.selectedDebrisType?.filterLabel(),
            placeholder = stringResource(R.string.map_filter_debris_type),
            options = availableDebrisTypes.map { it.filterLabel() },
            onSelect = { onFilterChanged(filterState.copy(selectedDebrisType = availableDebrisTypes[it])) },
            onClear = { onFilterChanged(filterState.copy(selectedDebrisType = null)) },
        )
        DateRangeFilterChip(
            startMs = filterState.dateRangeStartMs,
            endMs = filterState.dateRangeEndMs,
            onClear = { onFilterChanged(filterState.copy(dateRangeStartMs = null, dateRangeEndMs = null)) },
            onClick = onDateRangeClick,
        )
    }
}
