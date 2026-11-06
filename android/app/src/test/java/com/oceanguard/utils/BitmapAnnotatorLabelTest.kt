package com.oceanguard.ai.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapAnnotatorLabelTest {

    @Test fun labelCarriesThePercentageOfARealConfidence() {
        assertEquals("bottle 87%", BitmapAnnotator.labelFor("bottle", 0.874f, showConfidence = true))
    }

    @Test fun labelHasNoPercentageWhenTheScoreIsNotAConfidence() {
        assertEquals("bottle", BitmapAnnotator.labelFor("bottle", 0.9f, showConfidence = false))
    }
}
