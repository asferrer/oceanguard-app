package com.oceanguard.ai.ui.screens.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Transient choices of the history screen: tab, search and chips, and multi-selection. */
@Stable
class HistoryScreenState {
    var showVideos by mutableStateOf(false)
    var filter by mutableStateOf(HistoryFilter())
    var showDatePicker by mutableStateOf(false)

    /** Selection mode, entered by the Select button or a long press and left explicitly. */
    var selecting by mutableStateOf(false)
        private set
    var selectedIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    var bulkDialog by mutableStateOf(BulkDialog.None)

    /** Bumped after a bulk edit so place names are looked up again for the moved analyses. */
    var resetKey by mutableIntStateOf(0)
        private set

    fun startSelecting(firstId: Long? = null) {
        selecting = true
        if (firstId != null) selectedIds = setOf(firstId)
    }

    fun toggle(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun selectAll(ids: Collection<Long>) {
        selectedIds = ids.toSet()
    }

    fun stopSelecting() {
        selecting = false
        selectedIds = emptySet()
    }

    /** A bulk edit is done: leave selection mode and drop filters the edit may have made stale. */
    fun finishBulkEdit() {
        stopSelecting()
        bulkDialog = BulkDialog.None
        filter = HistoryFilter()
        resetKey++
    }
}

@Composable
fun rememberHistoryScreenState(): HistoryScreenState = remember { HistoryScreenState() }
