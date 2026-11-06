package com.oceanguard.ai.ui.screens.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.ZoneAggregator
import com.oceanguard.ai.ui.components.PlaceNameText
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A saved analysis. Positions 0, 1 and 2 (photo, date and place, health) are the ones the
 * first-use tour scrolls to, so they must stay in that order.
 */
@Composable
internal fun SessionDetailContent(
    session: DetectionSession,
    onEditLocation: () -> Unit,
    onEditDate: () -> Unit,
    boundsMap: MutableMap<String, Rect>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val repeated = remember(session) { findRepeatedObjects(session.debrisList.map { it.type.name }) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Box(Modifier.spotlightTarget("detail_image", boundsMap)) { SessionPhoto(session) } }
        item {
            Box(Modifier.spotlightTarget("detail_location", boundsMap)) {
                DateAndPlaceCard(session, onEditDate, onEditLocation)
            }
        }
        item {
            Box(Modifier.spotlightTarget("detail_health", boundsMap)) {
                if (session.totalCount > 0) {
                    HealthCard(session.healthScore, ZoneAggregator.computeFactors(session.debrisList))
                } else {
                    NoDetectionsCard()
                }
            }
        }
        item { AnalysisInfoRow(session.modelName, session.processingTimeMs) }
        item { SessionTiles(session) }
        repeated?.let { notice -> item { RepeatedObjectsNotice(notice) } }
        findingsSections(debris = session.debrisList, modelName = session.modelName)
    }
}

@Composable
private fun SessionTiles(session: DetectionSession) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(
            label = stringResource(R.string.session_detail_label_total_debris),
            value = session.totalCount.toString(),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(R.string.session_detail_label_quality),
            value = session.imageQuality.name,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DateAndPlaceCard(session: DetectionSession, onEditDate: () -> Unit, onEditLocation: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    SonarCard {
        EditableRow(
            icon = Icons.Filled.CalendarToday,
            editDescription = stringResource(R.string.session_detail_cd_edit_date),
            onEdit = onEditDate,
        ) {
            Text(
                text = dateFormat.format(session.timestamp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        LocationLine(session.location, onEditLocation)
    }
}

@Composable
private fun LocationLine(location: Location?, onEditLocation: () -> Unit) {
    if (location == null) {
        TextButton(onClick = onEditLocation) {
            Icon(Icons.Filled.LocationOn, null, Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.session_detail_location_add),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        return
    }
    val context = LocalContext.current
    val geocoder = remember(context) { (context.applicationContext as OceanGuardApp).geocoder }
    EditableRow(
        icon = Icons.Filled.LocationOn,
        editDescription = stringResource(R.string.session_detail_cd_edit_location_inline),
        onEdit = onEditLocation,
    ) {
        PlaceNameText(location = location, geocoder = geocoder, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EditableRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    editDescription: String,
    onEdit: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) { content() }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, editDescription, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
