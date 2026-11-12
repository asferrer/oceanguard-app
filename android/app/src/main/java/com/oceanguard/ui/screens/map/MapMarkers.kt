package com.oceanguard.ai.ui.screens.map

import com.oceanguard.ai.data.ZoneAggregator
import com.oceanguard.ai.data.ZoneCluster
import com.oceanguard.ai.ui.screens.map.species.SpeciesSpot

/** Something drawn over the map at a real coordinate. */
sealed interface MapMarker {
    /** Stable identity across camera moves, so a marker keeps its state while the map pans. */
    val key: String
    val latitude: Double
    val longitude: Double
}

/** A zone that has not been opened yet: shows how many photos it holds. */
data class ClusterMarker(
    val zoneIndex: Int,
    override val latitude: Double,
    override val longitude: Double,
    val sessionCount: Int,
    val healthScore: Int,
) : MapMarker {
    override val key: String get() = "zone-$zoneIndex"
}

/** One photo of an opened zone, at the exact coordinate where it was taken. */
data class PinMarker(
    val sessionId: Long,
    val zoneIndex: Int,
    override val latitude: Double,
    override val longitude: Double,
    val imageUri: String,
    val healthScore: Int,
) : MapMarker {
    override val key: String get() = "session-$sessionId"
}

/** A grid cell of species observations that has not been opened yet: shows how many were seen there. */
data class SpeciesClusterMarker(
    val spotIndex: Int,
    override val latitude: Double,
    override val longitude: Double,
    val observationCount: Int,
    val speciesCount: Int,
) : MapMarker {
    override val key: String get() = "species-spot-$spotIndex"
}

/** One species at one place, once its cell is open: the sprite of the species in a badge. */
data class SpeciesPinMarker(
    val spotIndex: Int,
    val speciesKey: String?,
    val scientificName: String,
    override val latitude: Double,
    override val longitude: Double,
    val observationCount: Int,
    val lastSeenMs: Long,
) : MapMarker {
    override val key: String get() = "species-$spotIndex-${speciesKey ?: scientificName}"
}

/**
 * Photos that are so close at this zoom that their pins would stack: drawn as one study zone with
 * a stack of thumbnails, the count and the average health. Zooming in splits it again.
 */
data class PinGroupMarker(
    val members: List<PinMarker>,
    override val latitude: Double,
    override val longitude: Double,
) : MapMarker {
    val healthScore: Int get() = members.map { it.healthScore }.average().toInt()
    override val key: String get() = "group-${members.minOf { it.sessionId }}-${members.size}"
}

/** Species pins that would stack: one marker with the sprites and a count, opening a list. */
data class SpeciesGroupMarker(
    val members: List<SpeciesPinMarker>,
    override val latitude: Double,
    override val longitude: Double,
) : MapMarker {
    val observationCount: Int get() = members.sumOf { it.observationCount }
    override val key: String get() = "species-group-${members.first().key}-${members.size}"
}

/** "38.12345, -0.50000": signed decimals with a point, so they can be pasted into any map app. */
fun formatCoordinates(latitude: Double, longitude: Double): String =
    java.lang.String.format(java.util.Locale.ROOT, "%.5f, %.5f", latitude, longitude)

/** Camera that shows every zone at once. */
data class FitTarget(val latitude: Double, val longitude: Double, val zoom: Double)

/**
 * Decides what the map draws at each zoom: zones collapse into numbered clusters when seen from
 * far away and open into one photo pin per session when the camera is close.
 */
object MapMarkerPlanner {

    /** From this zoom on, zones are shown as photo pins instead of clusters. */
    const val EXPAND_ZOOM = 13.0

    /** Zoom the camera flies to when a cluster is tapped: close enough to open the zone. */
    const val FLY_ZOOM = 15.0

    /** Most markers composed at once; the rest are off screen or too crowded to tell apart. */
    const val MAX_MARKERS = 120

    private const val CLUSTER_MIN_DP = 48
    private const val CLUSTER_STEP_DP = 8
    private const val CLUSTER_MAX_DP = 72
    private const val SINGLE_ZONE_ZOOM = 14.0

    fun isExpanded(zoom: Double): Boolean = zoom >= EXPAND_ZOOM

    fun plan(zones: List<ZoneCluster>, expanded: Boolean): List<MapMarker> =
        if (expanded) pins(zones) else clusters(zones)

    /** Debris zones and species spots together, each in the form the zoom asks for. */
    fun planAll(zones: List<ZoneCluster>, spots: List<SpeciesSpot>, expanded: Boolean): List<MapMarker> =
        plan(zones, expanded) + planSpecies(spots, expanded)

    /** One cluster per species spot, or one pin per species in it once [expanded]. */
    fun planSpecies(spots: List<SpeciesSpot>, expanded: Boolean): List<MapMarker> =
        spots.flatMapIndexed { index, spot ->
            if (expanded) {
                spot.entries.map { e ->
                    SpeciesPinMarker(index, e.key, e.scientificName, e.latitude, e.longitude, e.count, e.lastSeenMs)
                }
            } else {
                listOf(
                    SpeciesClusterMarker(
                        spotIndex = index,
                        latitude = spot.latitude,
                        longitude = spot.longitude,
                        observationCount = spot.observationCount,
                        speciesCount = spot.entries.map { it.key ?: it.scientificName }.distinct().size,
                    ),
                )
            }
        }

    /** Diameter of a pin, the unit the stacking threshold is measured in. */
    const val PIN_DP = 40.0

    /** Zoom is quantised to this step before grouping, so a pinch does not regroup on every frame. */
    const val ZOOM_STEP = 0.25

    fun quantizeZoom(zoom: Double): Double = Math.round(zoom / ZOOM_STEP) * ZOOM_STEP

    /**
     * Merges the pins of [markers] that stack at [zoom] into [PinGroupMarker]s and
     * [SpeciesGroupMarker]s. A pin with no neighbour keeps its own marker, and the other kinds pass
     * through. Photos and species never merge with each other.
     */
    fun groupStacked(markers: List<MapMarker>, zoom: Double): List<MapMarker> {
        val pins = markers.filterIsInstance<PinMarker>()
        val speciesPins = markers.filterIsInstance<SpeciesPinMarker>()
        val rest = markers.filter { it !is PinMarker && it !is SpeciesPinMarker }
        val photos = ProximityGrouping.group(pins.map { GeoPoint(it.latitude, it.longitude) }, zoom, PIN_DP)
            .map { g ->
                if (g.members.size == 1) pins[g.members.first()] else PinGroupMarker(g.members.map { pins[it] }, g.latitude, g.longitude)
            }
        val species = ProximityGrouping.group(speciesPins.map { GeoPoint(it.latitude, it.longitude) }, zoom, PIN_DP)
            .map { g ->
                if (g.members.size == 1) {
                    speciesPins[g.members.first()]
                } else {
                    SpeciesGroupMarker(g.members.map { speciesPins[it] }, g.latitude, g.longitude)
                }
            }
        return rest + photos + species
    }

    /**
     * The zone a tapped study-zone marker opens: the original zone when the group is all of it,
     * else a zone made of just the grouped photos.
     */
    fun zoneFor(group: PinGroupMarker, zones: List<ZoneCluster>): ZoneCluster? {
        val ids = group.members.map { it.sessionId }.toSet()
        val inZone = group.members.map { it.zoneIndex }.distinct().singleOrNull()?.let { zones.getOrNull(it) }
        if (inZone != null && inZone.sessions.size == ids.size) return inZone
        val sessions = zones.flatMap { it.sessions }.filter { it.id in ids }
        return if (sessions.isEmpty()) null else ZoneAggregator.zoneOf(sessions)
    }

    private fun clusters(zones: List<ZoneCluster>): List<MapMarker> =
        zones.mapIndexed { index, zone ->
            ClusterMarker(
                zoneIndex = index,
                latitude = zone.centroidLat,
                longitude = zone.centroidLon,
                sessionCount = zone.sessions.size,
                healthScore = zone.aggregateHealthScore,
            )
        }

    private fun pins(zones: List<ZoneCluster>): List<MapMarker> =
        zones.flatMapIndexed { index, zone ->
            zone.sessions.mapNotNull { session ->
                val location = session.location ?: return@mapNotNull null
                PinMarker(
                    sessionId = session.id,
                    zoneIndex = index,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    imageUri = session.thumbnailUri ?: session.imageUri,
                    healthScore = session.healthScore,
                )
            }
        }

    /** Grows one step per digit of the count, so "15" and "150" are told apart at a glance. */
    fun clusterDiameterDp(count: Int): Int {
        val digits = count.coerceAtLeast(1).toString().length
        return (CLUSTER_MIN_DP + CLUSTER_STEP_DP * (digits - 1)).coerceAtMost(CLUSTER_MAX_DP)
    }

    /** True when a marker centred at ([xDp], [yDp]) can be seen in a [widthDp] by [heightDp] map. */
    fun isOnScreen(xDp: Float, yDp: Float, widthDp: Float, heightDp: Float, marginDp: Float): Boolean =
        xDp in -marginDp..(widthDp + marginDp) && yDp in -marginDp..(heightDp + marginDp)

    /**
     * Centre and zoom that frame every zone and every extra (latitude, longitude) point, such as
     * species spots; null when there is nothing to frame.
     */
    fun fitTarget(zones: List<ZoneCluster>, extra: List<Pair<Double, Double>> = emptyList()): FitTarget? {
        val lats = zones.map { it.centroidLat } + extra.map { it.first }
        val lons = zones.map { it.centroidLon } + extra.map { it.second }
        if (lats.isEmpty()) return null
        val zoom = if (lats.size == 1) {
            SINGLE_ZONE_ZOOM
        } else {
            zoomForSpan(maxOf(lats.max() - lats.min(), lons.max() - lons.min()))
        }
        return FitTarget(lats.average(), lons.average(), zoom)
    }

    private fun zoomForSpan(spanDegrees: Double): Double = when {
        spanDegrees < 0.01 -> 14.0
        spanDegrees < 0.1 -> 12.0
        spanDegrees < 1.0 -> 9.0
        spanDegrees < 10.0 -> 6.0
        else -> 3.0
    }
}
