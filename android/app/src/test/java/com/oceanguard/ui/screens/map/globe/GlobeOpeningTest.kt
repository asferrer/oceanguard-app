package com.oceanguard.ai.ui.screens.map.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeOpeningTest {

    private fun plan(
        focus: Boolean = false,
        data: Boolean = true,
        took: Boolean = false,
        asked: Boolean = false,
        reduced: Boolean = false,
    ) = GlobeOpening.plan(focus, data, took, asked, reduced)

    @Test
    fun openingWithZonesTurnsToFaceThemAndNeverFliesIn() {
        assertEquals(OpeningAction.FACE, plan())
        assertEquals(OpeningAction.FACE, plan(reduced = true))
    }

    @Test
    fun openingWithoutDataSpinsUnlessMotionIsReduced() {
        assertEquals(OpeningAction.SPIN, plan(data = false))
        assertEquals(OpeningAction.NONE, plan(data = false, reduced = true))
    }

    @Test
    fun aDeepLinkFliesIn() {
        assertEquals(OpeningAction.DIVE, plan(focus = true))
    }

    @Test
    fun onlyAnExplicitRequestOrADeepLinkEverDives() {
        val others = listOf(
            plan(), plan(data = false), plan(took = true), plan(reduced = true), plan(data = false, reduced = true),
        )
        assertTrue(others.none { it == OpeningAction.DIVE })
        assertEquals(OpeningAction.DIVE, plan(asked = true))
        assertEquals(OpeningAction.DIVE, plan(asked = true, took = true))
    }

    @Test
    fun frameAllWithNothingToFrameDoesNotDive() {
        assertEquals(OpeningAction.SPIN, plan(data = false, asked = true))
    }

    @Test
    fun onceTheUserTouchesTheGlobeItStopsMovingOnItsOwn() {
        assertEquals(OpeningAction.NONE, plan(took = true))
        assertEquals(OpeningAction.NONE, plan(took = true, focus = true))
    }

    @Test
    fun facingKeepsTheZoomSoTheMapNeverTakesOver() {
        val view = GlobeCamera(10.0, 20.0, 1.4)

        val faced = GlobeOpening.facing(view, 38.0, -0.5)

        assertEquals(1.4, faced.zoom, 0.0)
        assertEquals(38.0, faced.latitude, 0.0)
        assertEquals(-0.5, faced.longitude, 0.0)
        assertFalse(GlobeTransition.shouldEnterMap(GlobeMath.mapZoom(faced)))
    }
}
