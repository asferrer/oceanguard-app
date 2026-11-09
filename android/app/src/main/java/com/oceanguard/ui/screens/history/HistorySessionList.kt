package com.oceanguard.ai.ui.screens.history

import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.ui.components.sonar.SwipeUndoRow
import com.oceanguard.ai.ui.components.sonar.UndoableDeletes
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.theme.SonarTheme
import com.oceanguard.ai.utils.PhotonGeocoderClient
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the list does with each card; the screen decides, the list only reports taps. */
class HistoryListActions(
    val onOpen: (Long) -> Unit,
    val onToggle: (Long) -> Unit,
    val onLongPress: (Long) -> Unit,
    val onEditLocation: (Long) -> Unit,
    val onOpenSpecies: (String) -> Unit = {},
)

/** What the list needs to draw species identifications next to the debris analyses. */
class HistorySpeciesContext(val catalog: SpeciesCatalog, val language: String)

/** Analyses grouped by day under sticky headers. Swiping a card left deletes it, with undo. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistorySessionList(
    groups: List<HistoryDayGroup>,
    geocoder: PhotonGeocoderClient,
    selecting: Boolean,
    selectedIds: Set<Long>,
    deletes: UndoableDeletes<Long>,
    actions: HistoryListActions,
    boundsMap: MutableMap<String, Rect>,
    modifier: Modifier = Modifier,
    species: HistorySpeciesContext? = null,
) {
    val firstId = groups.firstOrNull()?.sessions?.firstOrNull()?.id
    val withSpecies = species != null && !selecting
    val total = groups.sumOf { it.sessions.size + if (withSpecies) it.species.size else 0 }
    val mixed = groups.any { it.species.isNotEmpty() }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "count") { SessionCount(total, mixed && withSpecies) }
        groups.forEach { group ->
            stickyHeader(key = "day-${group.day}") { DayHeader(group) }
            items(group.entries.filter { withSpecies || it is HistoryEntry.Debris }, key = { it.key }) { entry ->
                when (entry) {
                    is HistoryEntry.Debris -> SessionRow(
                        session = entry.session,
                        geocoder = geocoder,
                        selecting = selecting,
                        checked = entry.session.id in selectedIds,
                        deletes = deletes,
                        actions = actions,
                        modifier = if (entry.session.id == firstId) Modifier.spotlightTarget("history_card", boundsMap) else Modifier,
                    )
                    is HistoryEntry.Species -> SpeciesHistoryCard(
                        group = entry.group,
                        catalog = species!!.catalog,
                        language = species.language,
                        geocoder = geocoder,
                        onClick = { actions.onOpenSpecies(entry.group.key) },
                        modifier = if (reducedMotionOff()) Modifier.animateItem() else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCount(count: Int, mixed: Boolean) {
    Text(
        text = if (mixed) {
            stringResource(R.string.history_entries_count, count)
        } else if (count != 1) {
            stringResource(R.string.history_sessions_count_plural, count)
        } else {
            stringResource(R.string.history_sessions_count_singular, count)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LazyItemScope.SessionRow(
    session: DetectionSession,
    geocoder: PhotonGeocoderClient,
    selecting: Boolean,
    checked: Boolean,
    deletes: UndoableDeletes<Long>,
    actions: HistoryListActions,
    modifier: Modifier,
) {
    // Reordering is spatial motion: under "Remove animations" rows simply appear in place.
    val placement = if (LocalReducedMotion.current) Modifier else Modifier.animateItem()
    val selection = when {
        !selecting -> SelectionState.Off
        checked -> SelectionState.Checked
        else -> SelectionState.Unchecked
    }
    val card = @Composable {
        SessionCard(
            session = session,
            geocoder = geocoder,
            selection = selection,
            onClick = { if (selecting) actions.onToggle(session.id) else actions.onOpen(session.id) },
            onLongClick = { actions.onLongPress(session.id) },
            onEditLocation = { actions.onEditLocation(session.id) },
        )
    }
    if (selecting) {
        Box(modifier.then(placement)) { card() }
    } else {
        SwipeUndoRow(key = session.id, deletes = deletes, modifier = modifier.then(placement)) { card() }
    }
}

@Composable
private fun reducedMotionOff(): Boolean = !LocalReducedMotion.current

@Composable
private fun DayHeader(group: HistoryDayGroup) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = dayLabel(group),
            style = MaterialTheme.typography.titleSmall,
            color = SonarTheme.colors.ink2,
            modifier = Modifier.padding(vertical = 8.dp).semantics { heading() },
        )
    }
}

@Composable
private fun dayLabel(group: HistoryDayGroup): String = when (group.bucket) {
    DayBucket.TODAY -> stringResource(R.string.history_group_today)
    DayBucket.YESTERDAY -> stringResource(R.string.history_group_yesterday)
    DayBucket.OTHER -> {
        val locale = Locale.getDefault()
        val pattern = remember(locale) { DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMy") }
        remember(group.day, pattern) { DateTimeFormatter.ofPattern(pattern, locale).format(group.day) }
    }
}
