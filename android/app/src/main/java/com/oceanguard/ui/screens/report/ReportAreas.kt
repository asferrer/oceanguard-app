package com.oceanguard.ai.ui.screens.report

import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.HealthTrend
import com.oceanguard.ai.data.ZoneAggregator
import com.oceanguard.ai.data.ZoneCluster
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.inference.ReportType

/**
 * An area a report can be written about: the debris sessions that fall inside it (as the original
 * zone) and the species observations of the same place. For a debris report [observations] is empty.
 */
internal class ReportArea(val zone: ZoneCluster, val observations: List<SpeciesObservation>)

/** Builds the areas of the zone selector for each kind of report. */
internal object ReportAreas {

    /**
     * Debris reports keep the zones of [ZoneAggregator] unchanged. Species and combined reports
     * cluster the located sessions and observations together (same greedy rule and radius), so
     * "the same area" means the same place for both. A species report keeps the areas with
     * observations, a combined one those with anything.
     */
    fun build(
        type: ReportType,
        sessions: List<DetectionSession>,
        observations: List<SpeciesObservation>,
        radiusMeters: Double = ZONE_RADIUS_METERS,
    ): List<ReportArea> {
        if (type == ReportType.DEBRIS) return ZoneAggregator.cluster(sessions, radiusMeters).map { ReportArea(it, emptyList()) }
        val points = buildList {
            if (type.hasDebris) sessions.filter { it.location != null }.forEach { add(Point(it.location!!.latitude, it.location.longitude, it, null)) }
            observations.filter { it.location != null }.sortedByDescending { it.timestamp.time }
                .forEach { add(Point(it.location!!.latitude, it.location.longitude, null, it)) }
        }
        return group(points, radiusMeters)
            .map { g -> areaOf(g) }
            .filter { if (type == ReportType.SPECIES) it.observations.isNotEmpty() else true }
    }

    private class Point(val lat: Double, val lon: Double, val session: DetectionSession?, val observation: SpeciesObservation?)

    private class Group(var lat: Double, var lon: Double, val points: MutableList<Point>)

    private fun group(points: List<Point>, radius: Double): List<Group> {
        val groups = mutableListOf<Group>()
        for (p in points) {
            val near = groups.firstOrNull { ZoneAggregator.haversineDistance(p.lat, p.lon, it.lat, it.lon) <= radius }
            if (near == null) {
                groups += Group(p.lat, p.lon, mutableListOf(p))
            } else {
                val n = near.points.size
                near.lat = (near.lat * n + p.lat) / (n + 1)
                near.lon = (near.lon * n + p.lon) / (n + 1)
                near.points += p
            }
        }
        return groups
    }

    private fun areaOf(g: Group): ReportArea {
        val sessions = g.points.mapNotNull { it.session }
        val observations = g.points.mapNotNull { it.observation }
        val lat = g.points.map { it.lat }.average()
        val lon = g.points.map { it.lon }.average()
        val health = if (sessions.isEmpty()) 100 else sessions.map { it.healthScore }.average().toInt()
        return ReportArea(ZoneCluster(lat, lon, sessions, health, HealthTrend.STABLE), observations)
    }

    /** Same radius the debris zones use, in metres. */
    const val ZONE_RADIUS_METERS = 1000.0
}
