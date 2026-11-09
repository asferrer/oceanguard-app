package com.oceanguard.ai.ui.screens.report

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ZoneAggregator
import com.oceanguard.ai.data.ZoneCluster
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.inference.ReportType
import com.oceanguard.ai.utils.toPlaceLabel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/** A zone is preselected from the map only when its centre is this close, in metres. */
private const val PRESELECT_MAX_METERS = 1_500.0

/** Zones of the located sessions, their place names, and which one is selected. */
@Stable
internal class ReportZones(
    val areas: List<ReportArea>,
    names: List<String>,
    val namesLoading: Boolean,
    private val selected: MutableState<Int?>,
) {
    private val resolvedNames = names

    /** The zone of each area, as the selector and the preview card read them. */
    val clusters: List<ZoneCluster> = areas.map { it.zone }

    /** Names shown to the person; empty until every zone has one. */
    val names: List<String> get() = if (resolvedNames.size == clusters.size) resolvedNames else emptyList()

    var selectedIndex: Int?
        get() = selected.value
        set(value) { selected.value = value }

    val selectedZone: ZoneCluster? get() = selectedArea?.zone
    val selectedArea: ReportArea? get() = selectedIndex?.let { areas.getOrNull(it) }
    val selectedName: String get() = selectedIndex?.let { resolvedNames.getOrNull(it) }.orEmpty()
}

/**
 * Clusters the located sessions, names each zone through the geocoder in parallel, and applies the
 * zone the map handed over, if any. A coordinate-only name is replaced by the map's own name.
 */
@Composable
internal fun rememberReportZones(
    sessions: List<DetectionSession>,
    observations: List<SpeciesObservation>,
    type: ReportType,
    preselectedLat: Double?,
    preselectedLon: Double?,
    preselectedName: String?,
): ReportZones {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = remember(context) { context.applicationContext as OceanGuardApp }
    val areas = remember(sessions, observations, type) { ReportAreas.build(type, sessions, observations) }
    val clusters = remember(areas) { areas.map { it.zone } }
    // The zones differ per kind of report, so an index chosen for one kind means nothing for another.
    val selected = rememberSaveable(type) { mutableStateOf<Int?>(null) }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(clusters) {
        if (clusters.isEmpty()) {
            names = emptyList()
            return@LaunchedEffect
        }
        loading = true
        names = clusters.mapIndexed { index, zone ->
            val zoneNumber: Int = index + 1
            async {
                app.geocoder.reverse(zone.centroidLat, zone.centroidLon)?.toPlaceLabel()
                    ?: resources.getString(R.string.report_zone_fallback, zoneNumber)
            }
        }.awaitAll()
        loading = false
    }
    LaunchedEffect(preselectedLat, preselectedLon, clusters) {
        if (selected.value == null) selected.value = closestZone(clusters, preselectedLat, preselectedLon)
    }
    LaunchedEffect(preselectedName, selected.value, names) {
        val index = selected.value
        if (preselectedName != null && index != null && names.getOrNull(index)?.isCoordinateOnly() == true) {
            names = names.toMutableList().also { it[index] = preselectedName }
        }
    }
    return ReportZones(areas, names, loading, selected)
}

/** Index of the zone nearest to the point, or null when there is none close enough. */
internal fun closestZone(clusters: List<ZoneCluster>, lat: Double?, lon: Double?): Int? {
    if (lat == null || lon == null || clusters.isEmpty()) return null
    val nearest = clusters.withIndex().minByOrNull { (_, zone) ->
        ZoneAggregator.haversineDistance(lat, lon, zone.centroidLat, zone.centroidLon)
    } ?: return null
    val meters = ZoneAggregator.haversineDistance(lat, lon, nearest.value.centroidLat, nearest.value.centroidLon)
    return nearest.index.takeIf { meters < PRESELECT_MAX_METERS }
}

private val COORDINATES = Regex("-?\\d+\\.\\d+, -?\\d+\\.\\d+")

private fun String.isCoordinateOnly(): Boolean = startsWith("Zone ") || matches(COORDINATES)
