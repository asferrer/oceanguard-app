package com.oceanguard.ai.ui.screens.report

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oceanguard.ai.data.DayGroup
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.GeneratedReport
import com.oceanguard.ai.data.ZoneAggregator
import com.oceanguard.ai.data.ZoneCluster
import com.oceanguard.ai.data.settings.ReportPeriodSelection
import com.oceanguard.ai.inference.ZoneReportInput
import com.oceanguard.ai.ui.screens.results.inRange

/** What the page is showing apart from the form: nothing, an open report, or a failed run. */
internal sealed class ReportScreenState {
    data object Idle : ReportScreenState()

    data class ViewingReport(val report: GeneratedReport) : ReportScreenState()

    data class Error(val message: String) : ReportScreenState()
}

/** Screen-level state shared by the page, the effects and the dialogs. */
@Stable
internal class ReportUiState {
    var screen by mutableStateOf<ReportScreenState>(ReportScreenState.Idle)
    var reportToDelete by mutableStateOf<GeneratedReport?>(null)
    var newReportId by mutableStateOf<Long?>(null)
    var exporting by mutableStateOf(false)
    var showDownloadDialog by mutableStateOf(false)

    /** Set when the person pressed Generate without the model: the report starts once it arrives. */
    var pendingStart by mutableStateOf(false)

    /** Kept while the report slides out, so the overlay does not empty before it is gone. */
    var lastViewed by mutableStateOf<GeneratedReport?>(null)

    val viewing: GeneratedReport? get() = (screen as? ReportScreenState.ViewingReport)?.report

    fun open(report: GeneratedReport) {
        newReportId = null
        lastViewed = report
        screen = ReportScreenState.ViewingReport(report)
    }

    fun close() {
        screen = ReportScreenState.Idle
    }
}

/** Sessions and days of the chosen zone that fall inside the chosen period. */
internal class ReportSelection(
    val sessions: List<DetectionSession>,
    val days: List<DayGroup>,
    val startMs: Long?,
    val endMs: Long?,
) {
    companion object {
        fun of(zone: ZoneCluster?, period: ReportPeriodSelection, nowMs: Long = System.currentTimeMillis()): ReportSelection {
            val (start, end) = period.range(nowMs)
            val sessions = zone?.sessions.orEmpty().filter { inRange(it.timestamp.time, start, end) }
            val days = zone?.let { ZoneAggregator.groupByDay(it) }.orEmpty().filter { inRange(it.date.time, start, end) }
            return ReportSelection(sessions, days, start, end)
        }
    }

    fun toInput(zones: ReportZones): ZoneReportInput {
        val zone = requireNotNull(zones.selectedZone) { "A zone must be selected to build the report input" }
        return ZoneReportInput(
            locationName = zones.selectedName,
            centroidLat = zone.centroidLat,
            centroidLon = zone.centroidLon,
            sessions = sessions,
            dayGroups = days,
            trend = zone.trend,
            dateRangeStartMs = startMs,
            dateRangeEndMs = endMs,
        )
    }
}
