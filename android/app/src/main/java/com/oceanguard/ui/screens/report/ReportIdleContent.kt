package com.oceanguard.ai.ui.screens.report

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.ui.components.DataDotDatePickerDialog
import com.oceanguard.ai.ui.components.sonar.SonarEmptyState
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** Reports that match the chosen place and dates; both filters are optional. */
internal fun filterReports(
    reports: List<GeneratedReport>,
    locationName: String?,
    startMs: Long?,
    endMs: Long?,
): List<GeneratedReport> = reports.filter { report ->
    val inPlace = locationName == null || report.locationName == locationName
    val time = report.timestamp.time
    inPlace && time >= (startMs ?: Long.MIN_VALUE) && time <= (endMs ?: Long.MAX_VALUE)
}

/** Place and date filters of the saved reports list. */
@Stable
internal class SavedReportsFilter {
    var place by mutableStateOf<String?>(null)
    var startMs by mutableStateOf<Long?>(null)
    var endMs by mutableStateOf<Long?>(null)
    var pickerVisible by mutableStateOf(false)
}

/**
 * Saved reports as items of the screen's own list, under a filter row; an empty state when there
 * are none. They share the list with the setup so the whole page scrolls as one.
 */
internal fun LazyListScope.savedReportsSection(
    hasSessions: Boolean,
    reports: List<GeneratedReport>,
    filter: SavedReportsFilter,
    highlightId: Long?,
    onView: (GeneratedReport) -> Unit,
    onDelete: (GeneratedReport) -> Unit,
    onHighlightConsumed: () -> Unit,
) {
    if (reports.isEmpty()) {
        item { NoSavedReports(hasSessions) }
        return
    }
    val visible = filterReports(reports, filter.place, filter.startMs, filter.endMs)
    item {
        SavedReportsFilterRow(
            places = reports.mapNotNull { it.locationName?.takeIf(String::isNotBlank) }.distinct().sorted(),
            place = filter.place,
            onPlace = { filter.place = it },
            startMs = filter.startMs,
            endMs = filter.endMs,
            onClearDates = { filter.startMs = null; filter.endMs = null },
            onPickDates = { filter.pickerVisible = true },
        )
    }
    if (visible.isEmpty()) {
        item { NoMatchingReports() }
        return
    }
    item {
        Text(
            text = stringResource(R.string.report_saved_title, visible.size),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    items(visible, key = { it.id }) { report ->
        val highlighted = report.id == highlightId
        SavedReportCard(
            report = report,
            onClick = { onView(report) },
            onDelete = { onDelete(report) },
            highlight = highlighted,
            onHighlightFinished = { if (highlighted) onHighlightConsumed() },
            modifier = Modifier.animateItem(),
        )
    }
}

@Composable
private fun NoSavedReports(hasSessions: Boolean) {
    SonarEmptyState(
        icon = Icons.AutoMirrored.Filled.MenuBook,
        title = stringResource(R.string.report_empty_title),
        message = stringResource(
            if (hasSessions) R.string.report_empty_message_with_sessions else R.string.report_empty_message_no_sessions,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    )
}

@Composable
private fun NoMatchingReports() {
    Text(
        text = stringResource(R.string.saved_reports_empty_filtered),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}

/** Date picker for the saved reports filter; shows the days that have a report. */
@Composable
internal fun SavedReportsDatePicker(filter: SavedReportsFilter, reports: List<GeneratedReport>) {
    if (!filter.pickerVisible) return
    DataDotDatePickerDialog(
        onDismissRequest = { filter.pickerVisible = false },
        initialStartDateMillis = filter.startMs,
        initialEndDateMillis = filter.endMs,
        datesWithData = remember(reports) { reportDays(reports) },
        onConfirm = { start, end ->
            filter.startMs = start
            filter.endMs = end
            filter.pickerVisible = false
        },
    )
}

/** Calendar days that have a report, for the dots in the date picker. */
private fun reportDays(reports: List<GeneratedReport>) = reports.map {
    Instant.ofEpochMilli(it.timestamp.time).atZone(ZoneId.systemDefault()).toLocalDate()
}.toSet()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedReportsFilterRow(
    places: List<String>,
    place: String?,
    onPlace: (String?) -> Unit,
    startMs: Long?,
    endMs: Long?,
    onClearDates: () -> Unit,
    onPickDates: () -> Unit,
) {
    val format = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    val hasDates = startMs != null || endMs != null
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (places.isNotEmpty()) PlaceFilter(places, place, onPlace)
        FilterChip(
            selected = hasDates,
            onClick = { if (hasDates) onClearDates() else onPickDates() },
            label = {
                val text = if (hasDates) {
                    val from = startMs?.let { format.format(Date(it)) } ?: "…"
                    val to = endMs?.let { format.format(Date(it)) } ?: "…"
                    "$from – $to"
                } else {
                    stringResource(R.string.history_filter_date_range)
                }
                Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingIcon = if (hasDates) {
                { Icon(Icons.Filled.Clear, contentDescription = null, Modifier.size(16.dp)) }
            } else {
                null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceFilter(places: List<String>, selected: String?, onSelected: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { if (selected != null) onSelected(null) else expanded = true },
            label = {
                Text(
                    text = selected ?: stringResource(R.string.history_filter_location),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = if (selected != null) {
                { Icon(Icons.Filled.Clear, contentDescription = null, Modifier.size(16.dp)) }
            } else {
                null
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            places.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelected(name)
                        expanded = false
                    },
                )
            }
        }
    }
}
