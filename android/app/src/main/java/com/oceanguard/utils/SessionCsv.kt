package com.oceanguard.ai.utils

import com.oceanguard.ai.data.AnalysisModels
import com.oceanguard.ai.data.DetectionSession
import java.text.SimpleDateFormat
import java.util.Locale

/** Header of the sessions CSV; `model` was appended last so older readers keep their columns. */
internal const val SESSIONS_CSV_HEADER =
    "session_id,image_name,date,lat,lon,debris_type,material,confidence,health_score,model"

/**
 * One row per detected object, or one row with empty object fields for a photo with none.
 * The confidence cell stays empty when the detector was Gemma, whose "confidence" is not a model
 * score. The health score is empty for a photo without detections, which has nothing to rate.
 */
internal fun sessionsToCsv(sessions: List<DetectionSession>): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val lines = mutableListOf(SESSIONS_CSV_HEADER)
    for (session in sessions) {
        val prefix = sessionCsvPrefix(session, dateFormat.format(session.timestamp))
        val health = if (session.totalCount > 0) session.healthScore.toString() else ""
        val model = csvCell(session.modelName.orEmpty())
        if (session.debrisList.isEmpty()) {
            lines += "$prefix,,,,$health,$model"
            continue
        }
        val showConfidence = AnalysisModels.hasModelConfidence(session.modelName)
        for (debris in session.debrisList) {
            val confidence = if (showConfidence) String.format(Locale.US, "%.4f", debris.confidence) else ""
            lines += "$prefix,${debris.type.name},${debris.material.name},$confidence,$health,$model"
        }
    }
    return lines.joinToString("\n", postfix = "\n")
}

private fun sessionCsvPrefix(session: DetectionSession, date: String): String {
    val imageName = session.imageUri.substringAfterLast('/').ifEmpty { session.imageUri }
    val lat = session.location?.latitude?.let { String.format(Locale.US, "%.6f", it) }.orEmpty()
    val lon = session.location?.longitude?.let { String.format(Locale.US, "%.6f", it) }.orEmpty()
    return "${session.id},${csvCell(imageName)},${csvCell(date)},$lat,$lon"
}

/** Quotes a cell that holds a comma, a quote or a line break; quotes inside are doubled. */
internal fun csvCell(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }
