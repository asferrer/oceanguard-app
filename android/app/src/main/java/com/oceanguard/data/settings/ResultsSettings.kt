package com.oceanguard.ai.data.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oceanguard.ai.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Period a report covers. [CUSTOM] uses the dates picked in the calendar. */
enum class ReportPeriod(val key: String, val days: Int?) {
    ALL("all", null),
    LAST_7_DAYS("7d", 7),
    LAST_30_DAYS("30d", 30),
    CUSTOM("custom", null),
    ;

    companion object {
        fun fromKey(key: String?): ReportPeriod = entries.firstOrNull { it.key == key } ?: ALL
    }
}

/** What the person chose for the period, kept between visits. */
data class ReportPeriodSelection(
    val period: ReportPeriod = ReportPeriod.ALL,
    val customStartMs: Long? = null,
    val customEndMs: Long? = null,
) {
    /**
     * Inclusive bounds in epoch milliseconds, null meaning unbounded on that side. The preset
     * windows are relative to [nowMs] and have no upper bound, so a photo taken a minute ago counts.
     */
    fun range(nowMs: Long): Pair<Long?, Long?> = when (period) {
        ReportPeriod.ALL -> null to null
        ReportPeriod.CUSTOM -> customStartMs to customEndMs
        else -> period.days?.let { nowMs - it * DAY_MS } to null
    }
}

private object ResultsKeys {
    val PERIOD = stringPreferencesKey("results_report_period")
    val CUSTOM_START = longPreferencesKey("results_report_custom_start")
    val CUSTOM_END = longPreferencesKey("results_report_custom_end")
    val LANGUAGE = stringPreferencesKey("results_report_language")
}

val SettingsRepository.reportPeriodSelection: Flow<ReportPeriodSelection>
    get() = store.data.map { prefs ->
        ReportPeriodSelection(
            period = ReportPeriod.fromKey(prefs[ResultsKeys.PERIOD]),
            customStartMs = prefs[ResultsKeys.CUSTOM_START],
            customEndMs = prefs[ResultsKeys.CUSTOM_END],
        )
    }

suspend fun SettingsRepository.setReportPeriodSelection(selection: ReportPeriodSelection) {
    store.edit { prefs ->
        prefs[ResultsKeys.PERIOD] = selection.period.key
        selection.customStartMs?.let { prefs[ResultsKeys.CUSTOM_START] = it }
            ?: prefs.remove(ResultsKeys.CUSTOM_START)
        selection.customEndMs?.let { prefs[ResultsKeys.CUSTOM_END] = it }
            ?: prefs.remove(ResultsKeys.CUSTOM_END)
    }
}

/** Language chosen for reports; null until the person picks one, then the app language applies. */
val SettingsRepository.reportLanguage: Flow<String?>
    get() = store.data.map { prefs -> prefs[ResultsKeys.LANGUAGE] }

suspend fun SettingsRepository.setReportLanguage(code: String) {
    store.edit { prefs -> prefs[ResultsKeys.LANGUAGE] = code }
}
