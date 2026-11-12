package com.oceanguard.ai.ui.screens.map

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow

/** A coordinate to group. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** Points that sit so close on screen that they would stack, as indices into the input list. */
data class ProximityGroup(val members: List<Int>, val latitude: Double, val longitude: Double)

/**
 * Screen-space grouping of markers: photos of one dive are metres apart, so a few zoom levels in
 * they stack on top of each other. Points closer than [LINK_PINS] pin diameters on screen join one
 * group; the threshold is in metres and follows the zoom, so zooming in splits a group once its
 * members are far enough apart to stand next to each other.
 *
 * Free of Android types, so it can be tested on the JVM.
 */
object ProximityGrouping {
    /** Two points closer than this many pin diameters are one group. */
    const val LINK_PINS = 1.5

    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
    private const val WORLD_DP_AT_ZOOM_0 = 512.0

    /** Ground metres that one dp covers on the map at [zoom] and [latitude]. */
    fun metresPerDp(zoom: Double, latitude: Double): Double =
        EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(latitude)) / (WORLD_DP_AT_ZOOM_0 * 2.0.pow(zoom))

    /** The distance, in metres, under which two points are drawn as one. */
    fun linkMetres(zoom: Double, latitude: Double, pinDp: Double): Double =
        LINK_PINS * pinDp * metresPerDp(zoom, latitude)

    /**
     * Groups [points] that are within [linkMetres] of each other, directly or through a chain of
     * neighbours. Every point is in exactly one group; a point with no neighbour is a group of one.
     * Groups are ordered by their first member, and so are the members of each.
     *
     * @param zoom Zoom of the flat map.
     * @param pinDp Diameter of one marker, in dp.
     */
    fun group(points: List<GeoPoint>, zoom: Double, pinDp: Double): List<ProximityGroup> {
        if (points.isEmpty()) return emptyList()
        val meanLat = points.sumOf { it.latitude } / points.size
        val link = linkMetres(zoom, meanLat, pinDp)
        val parent = IntArray(points.size) { it }
        for ((a, b) in neighbourPairs(points, meanLat, link)) union(parent, a, b)
        return points.indices
            .groupBy { find(parent, it) }
            .values
            .sortedBy { it.first() }
            .map { members -> groupOf(points, members) }
    }

    private fun groupOf(points: List<GeoPoint>, members: List<Int>) = ProximityGroup(
        members = members,
        latitude = members.sumOf { points[it].latitude } / members.size,
        longitude = members.sumOf { points[it].longitude } / members.size,
    )

    /** Pairs closer than [link] metres, found through a grid of cells that size so it is not quadratic. */
    private fun neighbourPairs(points: List<GeoPoint>, meanLat: Double, link: Double): List<Pair<Int, Int>> {
        val metresPerDegree = EARTH_CIRCUMFERENCE_M / 360.0
        val cellLat = link / metresPerDegree
        val cellLon = link / (metresPerDegree * cos(Math.toRadians(meanLat)).coerceAtLeast(MIN_COS))
        val cells = HashMap<Pair<Long, Long>, MutableList<Int>>()
        points.forEachIndexed { i, p ->
            cells.getOrPut(cellOf(p, cellLat, cellLon)) { mutableListOf() }.add(i)
        }
        val pairs = ArrayList<Pair<Int, Int>>()
        points.forEachIndexed { i, p ->
            val (cy, cx) = cellOf(p, cellLat, cellLon)
            for (dy in -1L..1L) for (dx in -1L..1L) {
                cells[(cy + dy) to (cx + dx)]?.forEach { j ->
                    if (j > i && distanceMetres(p, points[j], metresPerDegree) <= link) pairs.add(i to j)
                }
            }
        }
        return pairs
    }

    private fun cellOf(p: GeoPoint, cellLat: Double, cellLon: Double): Pair<Long, Long> =
        floor(p.latitude / cellLat).toLong() to floor(p.longitude / cellLon).toLong()

    /** Flat-earth distance: exact enough at the scale of a pin, and cheap. */
    private fun distanceMetres(a: GeoPoint, b: GeoPoint, metresPerDegree: Double): Double {
        val dLat = (a.latitude - b.latitude) * metresPerDegree
        val meanLat = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val dLon = wrapDegrees(a.longitude - b.longitude) * metresPerDegree * cos(meanLat)
        return kotlin.math.hypot(dLat, dLon)
    }

    private fun wrapDegrees(d: Double): Double = if (abs(d) > 180.0) d - 360.0 * kotlin.math.sign(d) else d

    private fun find(parent: IntArray, i: Int): Int {
        var root = i
        while (parent[root] != root) root = parent[root]
        var node = i
        while (parent[node] != root) node = parent[node].also { parent[node] = root }
        return root
    }

    private fun union(parent: IntArray, a: Int, b: Int) {
        val ra = find(parent, a)
        val rb = find(parent, b)
        if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
    }

    private const val MIN_COS = 0.05
}
