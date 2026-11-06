package com.oceanguard.ai.ui.screens.report

import android.content.Context
import android.net.Uri
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.ui.screens.results.ReportExportFormat
import com.oceanguard.ai.utils.DataExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes the report (or its sessions) in [format] and opens the share sheet.
 * @return false when the file could not be produced, so the caller can say so.
 */
internal suspend fun exportReport(
    context: Context,
    format: ReportExportFormat,
    report: GeneratedReport,
    sessions: List<DetectionSession>,
): Boolean {
    val uri: Uri? = withContext(Dispatchers.IO) {
        when (format) {
            ReportExportFormat.PDF -> DataExporter.exportToPdf(context, report, sessions)
            ReportExportFormat.MARKDOWN ->
                DataExporter.exportToMarkdown(context, report.text, report.locationName ?: "OceanGuard_Survey")
            ReportExportFormat.CSV -> DataExporter.exportToCSV(context, sessions)
            ReportExportFormat.GEOJSON -> DataExporter.exportToGeoJson(context, sessions)
        }
    }
    if (uri == null) return false
    DataExporter.shareFile(context, uri, format.mimeType)
    return true
}
