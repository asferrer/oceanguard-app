package com.oceanguard.ai.ui.motion

import com.oceanguard.ai.ui.navigation.NavMotion
import com.oceanguard.ai.ui.navigation.OceanRouteRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OceanNavMotionTest {

    private fun motion(from: String?, to: String?) = OceanRouteRules.motionBetween(from, to)

    @Test fun tabsMoveOnTheXAxisByPosition() {
        assertEquals(NavMotion.AXIS_FORWARD, motion("home", "history"))
        assertEquals(NavMotion.AXIS_BACKWARD, motion("settings", "home"))
    }

    @Test fun tabRoutesWithArgumentsAreStillTabs() {
        assertEquals(NavMotion.AXIS_FORWARD, motion("home", "map?lat={lat}&lon={lon}"))
        assertEquals(
            NavMotion.AXIS_BACKWARD,
            motion("reports?lat={lat}&lon={lon}&name={name}", "map?lat={lat}&lon={lon}"),
        )
        assertTrue(OceanRouteRules.isTab("map?lat={lat}&lon={lon}"))
    }

    @Test fun detailsUseDepthInBothDirections() {
        assertEquals(NavMotion.DEPTH, motion("history", "session/{sessionId}"))
        assertEquals(NavMotion.DEPTH, motion("session/{sessionId}", "history"))
        assertEquals(NavMotion.DEPTH, motion("home", "marinedex"))
        assertEquals(NavMotion.DEPTH, motion("marinedex", "marinedex/{debrisType}?tab={tab}"))
    }

    @Test fun splashAndCameraDissolve() {
        assertEquals(NavMotion.DISSOLVE, motion("splash", "home"))
        assertEquals(NavMotion.DISSOLVE, motion("onboarding", "home"))
        assertEquals(NavMotion.DISSOLVE, motion("home", "camera?mode={mode}"))
    }

    @Test fun nothingMarksDetailsAsTabs() {
        assertFalse(OceanRouteRules.isTab("session/{sessionId}"))
        assertFalse(OceanRouteRules.isTab(null))
    }

    @Test fun cameraFamilyIsImmersive() {
        assertTrue(OceanRouteRules.isImmersive("camera?mode={mode}"))
        assertTrue(OceanRouteRules.isImmersive("live_detection"))
        assertFalse(OceanRouteRules.isImmersive("home"))
    }
}
