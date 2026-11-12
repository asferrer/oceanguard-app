package com.oceanguard.ai.ui.screens.map.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeClustersTest {

    @Test
    fun nearbyPointsShareAGroupAndFarOnesDoNot() {
        val points = listOf(38.0 to -0.5, 38.4 to -0.1, 38.9 to -0.2, -30.0 to 150.0)

        val groups = GlobeClusters.group(points)

        assertEquals(2, groups.size)
        assertEquals(listOf(0, 1, 2), groups[0].members)
        assertEquals(listOf(3), groups[1].members)
    }

    @Test
    fun aGroupSitsAtTheMeanOfItsMembers() {
        val g = GlobeClusters.group(listOf(10.0 to 10.0, 11.0 to 11.0)).single()

        assertEquals(10.5, g.latitude, 1e-9)
        assertEquals(10.5, g.longitude, 1e-9)
        assertEquals(1.0, g.spanDegrees, 1e-9)
    }

    @Test
    fun everyPointBelongsToExactlyOneGroup() {
        val points = List(50) { (it * 7 % 170 - 85).toDouble() to (it * 13 % 350 - 175).toDouble() }

        val members = GlobeClusters.group(points).flatMap { it.members }.sorted()

        assertEquals(points.indices.toList(), members)
    }

    @Test
    fun aLonePointHasNoSpanAndGetsThePointZoom() {
        val g = GlobeClusters.group(listOf(5.0 to 5.0)).single()

        assertEquals(0.0, g.spanDegrees, 0.0)
        assertEquals(GlobeClusters.POINT_ZOOM, GlobeClusters.frameZoom(g.spanDegrees, 360.0), 0.0)
    }

    @Test
    fun widerGroupsFrameAtLowerZoomAndNeverBelowTheHandover() {
        val narrow = GlobeClusters.frameZoom(0.5, 360.0)
        val wide = GlobeClusters.frameZoom(3.0, 360.0)
        val huge = GlobeClusters.frameZoom(300.0, 360.0)

        assertTrue(narrow > wide)
        assertEquals(GlobeTransition.ENTER_MAP_ZOOM, huge, 0.0)
        assertTrue(GlobeTransition.shouldEnterMap(wide))
    }
}
