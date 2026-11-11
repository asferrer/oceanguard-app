package com.oceanguard.ai.service

import com.oceanguard.ai.ui.screens.SpeciesBatchHost
import com.oceanguard.ai.ui.screens.SpeciesBatchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchProgressTest {

    @Test
    fun `photo being analysed is one past the finished ones and never past the total`() {
        assertEquals(1, BatchProgress.currentPhoto(0, 12))
        assertEquals(4, BatchProgress.currentPhoto(3, 12))
        assertEquals(12, BatchProgress.currentPhoto(12, 12))
        assertEquals(1, BatchProgress.currentPhoto(0, 0))
    }

    @Test
    fun `time left comes from the average per finished photo`() {
        assertNull(BatchProgress.remainingMs(0, 12, 5_000))
        assertNull(BatchProgress.remainingMs(12, 12, 120_000))
        assertEquals(90_000L, BatchProgress.remainingMs(3, 12, 30_000))
    }

    @Test
    fun `time left reads in seconds under a minute and in whole minutes after`() {
        assertEquals("45 s", BatchProgress.formatRemaining(44_100))
        assertEquals("1 min", BatchProgress.formatRemaining(60_000))
        assertEquals("2 min", BatchProgress.formatRemaining(61_000))
    }

    @Test
    fun `species batch host knows when a batch runs`() {
        val host = SpeciesBatchHost()
        assertFalse(host.isRunning)
        host.state.value = SpeciesBatchUiState.Running(emptyList(), 0, 0L)
        assertTrue(host.isRunning)
        host.state.value = SpeciesBatchUiState.Complete(emptyList(), 0L, 1L)
        assertFalse(host.isRunning)
    }
}
