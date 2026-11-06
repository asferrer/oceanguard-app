package com.oceanguard.ai.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DebrisType
import java.text.DateFormat
import java.util.Date

/**
 * Chip that opens a menu of [options]. Once one is chosen the chip shows it and clears itself
 * when tapped. With nothing to choose from it is disabled rather than opening an empty menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuFilterChip(
    selectedLabel: String?,
    placeholder: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilterChip(
            selected = selectedLabel != null,
            enabled = selectedLabel != null || options.isNotEmpty(),
            onClick = { if (selectedLabel != null) onClear() else open = true },
            label = { Text(selectedLabel ?: placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = selectedLabel?.let { { ClearIcon() } },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(index)
                        open = false
                    },
                )
            }
        }
    }
}

/** Chip for a date range: opens the picker, and once a range is set shows it and clears it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeFilterChip(
    startMs: Long?,
    endMs: Long?,
    onClear: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val format = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val hasRange = startMs != null || endMs != null
    val label = if (hasRange) {
        val from = startMs?.let { format.format(Date(it)) } ?: "…"
        val to = endMs?.let { format.format(Date(it)) } ?: "…"
        "$from – $to"
    } else {
        stringResource(R.string.history_filter_date_range)
    }
    FilterChip(
        selected = hasRange,
        onClick = if (hasRange) onClear else onClick,
        modifier = modifier,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (hasRange) ({ ClearIcon() }) else null,
    )
}

@Composable
private fun ClearIcon() {
    Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
}

/** "PLASTIC_BAG" as "Plastic bag". */
fun DebrisType.filterLabel(): String =
    name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
