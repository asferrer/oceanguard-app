package com.oceanguard.ai.ui.components.sonar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SWIPE_ICON_PADDING = 24.dp

/**
 * Deletions that wait for the user: the row disappears at once, the data is deleted only when the
 * undo window closes. Leaving the screen closes the window: the deletion is kept, not lost.
 *
 * @param askUndo Suspends for the duration of the undo window; true when the user undid.
 * @param onCommit Performs the real deletion.
 */
@Stable
class UndoableDeletes<K>(
    private val scope: CoroutineScope,
    private val askUndo: suspend (K) -> Boolean,
    private val onCommit: suspend (K) -> Unit,
) {
    private val pending = mutableStateListOf<K>()

    fun isPending(key: K): Boolean = key in pending

    fun request(key: K) {
        if (key in pending) return
        pending.add(key)
        scope.launch {
            var undone = false
            try {
                undone = askUndo(key)
            } finally {
                withContext(NonCancellable) {
                    if (!undone) onCommit(key)
                    pending.remove(key)
                }
            }
        }
    }
}

/** [UndoableDeletes] with its undo window in [snackbar]. A new deletion closes the previous one. */
@Composable
fun <K> rememberUndoableDeletes(
    snackbar: SnackbarHostState,
    onCommit: suspend (K) -> Unit,
): UndoableDeletes<K> {
    val scope = rememberCoroutineScope()
    val message = stringResource(R.string.sonar_item_deleted)
    val undo = stringResource(R.string.sonar_undo)
    val commit by rememberUpdatedState(onCommit)
    return remember(snackbar) {
        UndoableDeletes(
            scope = scope,
            askUndo = {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(message, undo, duration = SnackbarDuration.Short) ==
                    SnackbarResult.ActionPerformed
            },
            onCommit = { commit(it) },
        )
    }
}

/**
 * Row that deletes by swiping left, with [UndoableDeletes] behind it. The gesture has a TalkBack
 * action as its alternative, and a static collapse under "Remove animations".
 */
@Composable
fun <K> SwipeUndoRow(
    key: K,
    deletes: UndoableDeletes<K>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val deleteLabel = stringResource(R.string.common_delete)
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = !deletes.isPending(key),
        modifier = modifier,
        exit = if (reduced) {
            fadeOut(OceanMotion.reducedFade())
        } else {
            shrinkVertically(OceanMotion.standard()) + fadeOut(OceanMotion.exit())
        },
    ) {
        val state = rememberSwipeToDismissBoxState()
        // Undone before the row finished collapsing: put it back in place.
        val pending = deletes.isPending(key)
        LaunchedEffect(pending) { if (!pending) state.reset() }
        SwipeToDismissBox(
            state = state,
            enableDismissFromStartToEnd = false,
            onDismiss = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) deletes.request(key)
            },
            // Only while swiping: a resting row must not show the colour around its rounded corners.
            backgroundContent = {
                if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) DeleteBackground()
            },
            modifier = Modifier.semantics {
                customActions = listOf(
                    CustomAccessibilityAction(deleteLabel) {
                        deletes.request(key)
                        true
                    },
                )
            },
        ) { content() }
    }
}

@Composable
private fun DeleteBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(end = SWIPE_ICON_PADDING),
        )
    }
}
