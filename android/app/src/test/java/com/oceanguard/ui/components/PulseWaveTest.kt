package com.oceanguard.ai.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PulseWaveTest {

    @Test fun speciesPulseStartsAtItsMinimumPeaksInTheMiddleAndComesBack() {
        assertEquals(0.45f, speciesPulseAlphaAt(0f), 0.001f)
        assertEquals(1f, speciesPulseAlphaAt(0.5f), 0.001f)
        assertEquals(0.45f, speciesPulseAlphaAt(1f), 0.001f)
    }

    @Test fun shimmerPulseStaysBetweenItsBounds() {
        for (step in 0..20) {
            val alpha = shimmerPulseAlphaAt(step / 20f)
            assertEquals(true, alpha in 0.6f..1f)
        }
        assertEquals(1f, shimmerPulseAlphaAt(0.5f), 0.001f)
    }
}
