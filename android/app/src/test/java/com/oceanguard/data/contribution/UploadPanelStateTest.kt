package com.oceanguard.ai.data.contribution

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadPanelStateTest {

    @Test
    fun `a running pass wins over the counts and shows the photo being sent`() {
        val s = UploadPanelState.of(pending = 4, done = 5, failed = 1, running = UploadProgress(1, 4), wifiOnly = true)
        assertEquals(UploadPanelState.Uploading(current = 2, total = 4), s)
    }

    @Test
    fun `a pass that has not reported progress yet is starting`() {
        val s = UploadPanelState.of(pending = 4, done = 0, failed = 0, running = UploadProgress(0, 0), wifiOnly = true)
        assertEquals(UploadPanelState.Uploading(current = 0, total = 0), s)
    }

    @Test
    fun `waiting photos say whether they wait for WiFi`() {
        assertEquals(UploadPanelState.Waiting(3, true), UploadPanelState.of(3, 5, 0, null, wifiOnly = true))
        assertEquals(UploadPanelState.Waiting(3, false), UploadPanelState.of(3, 5, 2, null, wifiOnly = false))
    }

    @Test
    fun `failed only when nothing waits, then all sent`() {
        assertEquals(UploadPanelState.Failed(2), UploadPanelState.of(0, 5, 2, null, wifiOnly = true))
        assertEquals(UploadPanelState.AllSent(10), UploadPanelState.of(0, 10, 0, null, wifiOnly = true))
        assertEquals(UploadPanelState.AllSent(0), UploadPanelState.of(0, 0, 0, null, wifiOnly = false))
    }

    @Test
    fun `current photo never goes past the total`() {
        val s = UploadPanelState.of(0, 0, 0, UploadProgress(4, 4), wifiOnly = true)
        assertEquals(UploadPanelState.Uploading(current = 4, total = 4), s)
    }
}
