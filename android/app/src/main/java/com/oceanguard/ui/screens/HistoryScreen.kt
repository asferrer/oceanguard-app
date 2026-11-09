package com.oceanguard.ai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.SessionDatesHelper
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.data.species.SpeciesNames
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.DataDotDatePickerDialog
import com.oceanguard.ai.ui.components.SelectionTopBar
import com.oceanguard.ai.ui.components.ShimmerLoadingScreen
import com.oceanguard.ai.ui.components.sonar.SonarEmptyState
import com.oceanguard.ai.ui.components.sonar.rememberUndoableDeletes
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.screens.history.BulkDialog
import com.oceanguard.ai.ui.screens.history.HistoryBulkDialogs
import com.oceanguard.ai.ui.screens.history.HistoryFilter
import com.oceanguard.ai.ui.screens.history.HistoryFilterChips
import com.oceanguard.ai.ui.screens.history.HistoryGrouping
import com.oceanguard.ai.ui.screens.history.HistoryListActions
import com.oceanguard.ai.ui.screens.history.HistoryKind
import com.oceanguard.ai.ui.screens.history.HistoryScreenState
import com.oceanguard.ai.ui.screens.history.HistorySpeciesContext
import com.oceanguard.ai.ui.screens.history.SpeciesHistoryGrouping
import com.oceanguard.ai.ui.screens.history.matchesSpecies
import com.oceanguard.ai.ui.screens.history.HistorySearchField
import com.oceanguard.ai.ui.screens.history.HistorySessionList
import com.oceanguard.ai.ui.screens.history.HistoryTourOverlay
import com.oceanguard.ai.ui.screens.history.HistoryVideoList
import com.oceanguard.ai.ui.screens.history.rememberHistoryScreenState
import com.oceanguard.ai.ui.screens.history.rememberPlaceNames
import java.util.Date

/**
 * Chronological history of every analysis, grouped by day.
 *
 * The search box and the chips filter as the user types or taps. The Select button, or a long
 * press on a card, enters selection mode for the bulk actions: edit date, edit location and
 * delete. Swiping a card left deletes it and offers Undo. The Photos and Videos tabs switch
 * between the two kinds of analysis.
 */
@Composable
fun HistoryScreen(
    navController: NavController,
    viewModel: MainViewModel,
    onNavigateToLocationPicker: (Long) -> Unit = { id -> navController.navigate("location_picker/$id") },
) {
    // Null until the database answers, so the first frame shows a skeleton and not "no analyses".
    val loaded by viewModel.allSessions.collectAsStateWithLifecycle(initialValue = null as List<DetectionSession>?)
    val sessions = loaded ?: emptyList()
    val videos by viewModel.allVideoAnalyses.collectAsStateWithLifecycle(initialValue = emptyList())
    val state = rememberHistoryScreenState()
    val boundsMap = rememberSpotlightBounds()
    val tour = rememberSpotlightController(TourDefinitions.HISTORY)
    val app = (LocalContext.current.applicationContext as OceanGuardApp)
    val snackbar = remember { SnackbarHostState() }
    val placeNames = rememberPlaceNames(sessions, app.geocoder, state.resetKey)
    val visible = remember(sessions, state.filter, placeNames.toMap()) {
        state.filter.apply(sessions, placeNames)
    }
    val observations by app.database.speciesObservationDao().getAll()
        .collectAsStateWithLifecycle(initialValue = emptyList<SpeciesObservation>())
    val language = remember { SpeciesNames.currentLanguage() }
    val speciesGroups = remember(observations) { SpeciesHistoryGrouping.group(observations) }
    val visibleSpecies = remember(speciesGroups, state.filter) {
        speciesGroups.filter { state.filter.matchesSpecies(it, app.speciesCatalog, language) }
    }

    BackHandler(enabled = state.selecting) { state.stopSelecting() }
    HistoryBulkDialogs(
        dialog = state.bulkDialog,
        count = state.selectedIds.size,
        app = app,
        onDismiss = { state.bulkDialog = BulkDialog.None },
        onDate = { millis ->
            viewModel.updateSessionsTimestamp(state.selectedIds.toList(), Date(millis))
            state.finishBulkEdit()
        },
        onLocation = { location ->
            viewModel.updateSessionsLocation(state.selectedIds.toList(), location)
            state.finishBulkEdit()
        },
        onDelete = {
            viewModel.deleteSessions(state.selectedIds.toList())
            state.stopSelecting()
            state.bulkDialog = BulkDialog.None
        },
    )

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { HistoryTopBar(navController, state, sessions.isNotEmpty(), visible) },
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            HistoryBody(
                sessions = sessions,
                loading = loaded == null,
                visible = visible,
                speciesGroups = speciesGroups,
                visibleSpecies = visibleSpecies,
                speciesContext = HistorySpeciesContext(app.speciesCatalog, language),
                videoCount = videos.size,
                videos = videos,
                placeNames = placeNames.values.distinct().sorted(),
                state = state,
                boundsMap = boundsMap,
                navController = navController,
                viewModel = viewModel,
                snackbar = snackbar,
                onEditLocation = onNavigateToLocationPicker,
                modifier = Modifier.padding(padding),
            )
        }
        HistoryTourOverlay(navController, tour, boundsMap, sessions.firstOrNull()?.id)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryTopBar(
    navController: NavController,
    state: HistoryScreenState,
    hasSessions: Boolean,
    visible: List<DetectionSession>,
) {
    if (state.selecting) {
        SelectionTopBar(
            selectedCount = state.selectedIds.size,
            onClose = state::stopSelecting,
            onEditDate = { state.bulkDialog = BulkDialog.EditDate },
            onEditLocation = { state.bulkDialog = BulkDialog.EditLocation },
            onDelete = { state.bulkDialog = BulkDialog.Delete },
            onSelectAll = { state.selectAll(visible.map { it.id }) },
            actionsEnabled = state.selectedIds.isNotEmpty(),
        )
        return
    }
    TopAppBar(
        title = { Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_go_back))
            }
        },
        actions = {
            if (hasSessions && !state.showVideos) {
                TextButton(onClick = { state.startSelecting() }) { Text(stringResource(R.string.history_select)) }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun HistoryBody(
    sessions: List<DetectionSession>,
    loading: Boolean,
    visible: List<DetectionSession>,
    speciesGroups: List<com.oceanguard.ai.ui.screens.history.SpeciesHistoryGroup>,
    visibleSpecies: List<com.oceanguard.ai.ui.screens.history.SpeciesHistoryGroup>,
    speciesContext: HistorySpeciesContext,
    videoCount: Int,
    videos: List<com.oceanguard.ai.data.VideoAnalysis>,
    placeNames: List<String>,
    state: HistoryScreenState,
    boundsMap: MutableMap<String, Rect>,
    navController: NavController,
    viewModel: MainViewModel,
    snackbar: SnackbarHostState,
    onEditLocation: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        MediaTabs(sessions.size, videoCount, state, Modifier.spotlightTarget("history_media_filter", boundsMap))
        if (state.showVideos) {
            HistoryVideoList(videos, onOpen = { navController.navigate("video_detail/$it") })
            return@Column
        }
        if (speciesGroups.isNotEmpty()) KindChips(state)
        PhotoFilters(sessions, placeNames, state, boundsMap, speciesGroups.isNotEmpty())
        PhotoList(
            sessions, loading, visible, SpeciesPart(speciesGroups, visibleSpecies, speciesContext),
            state, boundsMap, navController, viewModel, snackbar, onEditLocation,
        )
    }
    HistoryDatePicker(sessions, state)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindChips(state: HistoryScreenState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val kinds = listOf(
            HistoryKind.ALL to R.string.history_kind_all,
            HistoryKind.DEBRIS to R.string.history_kind_debris,
            HistoryKind.SPECIES to R.string.history_kind_species,
        )
        for ((kind, label) in kinds) {
            FilterChip(
                selected = state.filter.kind == kind,
                onClick = { state.filter = state.filter.copy(kind = kind) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

/** Species identifications of the history: all of them, the ones the filters keep, and how to draw them. */
private class SpeciesPart(
    val all: List<com.oceanguard.ai.ui.screens.history.SpeciesHistoryGroup>,
    val visible: List<com.oceanguard.ai.ui.screens.history.SpeciesHistoryGroup>,
    val context: HistorySpeciesContext,
)

@Composable
private fun MediaTabs(photos: Int, videos: Int, state: HistoryScreenState, modifier: Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MediaTab(stringResource(R.string.history_filter_images, photos), !state.showVideos) { state.showVideos = false }
        MediaTab(stringResource(R.string.history_filter_videos, videos), state.showVideos) { state.showVideos = true }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaTab(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
    )
}

@Composable
private fun PhotoFilters(
    sessions: List<DetectionSession>,
    places: List<String>,
    state: HistoryScreenState,
    boundsMap: MutableMap<String, Rect>,
    hasSpecies: Boolean = false,
) {
    HistorySearchField(
        query = state.filter.query,
        onQueryChange = { state.filter = state.filter.copy(query = it) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .spotlightTarget("history_search", boundsMap),
    )
    if (sessions.isEmpty() && !hasSpecies) return
    val types = remember(sessions) { sessions.flatMap { s -> s.debrisList.map { it.type } }.distinct().sorted() }
    HistoryFilterChips(
        filter = state.filter,
        onFilterChange = { state.filter = it },
        availableTypes = types,
        availablePlaces = places,
        onDateRangeClick = { state.showDatePicker = true },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).spotlightTarget("history_filters", boundsMap),
    )
}

@Composable
private fun PhotoList(
    sessions: List<DetectionSession>,
    loading: Boolean,
    visible: List<DetectionSession>,
    species: SpeciesPart,
    state: HistoryScreenState,
    boundsMap: MutableMap<String, Rect>,
    navController: NavController,
    viewModel: MainViewModel,
    snackbar: SnackbarHostState,
    onEditLocation: (Long) -> Unit,
) {
    val app = LocalContext.current.applicationContext as OceanGuardApp
    // The undo window ends in the real deletion, which also recomputes the MarineDex.
    val deletes = rememberUndoableDeletes<Long>(snackbar) { id ->
        sessions.firstOrNull { it.id == id }?.let(viewModel::deleteSession)
    }
    val groups = remember(visible, species.visible) {
        HistoryGrouping.groupByDay(visible, System.currentTimeMillis(), species = species.visible)
    }
    val kind = state.filter.kind
    when {
        loading -> ShimmerLoadingScreen(modifier = Modifier, itemCount = 5)
        sessions.isEmpty() && species.all.isEmpty() -> EmptyHistory()
        kind == HistoryKind.SPECIES && species.all.isEmpty() -> EmptySpeciesHistory()
        kind == HistoryKind.DEBRIS && sessions.isEmpty() -> EmptyHistory()
        visible.isEmpty() && species.visible.isEmpty() -> NoResults { state.filter = HistoryFilter() }
        else -> HistorySessionList(
            groups = groups,
            geocoder = app.geocoder,
            selecting = state.selecting,
            selectedIds = state.selectedIds,
            deletes = deletes,
            actions = HistoryListActions(
                onOpen = { navController.navigate("session/$it") },
                onToggle = state::toggle,
                onLongPress = { if (state.selecting) state.toggle(it) else state.startSelecting(it) },
                onEditLocation = onEditLocation,
                onOpenSpecies = { navController.navigate("species_analysis/$it") },
            ),
            boundsMap = boundsMap,
            modifier = Modifier.fillMaxSize(),
            species = species.context,
        )
    }
}

@Composable
private fun EmptyHistory() {
    SonarEmptyState(
        icon = Icons.Filled.History,
        title = stringResource(R.string.history_empty_title),
        message = stringResource(R.string.history_empty_message),
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun EmptySpeciesHistory() {
    SonarEmptyState(
        icon = Icons.Filled.History,
        title = stringResource(R.string.history_species_empty_title),
        message = stringResource(R.string.history_species_empty_message),
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun NoResults(onClear: () -> Unit) {
    SonarEmptyState(
        icon = Icons.Filled.SearchOff,
        title = stringResource(R.string.history_no_results_title),
        message = stringResource(R.string.history_no_results_message),
        actionLabel = stringResource(R.string.history_clear_filters),
        onAction = onClear,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun HistoryDatePicker(sessions: List<DetectionSession>, state: HistoryScreenState) {
    if (!state.showDatePicker) return
    val dates = remember(sessions) { SessionDatesHelper.datesWithData(sessions) }
    DataDotDatePickerDialog(
        onDismissRequest = { state.showDatePicker = false },
        initialStartDateMillis = state.filter.dateRangeStartMs,
        initialEndDateMillis = state.filter.dateRangeEndMs,
        datesWithData = dates,
        onConfirm = { start, end ->
            state.filter = state.filter.copy(dateRangeStartMs = start, dateRangeEndMs = end)
            state.showDatePicker = false
        },
    )
}
