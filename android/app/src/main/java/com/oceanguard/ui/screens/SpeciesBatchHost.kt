package com.oceanguard.ai.ui.screens

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Where a species batch lives. The app keeps one ([com.oceanguard.ai.OceanGuardApp.speciesBatchHost]) with an
 * application scope, so a batch goes on when the user leaves the app or the activity is recreated, and a foreground
 * service ([onStarted]) shows its progress in the notification shade. Tests use the default: state of its own and the
 * ViewModel's scope.
 *
 * @param scope     Scope the batch runs in; null = the ViewModel's own scope.
 * @param onStarted Called once a batch starts running (after its first state is published).
 */
class SpeciesBatchHost(
    val state: MutableStateFlow<SpeciesBatchUiState> = MutableStateFlow(SpeciesBatchUiState.Idle),
    val scope: CoroutineScope? = null,
    val onStarted: () -> Unit = {},
) {
    /** The running batch, to cancel it from the notification. */
    @Volatile
    var job: Job? = null

    val isRunning: Boolean get() = state.value is SpeciesBatchUiState.Running

    fun cancel() {
        job?.cancel()
    }
}
