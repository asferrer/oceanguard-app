package com.oceanguard.ai.utils

import com.oceanguard.ai.data.DetectionSession
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * GeoJSON FeatureCollection for sharing: one Point per photo that has a location, with the model
 * that analysed it. Photos without a location are left out, since a Point cannot exist without one.
 * It is written by hand so it can be checked without the Android JSON classes.
 */
internal fun sessionsToExportGeoJson(sessions: List<DetectionSession>): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    val features = sessions.mapNotNull { session ->
        val location = session.location ?: return@mapNotNull null
        val coordinates = "${jsonNumber(location.longitude)},${jsonNumber(location.latitude)}"
        val properties = buildList {
            add("\"sessionId\":${session.id}")
            add("\"timestamp\":${jsonString(dateFormat.format(session.timestamp))}")
            add("\"totalCount\":${session.totalCount}")
            if (session.totalCount > 0) add("\"healthScore\":${session.healthScore}")
            session.modelName?.let { add("\"model\":${jsonString(it)}") }
        }.joinToString(",")
        "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[$coordinates]}," +
            "\"properties\":{$properties}}"
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[${features.joinToString(",")}]}"
}

private fun jsonNumber(value: Double): String = String.format(Locale.US, "%.6f", value)

internal fun jsonString(value: String): String {
    val escaped = buildString {
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append(String.format(Locale.US, "\\u%04x", c.code))
                else -> append(c)
            }
        }
    }
    return "\"$escaped\""
}
