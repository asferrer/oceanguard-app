package com.oceanguard.ai.ui.screens.results

import com.oceanguard.ai.data.AnalysisModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfidenceDisplayTest {

    @Test
    fun detectorModelShowsPercentage() {
        assertEquals("87%", confidenceLabel(0.874f, AnalysisModels.RT_DETR_V2))
    }

    @Test
    fun gemmaNeverShowsPercentage() {
        assertNull(confidenceLabel(0.9f, AnalysisModels.GEMMA4_BASE))
        assertNull(confidenceLabel(0.9f, AnalysisModels.GEMMA4_OCEANGUARD))
    }

    @Test
    fun rowsWithoutRecordedModelKeepThePercentage() {
        assertEquals("50%", confidenceLabel(0.5f, null))
    }

    @Test
    fun valuesOutsideZeroToOneAreClamped() {
        assertEquals("100%", confidenceLabel(1.7f, null))
        assertEquals("0%", confidenceLabel(-0.2f, null))
    }
}
