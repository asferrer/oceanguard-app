package com.oceanguard.ai.ui.screens.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.SessionDatesHelper
import com.oceanguard.ai.data.ZoneAggregator
import com.oceanguard.ai.data.ZoneCluster
import com.oceanguard.ai.utils.toZoneGeoJson
import java.time.LocalDate

/** What the map shows for the current sessions and filters. */
@Stable
class MapData(
    /** Sessions with a GPS position, before filtering. */
    val located: List<DetectionSession>,
    val zones: List<ZoneCluster>,
    val geoJson: String,
    val debrisTypes: List<DebrisType>,
    val datesWithData: Set<LocalDate>,
) {
    val avgHealth: Int
        get() = if (zones.isEmpty()) 0 else zones.map { it.aggregateHealthScore }.average().toInt()
}

/** Transient choices of the map screen: filters, view mode, selection and load status. */
@Stable
class MapScreenState {
    var filters by mutableStateOf(MapFilterState())
    var showHeatmap by mutableStateOf(false)
    var selectedZone by mutableStateOf<ZoneCluster?>(null)
    var selectedPinId by mutableStateOf<Long?>(null)
    /** Style picked by the user; null follows the app theme. */
    var styleChoice by mutableStateOf<MapStyle?>(null)
    var showDatePicker by mutableStateOf(false)
    var fitAllTrigger by mutableIntStateOf(0)
    var loadFailed by mutableStateOf(false)
    var retryKey by mutableIntStateOf(0)

    fun retryLoad() {
        loadFailed = false
        retryKey++
    }
}

@Composable
fun rememberMapScreenState(): MapScreenState = remember { MapScreenState() }

/** Applies [filters] to the located sessions and clusters the result into zones. */
@Composable
fun rememberMapData(sessions: List<DetectionSession>, filters: MapFilterState): MapData {
    val located = remember(sessions) { sessions.filter { it.location != null } }
    val types = remember(located) { located.flatMap { s -> s.debrisList.map { it.type } }.distinct().sorted() }
    val dates = remember(sessions) { SessionDatesHelper.datesWithData(sessions) }
    val zones = remember(located, filters) { ZoneAggregator.cluster(filters.applyTo(located)) }
    val geoJson = remember(zones) { zones.toZoneGeoJson() }
    return remember(located, zones, geoJson, types, dates) { MapData(located, zones, geoJson, types, dates) }
}
