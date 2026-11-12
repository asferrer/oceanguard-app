package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ZoneAggregator
import com.oceanguard.ai.data.ZoneCluster
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.DataDotDatePickerDialog
import com.oceanguard.ai.ui.components.sonar.SonarEmptyState
import com.oceanguard.ai.ui.components.spotlight.SpotlightController
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.navigation.OceanRoutes
import com.oceanguard.ai.ui.screens.map.MapData
import com.oceanguard.ai.ui.screens.map.MapFabControls
import com.oceanguard.ai.ui.screens.map.MapFilterBar
import com.oceanguard.ai.ui.screens.map.MapLegend
import com.oceanguard.ai.ui.screens.map.MapLoadFailed
import com.oceanguard.ai.ui.screens.map.MapMarkerPlanner
import com.oceanguard.ai.ui.screens.map.MapMarkersOverlay
import com.oceanguard.ai.ui.screens.map.MapOfflineBanner
import com.oceanguard.ai.ui.screens.map.MapPinCard
import com.oceanguard.ai.ui.screens.map.MapScreenState
import com.oceanguard.ai.ui.screens.map.MapStyle
import com.oceanguard.ai.ui.screens.map.MapTourOverlay
import com.oceanguard.ai.ui.screens.map.OceanGuardMap
import com.oceanguard.ai.ui.screens.map.ZoneHealthSummary
import com.oceanguard.ai.ui.screens.map.ZoneMapBottomSheet
import com.oceanguard.ai.ui.screens.map.flyTo
import com.oceanguard.ai.ui.screens.map.globe.GlobeCamera
import com.oceanguard.ai.ui.screens.map.globe.GlobeMapBridge
import com.oceanguard.ai.ui.screens.map.globe.GlobeMode
import com.oceanguard.ai.ui.screens.map.globe.GlobeView
import com.oceanguard.ai.ui.screens.map.globe.diveInto
import com.oceanguard.ai.ui.screens.map.globe.divingCamera
import com.oceanguard.ai.ui.screens.map.globe.rememberZoneDots
import com.oceanguard.ai.ui.screens.map.rememberIsOnline
import com.oceanguard.ai.ui.screens.map.rememberMapCamera
import com.oceanguard.ai.ui.screens.map.rememberMapData
import com.oceanguard.ai.ui.screens.map.rememberMapScreenState
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.Position
import java.net.URLEncoder

/** A zone counts as the deep-linked one when its centroid is within this many metres. */
private const val FOCUS_RADIUS_METERS = 1000.0

/**
 * Map of every analysis at its real coordinates. Zones start as numbered clusters; tapping one
 * flies the camera to it and opens it into one photo pin per analysis.
 */
@Composable
fun MapScreen(
    navController: NavController,
    viewModel: MainViewModel,
    focusLat: Double? = null,
    focusLon: Double? = null,
) {
    val sessions by viewModel.allSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val darkMode by viewModel.settingsRepository.darkMode.collectAsStateWithLifecycle(initialValue = true)
    val state = rememberMapScreenState()
    val data = rememberMapData(sessions, state.filters)
    val boundsMap = rememberSpotlightBounds()
    val tour = rememberSpotlightController(TourDefinitions.MAP)
    val focus = remember(focusLat, focusLon) {
        if (focusLat != null && focusLon != null) Position(longitude = focusLon, latitude = focusLat) else null
    }
    FocusZoneEffect(focus, data.zones) { state.selectedZone = it }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { MapTopBar(navController, data) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            MapBody(
                navController = navController,
                state = state,
                data = data,
                style = state.styleChoice ?: MapStyle.forDarkMode(darkMode),
                focus = focus,
                hasAnySessions = sessions.isNotEmpty(),
                tour = tour,
                boundsMap = boundsMap,
                modifier = Modifier.padding(padding),
            )
        }
        MapSheets(navController, state)
        MapTourOverlay(navController, tour, boundsMap)
    }
}

/** Deep link: opens the zone nearest to [focus] when it is close enough to be the same place. */
@Composable
private fun FocusZoneEffect(focus: Position?, zones: List<ZoneCluster>, onZone: (ZoneCluster) -> Unit) {
    LaunchedEffect(focus, zones) {
        val target = focus ?: return@LaunchedEffect
        val closest = zones.minByOrNull { distanceTo(target, it) } ?: return@LaunchedEffect
        if (distanceTo(target, closest) < FOCUS_RADIUS_METERS) onZone(closest)
    }
}

private fun distanceTo(p: Position, zone: ZoneCluster): Double =
    ZoneAggregator.haversineDistance(p.latitude, p.longitude, zone.centroidLat, zone.centroidLon)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapTopBar(navController: NavController, data: MapData) {
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.map_title), style = MaterialTheme.typography.titleLarge)
                if (data.located.isNotEmpty()) MapSubtitle(data.located.size, data.zones.size)
            }
        },
        navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.map_cd_go_back))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun MapSubtitle(sessionCount: Int, zoneCount: Int) {
    val sessions = pluralStringResource(R.plurals.map_subtitle_sessions, sessionCount, sessionCount)
    val text = if (zoneCount > 0) {
        sessions + " · " + pluralStringResource(R.plurals.map_subtitle_zones, zoneCount, zoneCount)
    } else {
        sessions
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MapBody(
    navController: NavController,
    state: MapScreenState,
    data: MapData,
    style: MapStyle,
    focus: Position?,
    hasAnySessions: Boolean,
    tour: SpotlightController,
    boundsMap: MutableMap<String, Rect>,
    modifier: Modifier = Modifier,
) {
    val camera = rememberMapCamera()
    Column(modifier.fillMaxSize()) {
        MapFilterBar(
            filterState = state.filters,
            onFilterChanged = { state.filters = it },
            availableDebrisTypes = data.debrisTypes,
            onDateRangeClick = { state.showDatePicker = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .spotlightTarget("map_filters", boundsMap),
        )
        Box(Modifier.weight(1f)) {
            MapStage(state, data, style, focus, camera, boundsMap)
            if (data.located.isEmpty()) {
                EmptyMapCard(navController, hasAnySessions, Modifier.align(Alignment.Center))
            } else {
                MapHud(navController, state, data, style, tour, boundsMap)
            }
            MapConnectionStatus(state)
        }
    }
    MapDatePicker(state, data)
}

/** Base map, the zones and photos drawn on it, and the camera that moves them. */
@Composable
private fun MapStage(
    state: MapScreenState,
    data: MapData,
    style: MapStyle,
    focus: Position?,
    camera: CameraState,
    boundsMap: MutableMap<String, Rect>,
) {
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val focusTrigger = if (focus != null) 1 else 0
    val globe = state.globe
    key(state.retryKey) {
        OceanGuardMap(
            cameraState = camera,
            geoJson = data.geoJson,
            styleUrl = style.url,
            showHeatmap = state.showHeatmap,
            zoneClusters = data.zones,
            fitAllTrigger = state.fitAllTrigger,
            focusPosition = focus,
            focusTrigger = focusTrigger,
            flightsEnabled = globe.mode == GlobeMode.MAP,
            flightsEpoch = globe.introEpoch,
            onLoadFailed = { state.loadFailed = true },
            onLoadFinished = { state.loadFailed = false },
            modifier = Modifier.fillMaxSize().spotlightTarget("map_view", boundsMap),
        )
    }
    val introTarget = remember(focus, data.zones) { introCamera(focus, data.zones) }
    GlobeMapBridge(globe, camera, introTarget, state.fitAllTrigger)
    val mapMarkersShown by remember(globe) {
        derivedStateOf { globe.mode == GlobeMode.MAP || globe.opacity < MARKERS_HANDOVER_OPACITY }
    }
    if (!state.showHeatmap && !state.loadFailed && mapMarkersShown) {
        MapMarkersOverlay(
            cameraState = camera,
            zones = data.zones,
            selectedSessionId = state.selectedPinId,
            onClusterClick = { index ->
                val zone = data.zones.getOrNull(index) ?: return@MapMarkersOverlay
                scope.launch {
                    camera.flyTo(Position(zone.centroidLon, zone.centroidLat), MapMarkerPlanner.FLY_ZOOM, reduced)
                    state.selectedZone = zone
                }
            },
            onPinClick = { state.selectedPinId = it },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = if (globe.mode == GlobeMode.MAP) 1f else 1f - globe.opacity
            },
        )
    }
    GlobeView(
        controller = globe,
        dots = rememberZoneDots(data.zones),
        onDotClick = { index ->
            val zone = data.zones.getOrNull(index) ?: return@GlobeView
            scope.launch {
                val at = Position(zone.centroidLon, zone.centroidLat)
                globe.diveInto(at, camera, reduced)
                camera.flyTo(at, MapMarkerPlanner.FLY_ZOOM, reduced)
                state.selectedZone = zone
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Where the globe opens the screen: the deep-linked place, else the middle of the zones, else nowhere. */
private fun introCamera(focus: Position?, zones: List<ZoneCluster>): GlobeCamera? {
    if (focus != null) return divingCamera(focus.latitude, focus.longitude)
    val fit = MapMarkerPlanner.fitTarget(zones) ?: return null
    return divingCamera(fit.latitude, fit.longitude)
}

/** The map's markers appear as the globe thins out, not before, so the two never double up. */
private const val MARKERS_HANDOVER_OPACITY = 0.99f

@Composable
private fun MapHud(
    navController: NavController,
    state: MapScreenState,
    data: MapData,
    style: MapStyle,
    tour: SpotlightController,
    boundsMap: MutableMap<String, Rect>,
) {
    Box(Modifier.fillMaxSize()) {
        if (data.zones.isNotEmpty() && !tour.isActive) {
            ZoneHealthSummary(
                clusterCount = data.zones.size,
                avgScore = data.avgHealth,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp).spotlightTarget("map_zones", boundsMap),
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                if (!tour.isActive) {
                    MapLegend(Modifier.spotlightTarget("map_legend", boundsMap))
                }
                Spacer(Modifier.weight(1f))
                MapFabControls(
                    showHeatmap = state.showHeatmap,
                    onToggleHeatmap = { state.showHeatmap = !state.showHeatmap },
                    onFitAll = { state.fitAllTrigger++ },
                    style = style,
                    onStyleSelected = { state.styleChoice = it },
                    boundsMap = boundsMap,
                )
            }
            SelectedPinCard(navController, state, data)
        }
    }
}

@Composable
private fun SelectedPinCard(navController: NavController, state: MapScreenState, data: MapData) {
    val session = data.located.firstOrNull { it.id == state.selectedPinId } ?: return
    MapPinCard(
        session = session,
        onOpenDetail = { navController.navigate("session/${session.id}") },
        onFixLocation = { navController.navigate("location_picker/${session.id}") },
        onClose = { state.selectedPinId = null },
    )
}

/** Over the blank flat map: the style failed to load, or there is no connection to download it. */
@Composable
private fun MapConnectionStatus(state: MapScreenState) {
    val online by rememberIsOnline()
    Box(Modifier.fillMaxSize()) {
        when {
            // The globe is bundled with the app: it needs no connection, so it shows no warning.
            state.globe.mode == GlobeMode.GLOBE -> Unit
            state.loadFailed -> MapLoadFailed(state::retryLoad, Modifier.align(Alignment.Center))
            !online -> MapOfflineBanner(Modifier.align(Alignment.TopCenter).padding(16.dp))
        }
    }
}

/** No analysis has a position yet: the map stays visible, with the reason and the next step. */
@Composable
private fun EmptyMapCard(navController: NavController, hasAnySessions: Boolean, modifier: Modifier) {
    Surface(
        modifier = modifier.padding(24.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        SonarEmptyState(
            icon = Icons.Filled.Map,
            title = stringResource(
                if (hasAnySessions) R.string.map_empty_no_location_title else R.string.map_empty_no_sessions_title,
            ),
            message = stringResource(
                if (hasAnySessions) R.string.map_empty_no_location_message else R.string.map_empty_no_sessions_message,
            ),
            actionLabel = if (hasAnySessions) null else stringResource(R.string.map_empty_action_scan),
            onAction = { navController.navigate("${OceanRoutes.CAMERA}?mode=debris") },
        )
    }
}

@Composable
private fun MapDatePicker(state: MapScreenState, data: MapData) {
    if (!state.showDatePicker) return
    DataDotDatePickerDialog(
        onDismissRequest = { state.showDatePicker = false },
        initialStartDateMillis = state.filters.dateRangeStartMs,
        initialEndDateMillis = state.filters.dateRangeEndMs,
        datesWithData = data.datesWithData,
        onConfirm = { start, end ->
            state.filters = state.filters.copy(dateRangeStartMs = start, dateRangeEndMs = end)
            state.showDatePicker = false
        },
    )
}

/** Sheet of the opened zone: activity by day, with the way to its analyses and to a report. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapSheets(navController: NavController, state: MapScreenState) {
    val zone = state.selectedZone ?: return
    ZoneMapBottomSheet(
        zone = zone,
        onDismiss = { state.selectedZone = null },
        onSessionClick = { sessionId ->
            state.selectedZone = null
            navController.navigate("session/$sessionId")
        },
        onGenerateReport = { lat, lon, name ->
            state.selectedZone = null
            navController.navigate("reports?lat=$lat&lon=$lon&name=${URLEncoder.encode(name, "UTF-8")}")
        },
    )
}
