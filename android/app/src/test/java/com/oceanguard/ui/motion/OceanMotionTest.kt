package com.oceanguard.ai.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OceanMotionTest {

    @Test fun durationsAreTheFourMotionSteps() {
        assertEquals(150, OceanMotion.FAST_MS)
        assertEquals(300, OceanMotion.STANDARD_MS)
        assertEquals(450, OceanMotion.TRANSITION_MS)
        assertEquals(800, OceanMotion.SIGNATURE_MS)
    }

    @Test fun exitIsFasterThanEnter() {
        assertTrue(OceanMotion.exit<Float>().durationMillis < OceanMotion.standard<Float>().durationMillis)
    }

    @Test fun springsKeepTheirStiffnessAndDamping() {
        val snap = OceanMotion.snapSpring<Float>()
        assertEquals(420f, snap.stiffness, 0f)
        assertEquals(0.732f, snap.dampingRatio, 0.001f)
        val soft = OceanMotion.softSpring<Float>()
        assertEquals(180f, soft.stiffness, 0f)
        assertEquals(0.671f, soft.dampingRatio, 0.001f)
        val pop = OceanMotion.popSpring<Float>()
        assertEquals(320f, pop.stiffness, 0f)
        assertEquals(0.503f, pop.dampingRatio, 0.001f)
    }

    @Test fun onlyAScaleOfZeroMeansRemoveAnimations() {
        assertTrue(OceanMotion.isAnimatorOff(0f))
        assertFalse(OceanMotion.isAnimatorOff(0.5f))
        assertFalse(OceanMotion.isAnimatorOff(1f))
    }

    @Test fun phaseWrapsAndNeverReachesOne() {
        assertEquals(0.25f, OceanMotion.advancePhase(0f, 250, 1000), 1e-5f)
        assertEquals(0.1f, OceanMotion.advancePhase(0.9f, 200, 1000), 1e-5f)
        assertEquals(0.5f, OceanMotion.advancePhase(0.5f, 3000, 1000), 1e-5f)
    }

    @Test fun phaseIsUntouchedWithoutAPeriod() {
        assertEquals(0.4f, OceanMotion.advancePhase(0.4f, 100, 0), 0f)
    }
}
