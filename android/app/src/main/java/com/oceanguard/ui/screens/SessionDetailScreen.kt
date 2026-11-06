package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.ShimmerLoadingScreen
import com.oceanguard.ai.ui.components.sonar.SonarEmptyState
import com.oceanguard.ai.ui.components.spotlight.GuidedTourTransitionDialog
import com.oceanguard.ai.ui.components.spotlight.SpotlightController
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.screens.results.SessionDetailContent
import com.oceanguard.ai.ui.screens.results.sharePhoto
import kotlinx.coroutines.launch
import java.util.Date
import java.util.TimeZone

/**
 * A saved analysis: photo with its boxes, health, model and time, findings, and the actions to
 * edit its date and place, share the photo or delete it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    navController: NavController,
    viewModel: MainViewModel,
    sessionId: Long,
    onNavigateToLocationPicker: (Long) -> Unit = {},
) {
    var session by remember { mutableStateOf<DetectionSession?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showDelete by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val boundsMap = rememberSpotlightBounds()
    val tour = rememberSpotlightController(TourDefinitions.SESSION_DETAIL)
    val app = LocalContext.current.applicationContext as OceanGuardApp
    SessionTourStart(app, tour)

    LaunchedEffect(sessionId) {
        session = viewModel.getSession(sessionId)
        loading = false
    }
    session?.takeIf { showDate }?.let { current ->
        DateDialog(
            initialMillis = current.timestamp.time,
            onDismiss = { showDate = false },
            onPicked = { picked ->
                showDate = false
                val updated = current.copy(timestamp = picked)
                session = updated
                scope.launch { app.repository.updateSession(updated) }
            },
        )
    }
    session?.takeIf { showDelete }?.let { current ->
        DeleteDialog(
            onDismiss = { showDelete = false },
            onConfirm = {
                showDelete = false
                viewModel.deleteSession(current)
                navController.popBackStack()
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                SessionTopBar(
                    session = session,
                    onBack = { navController.popBackStack() },
                    onEditLocation = { onNavigateToLocationPicker(sessionId) },
                    onDelete = { showDelete = true },
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            val modifier = Modifier.padding(padding)
            val current = session
            when {
                loading -> ShimmerLoadingScreen(modifier = modifier.fillMaxSize())
                current == null -> SonarEmptyState(
                    icon = Icons.Filled.BrokenImage,
                    title = stringResource(R.string.session_detail_empty),
                    message = stringResource(R.string.results_session_missing),
                    modifier = modifier,
                )
                else -> SessionDetailContent(
                    session = current,
                    onEditLocation = { onNavigateToLocationPicker(sessionId) },
                    onEditDate = { showDate = true },
                    boundsMap = boundsMap,
                    modifier = modifier,
                    listState = listState,
                )
            }
        }
        SessionSpotlight(app, navController, tour, boundsMap, listState)
    }
}

@Composable
private fun SessionTourStart(app: OceanGuardApp, tour: SpotlightController) {
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.SESSION_DETAIL))
        .collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(tourComplete) { if (!tourComplete) tour.start() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionTopBar(
    session: DetectionSession?,
    onBack: () -> Unit,
    onEditLocation: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    TopAppBar(
        title = { Text(stringResource(R.string.session_detail_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.session_detail_cd_back))
            }
        },
        actions = {
            if (session == null) return@TopAppBar
            IconButton(onClick = onEditLocation) {
                Icon(Icons.Filled.Edit, stringResource(R.string.session_detail_cd_edit_location), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { scope.launch { sharePhoto(context, session.thumbnailUri ?: session.imageUri) } }) {
                Icon(Icons.Filled.Share, stringResource(R.string.results_share_photo), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, stringResource(R.string.session_detail_cd_delete_session), tint = MaterialTheme.colorScheme.error)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initialMillis: Long, onDismiss: () -> Unit, onPicked: (Date) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    onPicked(Date(millis + TimeZone.getDefault().getOffset(millis)))
                } ?: onDismiss()
            }) { Text(stringResource(R.string.location_picker_btn_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    ) { DatePicker(state = state) }
}

@Composable
private fun DeleteDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_detail_dialog_delete_title)) },
        text = { Text(stringResource(R.string.session_detail_dialog_delete_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.session_detail_btn_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun SessionSpotlight(
    app: OceanGuardApp,
    navController: NavController,
    tour: SpotlightController,
    boundsMap: MutableMap<String, Rect>,
    listState: LazyListState,
) {
    val scope = rememberCoroutineScope()
    val guided by app.settingsRepository.guidedTourActive.collectAsStateWithLifecycle(initialValue = false)
    var showTransition by remember { mutableStateOf(false) }

    SpotlightOverlay(
        controller = tour,
        targetBounds = boundsMap,
        onComplete = {
            scope.launch {
                app.settingsRepository.markTourComplete(TourDefinitions.getScreenId(TourDefinitions.SESSION_DETAIL))
                if (guided) showTransition = true else app.launchDemoCleanupIfComplete()
            }
        },
        onScrollToTarget = { targetId ->
            val index = when (targetId) {
                "detail_image" -> 0
                "detail_location" -> 1
                "detail_health" -> 2
                else -> null
            }
            if (index != null) listState.animateScrollToItem(index)
        },
        isGuidedTour = guided,
        onSkipTutorial = {
            scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
            app.launchDemoCleanup()
        },
    )
    if (showTransition) SessionTourTransition(app, navController) { showTransition = false }
}

/** Offers to continue the guided tour in Settings, or to leave it. */
@Composable
private fun SessionTourTransition(app: OceanGuardApp, navController: NavController, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    GuidedTourTransitionDialog(
        screenId = "session_detail",
        onContinue = {
            onDone()
            navController.navigate("settings") {
                popUpTo("home") { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
        onSkipTutorial = {
            onDone()
            scope.launch { app.settingsRepository.markAllGuidedToursComplete() }
            app.launchDemoCleanup()
        },
    )
}
