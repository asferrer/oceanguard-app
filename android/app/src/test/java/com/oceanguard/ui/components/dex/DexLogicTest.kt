package com.oceanguard.ai.ui.components.dex

import com.oceanguard.ai.data.DebrisType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DexLogicTest {

    @Test fun tabDirectionFollowsTheOrderOfTheTabs() {
        assertEquals(1, tabDirection(0, 2))
        assertEquals(-1, tabDirection(2, 1))
        assertEquals(0, tabDirection(1, 1))
    }

    @Test fun heroFloatStaysWithinItsAmplitudeAndStartsStill() {
        assertEquals(0f, heroFloatOffset(0f), 0.0001f)
        (0..100).forEach { assertTrue(kotlin.math.abs(heroFloatOffset(it / 100f)) <= 6f + 0.0001f) }
    }

    @Test fun everyCanonicalTypeHasATranslatedName() {
        DebrisType.CANONICAL.forEach { assertNotNull(it.name, debrisNameRes(it)) }
    }

    @Test fun extendedTypesFallBackToAReadableName() {
        assertNull(debrisNameRes(DebrisType.PLASTIC_BAG))
        assertEquals("Plastic bag", humanizeEnumName("PLASTIC_BAG"))
    }
}
