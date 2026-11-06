package com.oceanguard.ai.ui.screens.report

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.data.settings.ReportPeriod
import com.oceanguard.ai.data.settings.ReportPeriodSelection
import com.oceanguard.ai.data.settings.reportLanguage
import com.oceanguard.ai.data.settings.reportPeriodSelection
import com.oceanguard.ai.data.settings.setReportLanguage
import com.oceanguard.ai.data.settings.setReportPeriodSelection
import kotlinx.coroutines.launch

/** Period, language and audience as saved, the actions that change them, and the date dialog. */
@Stable
internal class ReportChoicesState(
    val choices: ReportChoices,
    val actions: ReportChoiceActions,
    pickerVisible: Boolean,
    private val setPicker: (Boolean) -> Unit,
    private val applyDates: (Long?, Long?) -> Unit,
) {
    val pickerVisible: Boolean = pickerVisible

    fun dismissPicker() = setPicker(false)

    fun confirmDates(startMs: Long?, endMs: Long?) {
        setPicker(false)
        applyDates(startMs, endMs)
    }
}

/**
 * Reads the three choices from the settings and writes every change back, so the next report
 * starts from them. The language follows the app's own until the person picks another.
 */
@Composable
internal fun rememberReportChoices(settings: SettingsRepository): ReportChoicesState {
    val scope = rememberCoroutineScope()
    val period by settings.reportPeriodSelection.collectAsStateWithLifecycle(ReportPeriodSelection())
    val savedLanguage by settings.reportLanguage.collectAsStateWithLifecycle(null)
    val appLanguage by settings.language.collectAsStateWithLifecycle(SettingsRepository.DEFAULT_LANGUAGE)
    val audience by settings.reportAudience.collectAsStateWithLifecycle(SettingsRepository.DEFAULT_REPORT_AUDIENCE)
    var picker by remember { mutableStateOf(false) }

    val actions = ReportChoiceActions(
        onPeriod = { chosen ->
            scope.launch { settings.setReportPeriodSelection(ReportPeriodSelection(chosen)) }
        },
        onPickDates = { picker = true },
        onLanguage = { code -> scope.launch { settings.setReportLanguage(code) } },
        onAudience = { key -> scope.launch { settings.setReportAudience(key) } },
    )
    return ReportChoicesState(
        choices = ReportChoices(period, savedLanguage ?: appLanguage, audience),
        actions = actions,
        pickerVisible = picker,
        setPicker = { picker = it },
        applyDates = { start, end ->
            scope.launch {
                settings.setReportPeriodSelection(ReportPeriodSelection(ReportPeriod.CUSTOM, start, end))
            }
        },
    )
}
