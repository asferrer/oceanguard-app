package com.oceanguard.ai.ui.screens.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.platform.LocalResources
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.utils.PhotonGeocoderClient
import com.oceanguard.ai.utils.toPlaceLabel

/**
 * Place name of each located analysis, resolved by reverse geocoding as the list loads. It feeds
 * the location chip and lets the search find an analysis by where it was taken.
 *
 * A name is looked up again when its analysis changes position (a bulk edit), and dropped when
 * the analysis is deleted or loses its position. Everything restarts when [resetKey] changes.
 */
@Composable
fun rememberPlaceNames(
    sessions: List<DetectionSession>,
    geocoder: PhotonGeocoderClient,
    resetKey: Int,
): SnapshotStateMap<Long, String> {
    val resources = LocalResources.current
    val names = remember(resetKey) { mutableStateMapOf<Long, String>() }
    val positions = remember(resetKey) { mutableStateMapOf<Long, String>() }
    LaunchedEffect(sessions, resetKey) {
        val located = sessions.filter { it.location != null }
        located.forEach { session ->
            val location = session.location ?: return@forEach
            val position = "${location.latitude},${location.longitude}"
            if (positions[session.id] == position) return@forEach
            positions[session.id] = position
            names[session.id] = geocoder.reverse(location.latitude, location.longitude)?.toPlaceLabel()
                ?: resources.getString(R.string.zone_name_unknown)
        }
        val ids = located.map { it.id }.toSet()
        names.keys.removeAll { it !in ids }
        positions.keys.removeAll { it !in ids }
    }
    return names
}
