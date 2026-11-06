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
)

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
) {
    val firstId = groups.firstOrNull()?.sessions?.firstOrNull()?.id
    val total = groups.sumOf { it.sessions.size }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "count") { SessionCount(total) }
        groups.forEach { group ->
            stickyHeader(key = "day-${group.day}") { DayHeader(group) }
            items(group.sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    geocoder = geocoder,
                    selecting = selecting,
                    checked = session.id in selectedIds,
                    deletes = deletes,
                    actions = actions,
                    modifier = if (session.id == firstId) Modifier.spotlightTarget("history_card", boundsMap) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun SessionCount(count: Int) {
    Text(
        text = if (count != 1) {
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
