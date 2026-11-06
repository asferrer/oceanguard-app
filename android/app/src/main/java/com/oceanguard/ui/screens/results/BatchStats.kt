package com.oceanguard.ai.ui.screens.results

import java.util.Locale

/** One analysed photo reduced to what the batch summary needs. */
data class PhotoOutcome(
    val healthScore: Int,
    val totalCount: Int,
    val typeNames: List<String>,
    val modelName: String?,
)

/** An object type that makes up most of a photo, which usually means one item counted many times. */
data class RepeatedObjects(val typeName: String, val count: Int)

data class BatchStats(
    val photos: Int,
    val failed: Int,
    val withDetections: Int,
    val withoutDetections: Int,
    val totalObjects: Int,
    /** Mean over photos that have detections; null when none do, since there is nothing to rate. */
    val averageHealth: Int?,
    val durationMs: Long,
    val modelLabel: String?,
    val repeated: RepeatedObjects?,
)

const val REPEAT_MIN_COUNT = 6
const val REPEAT_MIN_SHARE = 0.6f

/** The type that dominates [typeNames], or null if none repeats enough to be worth a warning. */
fun findRepeatedObjects(typeNames: List<String>): RepeatedObjects? {
    if (typeNames.size < REPEAT_MIN_COUNT) return null
    val top = typeNames.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
    val share = top.value.toFloat() / typeNames.size
    return if (top.value >= REPEAT_MIN_COUNT && share >= REPEAT_MIN_SHARE) {
        RepeatedObjects(top.key, top.value)
    } else {
        null
    }
}

/** Health to show for a photo: none when it has no detections, instead of a reassuring 100. */
fun displayedHealth(healthScore: Int, totalCount: Int): Int? =
    if (totalCount > 0) healthScore else null

/** Distinct model names in order of appearance, joined; null when none was recorded. */
fun modelLabel(names: List<String?>): String? =
    names.filterNotNull().filter { it.isNotBlank() }.distinct().joinToString(" · ").ifEmpty { null }

/** `null` items are photos whose analysis failed. */
fun summarizeBatch(outcomes: List<PhotoOutcome?>, durationMs: Long): BatchStats {
    val analysed = outcomes.filterNotNull()
    val withDetections = analysed.filter { it.totalCount > 0 }
    val repeated = analysed.mapNotNull { findRepeatedObjects(it.typeNames) }.maxByOrNull { it.count }
    return BatchStats(
        photos = outcomes.size,
        failed = outcomes.size - analysed.size,
        withDetections = withDetections.size,
        withoutDetections = analysed.size - withDetections.size,
        totalObjects = analysed.sumOf { it.totalCount },
        averageHealth = withDetections.map { it.healthScore }.average().takeUnless { it.isNaN() }?.toInt(),
        durationMs = durationMs,
        modelLabel = modelLabel(analysed.map { it.modelName }),
        repeated = repeated,
    )
}

/** `45 s`, `2 min 5 s`; the units are symbols, so they need no translation. */
fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000L).coerceAtLeast(0L)
    return if (seconds >= 60L) "${seconds / 60} min ${seconds % 60} s" else "$seconds s"
}

/** Mean time per analysed photo in seconds with one decimal, or null when unknown. */
fun secondsPerPhoto(durationMs: Long, analysedPhotos: Int): String? =
    if (durationMs <= 0L || analysedPhotos <= 0) {
        null
    } else {
        String.format(Locale.US, "%.1f", durationMs / 1000f / analysedPhotos)
    }
