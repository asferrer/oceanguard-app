package com.oceanguard.ai.ui.screens.models

import com.oceanguard.ai.inference.DownloadFailureKind
import com.oceanguard.ai.inference.VlmDownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeciesPackLogicTest {

    private val downloading = VlmDownloadState.Downloading(0.4f, 246_000_000, 615_000_000)

    @Test fun `installed pack is ready whatever the download state says`() {
        assertEquals(SpeciesPackStatus.Ready, SpeciesPackLogic.status(true, false, VlmDownloadState.Idle))
        assertEquals(SpeciesPackStatus.Ready, SpeciesPackLogic.status(true, true, downloading))
    }

    @Test fun `missing pack with nothing downloading is offered`() {
        val status = SpeciesPackLogic.status(false, false, VlmDownloadState.Idle)
        assertEquals(SpeciesPackStatus.Missing, status)
        assertTrue(SpeciesPackLogic.offersDownload(status))
    }

    @Test fun `progress of another model is not shown as the pack's`() {
        assertEquals(SpeciesPackStatus.Missing, SpeciesPackLogic.status(false, false, downloading))
        val failure = VlmDownloadState.Error("x", DownloadFailureKind.NO_SPACE)
        assertEquals(SpeciesPackStatus.Missing, SpeciesPackLogic.status(false, false, failure))
    }

    @Test fun `pack download shows bytes while downloading and an unknown bar while preparing`() {
        assertEquals(
            SpeciesPackStatus.Downloading(0.4f, 246_000_000, 615_000_000),
            SpeciesPackLogic.status(false, true, downloading),
        )
        assertEquals(SpeciesPackStatus.Downloading(null), SpeciesPackLogic.status(false, true, VlmDownloadState.Preparing))
        assertEquals(SpeciesPackStatus.Downloading(null), SpeciesPackLogic.status(false, true, VlmDownloadState.Installing))
        assertFalse(SpeciesPackLogic.offersDownload(SpeciesPackLogic.status(false, true, downloading)))
    }

    @Test fun `failure keeps its kind and offers a retry`() {
        val status = SpeciesPackLogic.status(false, true, VlmDownloadState.Error("x", DownloadFailureKind.NO_CONNECTION))
        assertEquals(SpeciesPackStatus.Failed(DownloadFailureKind.NO_CONNECTION), status)
        assertTrue(SpeciesPackLogic.offersDownload(status))
    }

    @Test fun `finished or cancelled download without the files is missing again`() {
        assertEquals(SpeciesPackStatus.Missing, SpeciesPackLogic.status(false, true, VlmDownloadState.Complete))
        assertEquals(SpeciesPackStatus.Missing, SpeciesPackLogic.status(false, true, VlmDownloadState.Idle))
    }

    @Test fun `screens resume only on the step where the pack becomes installed`() {
        val ready = SpeciesPackStatus.Ready
        assertTrue(SpeciesPackLogic.justInstalled(SpeciesPackStatus.Downloading(1f), ready))
        assertTrue(SpeciesPackLogic.justInstalled(SpeciesPackStatus.Missing, ready))
        assertFalse(SpeciesPackLogic.justInstalled(null, ready)) // already installed when the screen opened
        assertFalse(SpeciesPackLogic.justInstalled(ready, ready))
        assertFalse(SpeciesPackLogic.justInstalled(SpeciesPackStatus.Missing, SpeciesPackStatus.Missing))
    }
}
