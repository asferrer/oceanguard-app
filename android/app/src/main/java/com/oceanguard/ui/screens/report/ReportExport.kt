package com.oceanguard.ai.ui.screens.report

import android.content.Context
import android.net.Uri
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.inference.ReportType
import com.oceanguard.ai.ui.screens.results.ReportExportFormat
import com.oceanguard.ai.utils.DataExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The species side of an export: the observations the report was written from and how to name them. */
internal class SpeciesExportData(
    val observations: List<SpeciesObservation>,
    val catalog: SpeciesCatalog,
)

/**
 * Writes the report (or its sessions or observations) in [format] and opens the share sheet.
 * A species report exports its observations; a combined one its sessions, with the observations
 * added to the GeoJSON.
 * @return false when the file could not be produced, so the caller can say so.
 */
internal suspend fun exportReport(
    context: Context,
    format: ReportExportFormat,
    report: GeneratedReport,
    sessions: List<DetectionSession>,
    species: SpeciesExportData? = null,
): Boolean {
    val type = ReportType.fromKey(report.reportType)
    val uri: Uri? = withContext(Dispatchers.IO) {
        when (format) {
            ReportExportFormat.PDF -> DataExporter.exportToPdf(context, report, sessions)
            ReportExportFormat.MARKDOWN ->
                DataExporter.exportToMarkdown(context, report.text, report.locationName ?: "OceanGuard_Survey")
            ReportExportFormat.CSV ->
                if (type == ReportType.SPECIES && species != null) {
                    DataExporter.exportObservationsToCsv(context, species.observations, species.catalog, report.language)
                } else {
                    DataExporter.exportToCSV(context, sessions)
                }
            ReportExportFormat.GEOJSON ->
                if (type != ReportType.DEBRIS && species != null) {
                    DataExporter.exportSpeciesGeoJson(context, sessions, species.observations, species.catalog, report.language)
                } else {
                    DataExporter.exportToGeoJson(context, sessions)
                }
        }
    }
    if (uri == null) return false
    DataExporter.shareFile(context, uri, format.mimeType)
    return true
}
