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
import androidx.compose.runtime.SideEffect
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
import com.oceanguard.ai.ui.screens.map.MapDataModeSelector
import com.oceanguard.ai.ui.screens.map.MapSpeciesCard
import com.oceanguard.ai.ui.screens.map.MapSpeciesListCard
import com.oceanguard.ai.ui.screens.map.MapStage
import com.oceanguard.ai.ui.screens.map.SpeciesSummaryCard
import com.oceanguard.ai.ui.screens.map.species.MapDataMode
import com.oceanguard.ai.ui.screens.map.species.MapSummary
import com.oceanguard.ai.ui.screens.map.species.SpeciesMapData
import com.oceanguard.ai.ui.screens.map.species.SummaryKind
import com.oceanguard.ai.ui.screens.map.species.SummaryPart
import com.oceanguard.ai.ui.screens.map.species.rememberSpeciesMapData
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
    val species = rememberSpeciesMapData(state.filters)
    val savedMode by viewModel.settingsRepository.mapDataMode.collectAsStateWithLifecycle(initialValue = null)
    val mode = MapDataMode.fromKey(savedMode)
    SideEffect {
        state.dataMode = mode
        state.species = species
    }
    val scope = rememberCoroutineScope()
    val boundsMap = rememberSpotlightBounds()
    val tour = rememberSpotlightController(TourDefinitions.MAP)
    val focus = remember(focusLat, focusLon) {
        if (focusLat != null && focusLon != null) Position(longitude = focusLon, latitude = focusLat) else null
    }
    FocusZoneEffect(focus, data.zones) { state.selectedZone = it }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { MapTopBar(navController, data, mode, species) },
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
                onModeChange = { picked ->
                    state.selectedPinId = null
                    state.selectedSpecies = null
                    state.selectedSpeciesGroup = null
                    scope.launch { viewModel.settingsRepository.setMapDataMode(picked.key) }
                },
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
private fun MapTopBar(navController: NavController, data: MapData, mode: MapDataMode, species: SpeciesMapData) {
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.map_title), style = MaterialTheme.typography.titleLarge)
                MapSubtitle(MapSummary.parts(mode, data.located.size, data.zones.size, species.observationCount, species.speciesCount))
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

/** "13 analyses · 1 zone", or "6 observations · 3 species": whatever the chosen data counts. */
@Composable
private fun MapSubtitle(parts: List<SummaryPart>) {
    if (parts.isEmpty()) return
    val texts = mutableListOf<String>()
    for (part in parts) {
        texts += when (part.kind) {
            SummaryKind.SESSIONS -> pluralStringResource(R.plurals.map_subtitle_sessions, part.count, part.count)
            SummaryKind.ZONES -> pluralStringResource(R.plurals.map_subtitle_zones, part.count, part.count)
            SummaryKind.OBSERVATIONS -> pluralStringResource(R.plurals.map_subtitle_observations, part.count, part.count)
            SummaryKind.SPECIES -> pluralStringResource(R.plurals.map_subtitle_species, part.count, part.count)
        }
    }
    val text = texts.joinToString(" · ")
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
    onModeChange: (MapDataMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val camera = rememberMapCamera()
    val hasData = (state.dataMode.showsDebris && data.located.isNotEmpty()) ||
        (state.dataMode.showsSpecies && state.species.spots.isNotEmpty())
    Column(modifier.fillMaxSize()) {
        MapDataModeSelector(
            mode = state.dataMode,
            onSelect = onModeChange,
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp),
        )
        MapFilterBar(
            filterState = state.filters,
            onFilterChanged = { state.filters = it },
            availableDebrisTypes = data.debrisTypes,
            onDateRangeClick = { state.showDatePicker = true },
            showDebrisTypeChip = state.dataMode.offersDebrisControls,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .spotlightTarget("map_filters", boundsMap),
        )
        Box(Modifier.weight(1f)) {
            MapStage(state, data, style, focus, camera, boundsMap)
            if (!hasData) {
                EmptyMapCard(navController, hasAnySessions, state.dataMode == MapDataMode.SPECIES, Modifier.align(Alignment.Center))
            } else {
                MapHud(navController, state, data, style, tour, boundsMap)
            }
            MapConnectionStatus(state)
        }
    }
    MapDatePicker(state, data)
}

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
        val summaryModifier = Modifier.align(Alignment.TopStart).padding(16.dp).spotlightTarget("map_zones", boundsMap)
        if (!tour.isActive) {
            if (state.dataMode.showsDebris && data.zones.isNotEmpty()) {
                ZoneHealthSummary(clusterCount = data.zones.size, avgScore = data.avgHealth, modifier = summaryModifier)
            } else if (state.dataMode.showsSpecies && state.species.observationCount > 0) {
                SpeciesSummaryCard(state.species.observationCount, state.species.speciesCount, summaryModifier)
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                if (!tour.isActive && state.dataMode.showsDebris) {
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
                    showHeatmapButton = state.dataMode.offersDebrisControls,
                )
            }
            SelectedPinCard(navController, state, data)
        }
    }
}

@Composable
private fun SelectedPinCard(navController: NavController, state: MapScreenState, data: MapData) {
    state.selectedSpeciesGroup?.let { group ->
        MapSpeciesListCard(
            group = group,
            catalog = state.species.catalog,
            onSelect = { pin ->
                state.selectedSpeciesGroup = null
                state.selectedSpecies = pin
            },
            onClose = { state.selectedSpeciesGroup = null },
        )
        return
    }
    state.selectedSpecies?.let { pin ->
        MapSpeciesCard(
            pin = pin,
            catalog = state.species.catalog,
            onOpenSpecies = pin.speciesKey?.let { key -> { navController.navigate("biodex/$key") } },
            onOpenPhoto = { id -> navController.navigate("species_observation/$id") },
            onClose = { state.selectedSpecies = null },
        )
        return
    }
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
private fun EmptyMapCard(navController: NavController, hasAnySessions: Boolean, speciesOnly: Boolean, modifier: Modifier) {
    if (speciesOnly) {
        SpeciesEmptyCard(modifier)
        return
    }
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

/** Only species are shown and none has a position yet: the same reason the BioDex map gives. */
@Composable
private fun SpeciesEmptyCard(modifier: Modifier) {
    Surface(
        modifier = modifier.padding(24.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        SonarEmptyState(
            icon = Icons.Filled.Map,
            title = stringResource(R.string.biodex_detail_tab_map),
            message = stringResource(R.string.biodex_map_empty_no_location),
        )
    }
}
