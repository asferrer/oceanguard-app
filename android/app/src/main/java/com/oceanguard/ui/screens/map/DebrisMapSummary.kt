package com.oceanguard.ai.ui.screens.map

import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ZoneCluster

/** A photo in which a debris type was detected: the analysis it opens and how many objects of the type it holds. */
data class DebrisPhoto(val sessionId: Long, val uri: String, val takenMs: Long, val count: Int)

/**
 * One debris type detected at one place, the way [com.oceanguard.ai.ui.screens.map.species.SpeciesEntry]
 * is one species: its MarineDex type, how many objects, in which photos (newest first) and when last.
 */
data class DebrisTypeHere(
    val type: DebrisType,
    val objectCount: Int,
    val photos: List<DebrisPhoto>,
    val lastSeenMs: Long,
)

/**
 * Photos of debris the user tapped on the map: a zone, a study zone or a single photo.
 *
 * @param zone The zone they make up, which opens the zone sheet; null for a single photo.
 */
data class DebrisPlace(val sessions: List<DetectionSession>, val zone: ZoneCluster?) {
    val types: List<DebrisTypeHere> by lazy { DebrisMapSummary.typesOf(sessions) }
    val healthScore: Int get() = if (sessions.isEmpty()) 0 else sessions.map { it.healthScore }.average().toInt()
}

/** Groups the detections of some photos by debris type, as the map lists species. Free of Android types. */
object DebrisMapSummary {

    /**
     * One entry per MarineDex type ([DebrisType.canonical]) detected in [sessions], most objects
     * first (ties by type order). Photos with nothing detected add nothing.
     */
    fun typesOf(sessions: List<DetectionSession>): List<DebrisTypeHere> =
        sessions
            .flatMap { s -> s.debrisList.map { it.type.canonical() to s } }
            .groupBy({ it.first }, { it.second })
            .map { (type, hits) -> entryOf(type, hits) }
            .sortedWith(compareByDescending<DebrisTypeHere> { it.objectCount }.thenBy { it.type.ordinal })

    private fun entryOf(type: DebrisType, hits: List<DetectionSession>): DebrisTypeHere {
        val photos = hits
            .groupBy { it.id }
            .values
            .map { same -> same.first().let { DebrisPhoto(it.id, it.thumbnailUri ?: it.imageUri, it.timestamp.time, same.size) } }
            .sortedByDescending { it.takenMs }
        return DebrisTypeHere(type, hits.size, photos, photos.maxOf { it.takenMs })
    }

    /** The sessions behind the pins of a study-zone marker, in the order of its members. */
    fun sessionsOf(group: PinGroupMarker, zones: List<ZoneCluster>): List<DetectionSession> {
        val byId = zones.flatMap { it.sessions }.associateBy { it.id }
        return group.members.mapNotNull { byId[it.sessionId] }
    }
}
