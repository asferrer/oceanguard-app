package com.oceanguard.ai.ui.components.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementFxMathTest {

    @Test fun timelineIsClampedToTheEffectDuration() {
        assertEquals(0f, AchievementFxMath.timeline(-5), 0f)
        assertEquals(0.5f, AchievementFxMath.timeline(AchievementFxMath.DURATION_MS / 2L), 0.001f)
        assertEquals(1f, AchievementFxMath.timeline(AchievementFxMath.DURATION_MS * 3L), 0f)
    }

    @Test fun rippleStartsStaggeredAndFadesOut() {
        assertNotNull(AchievementFxMath.pingProgress(0.1f, 0))
        assertNull("second ripple has not started", AchievementFxMath.pingProgress(0.1f, 2))
        assertNull("first ripple is over", AchievementFxMath.pingProgress(1.05f, 0))
        assertEquals(1f, AchievementFxMath.pingAlpha(0f), 0f)
        assertEquals(0f, AchievementFxMath.pingAlpha(1f), 0f)
    }

    @Test fun findingLightsUpOnlyAfterTheArmPassesIt() {
        val angle = AchievementFxMath.SWEEP_FINDINGS.first().first
        assertFalse(AchievementFxMath.findingVisible(0f, angle))
        assertTrue(AchievementFxMath.findingVisible(1f, angle))
    }

    @Test fun everyFindingIsReachedBeforeTheEffectEnds() {
        AchievementFxMath.SWEEP_FINDINGS.forEach { (angle, _) ->
            assertTrue(AchievementFxMath.findingVisible(1f, angle))
        }
    }

    @Test fun orbitClosesInAndFadesIn() {
        val start = AchievementFxMath.orbitPoint(0, 6, 0f)
        val end = AchievementFxMath.orbitPoint(0, 6, 1f)
        assertTrue(radius(start) > radius(end))
        assertEquals(0f, AchievementFxMath.orbitAlpha(0f), 0f)
        assertEquals(1f, AchievementFxMath.orbitAlpha(1f), 0f)
    }

    @Test fun orbitSpreadsSpritesAroundTheCentre() {
        val points = (0 until 6).map { AchievementFxMath.orbitPoint(it, 6, 0.4f) }
        assertEquals(6, points.distinct().size)
        assertEquals(FxPoint(0f, 0f), AchievementFxMath.orbitPoint(0, 0, 0.4f))
    }

    @Test fun tideRisesAndStopsBelowTheTop() {
        assertEquals(0f, AchievementFxMath.tideLevel(0f), 0f)
        assertTrue(AchievementFxMath.tideLevel(0.5f) > AchievementFxMath.tideLevel(0.2f))
        assertEquals(0.62f, AchievementFxMath.tideLevel(1f), 0f)
    }

    @Test fun networkPointsMoveTowardsTheCentre() {
        val start = AchievementFxMath.networkStart(3)
        val end = AchievementFxMath.networkPoint(start, 1f)
        assertTrue(kotlin.math.abs(end.x - 0.5f) < kotlin.math.abs(start.x - 0.5f))
        assertTrue(kotlin.math.abs(end.y - 0.5f) < kotlin.math.abs(start.y - 0.5f))
        assertEquals(start, AchievementFxMath.networkPoint(start, 0f))
    }

    @Test fun networkStartsStayInsideTheScreen() {
        repeat(AchievementFxMath.NETWORK_POINTS) {
            val p = AchievementFxMath.networkStart(it)
            assertTrue(p.x in 0f..1f && p.y in 0f..1f)
        }
    }

    @Test fun networkLinksHaveNoSelfLinksOrDuplicates() {
        val links = AchievementFxMath.networkLinks(AchievementFxMath.NETWORK_POINTS)
        assertTrue(links.isNotEmpty())
        assertEquals(links.size, links.toSet().size)
        assertTrue(links.all { (a, b) -> a < b && b < AchievementFxMath.NETWORK_POINTS })
        assertTrue(AchievementFxMath.networkLinks(1).isEmpty())
    }

    @Test fun linksAppearAfterTheNetworkHasStartedToForm() {
        assertFalse(AchievementFxMath.networkLinksVisible(0.1f))
        assertTrue(AchievementFxMath.networkLinksVisible(0.5f))
    }

    @Test fun raysGrowToFullLengthAndSpin() {
        assertEquals(0f, AchievementFxMath.rayLength(0f), 0f)
        assertEquals(1f, AchievementFxMath.rayLength(1f), 0f)
        assertTrue(AchievementFxMath.raySpin(1f) > AchievementFxMath.raySpin(0.5f))
    }

    private fun radius(p: FxPoint) = kotlin.math.sqrt(p.x * p.x + p.y * p.y)
}
