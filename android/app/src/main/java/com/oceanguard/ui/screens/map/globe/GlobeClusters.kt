package com.oceanguard.ai.ui.screens.map.globe

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

/** Points that fall in the same cell of the grid, with the box that holds them. */
data class PointGroup(
    val latitude: Double,
    val longitude: Double,
    val members: List<Int>,
    val spanDegrees: Double,
)

/** Groups nearby points on the globe so that a crowd shows as one circle with a count. */
object GlobeClusters {
    const val CELL_DEGREES = 4.0

    /** Zoom for a lone point. */
    const val POINT_ZOOM = 14.0
    /** Share of the shorter side a framed group should fill. */
    private const val FILL = 0.6

    /**
     * Groups [points] (latitude, longitude) by the cell of [cellDegrees] they fall in. A group sits
     * at the mean of its members and reports the larger side of its box, in degrees.
     */
    fun group(points: List<Pair<Double, Double>>, cellDegrees: Double = CELL_DEGREES): List<PointGroup> {
        val cells = LinkedHashMap<Pair<Long, Long>, MutableList<Int>>()
        points.forEachIndexed { index, (lat, lon) ->
            val key = floor((lat + 90.0) / cellDegrees).toLong() to floor((lon + 180.0) / cellDegrees).toLong()
            cells.getOrPut(key) { mutableListOf() }.add(index)
        }
        return cells.values.map { members -> summarise(points, members) }
    }

    private fun summarise(points: List<Pair<Double, Double>>, members: List<Int>): PointGroup {
        val lats = members.map { points[it].first }
        val lons = members.map { points[it].second }
        return PointGroup(lats.average(), lons.average(), members, max(lats.max() - lats.min(), lons.max() - lons.min()))
    }

    /**
     * Flat-map zoom that frames a group [spanDegrees] across in a viewport whose shorter side is
     * [minDimensionDp]. A lone point, or points on top of each other, get [POINT_ZOOM]. Never below
     * the zoom where the flat map takes over, so the flight always ends on the map.
     */
    fun frameZoom(spanDegrees: Double, minDimensionDp: Double): Double {
        if (spanDegrees < MIN_SPAN) return POINT_ZOOM
        val zoom = ln(FILL * minDimensionDp * 360.0 / (GlobeMath.TILE_SIZE * spanDegrees)) / ln(2.0)
        return zoom.coerceIn(GlobeTransition.ENTER_MAP_ZOOM, POINT_ZOOM)
    }

    private const val MIN_SPAN = 0.0005
}
