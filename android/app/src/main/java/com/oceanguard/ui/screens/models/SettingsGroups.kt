package com.oceanguard.ai.ui.screens.models

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val ROW_GAP = 12.dp

/** App language, theme and the capture preview. */
@Composable
fun GeneralGroup(
    app: OceanGuardApp,
    scope: CoroutineScope,
    bounds: MutableMap<String, Rect>,
) {
    val settings = app.settingsRepository
    val confirmCapture by settings.confirmCapture.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_CONFIRM_CAPTURE,
    )
    val darkMode by settings.darkMode.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_DARK_MODE,
    )
    SettingsGroup(stringResource(R.string.settings_group_general)) {
        AppLanguageField(app, scope)
        SettingsToggleRow(
            title = stringResource(R.string.settings_label_dark_mode),
            description = stringResource(R.string.settings_desc_dark_mode),
            checked = darkMode,
            onCheckedChange = { scope.launch { settings.setDarkMode(it) } },
            modifier = Modifier.spotlightTarget("settings_theme", bounds),
        )
        SettingsToggleRow(
            title = stringResource(R.string.settings_label_confirm_capture),
            description = stringResource(R.string.settings_desc_confirm_capture),
            checked = confirmCapture,
            onCheckedChange = { scope.launch { settings.setConfirmCapture(it) } },
        )
    }
}

/** Names of the languages in their own language, so they are the same whatever the app uses. */
private val LANGUAGE_NAMES = listOf(
    "en" to "English",
    "es" to "Español",
    "fr" to "Français",
    "de" to "Deutsch",
    "it" to "Italiano",
    "pt" to "Português",
)

@Composable
private fun AppLanguageField(app: OceanGuardApp, scope: CoroutineScope) {
    val settings = app.settingsRepository
    val code by settings.appLanguage.collectAsStateWithLifecycle(initialValue = "")
    val system = stringResource(R.string.settings_language_system)
    val options = listOf("" to system) + LANGUAGE_NAMES
    LabeledDropdown(
        title = stringResource(R.string.settings_label_interface_language),
        description = stringResource(R.string.settings_desc_interface_language),
        selectedText = options.toMap()[code] ?: code,
        options = options,
        onSelect = { picked ->
            scope.launch { settings.setAppLanguage(picked) }
            AppCompatDelegate.setApplicationLocales(
                if (picked.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(picked)
                },
            )
        },
    )
}

/** Language and audience of the generated reports. */
@Composable
fun ReportsGroup(app: OceanGuardApp, scope: CoroutineScope) {
    val settings = app.settingsRepository
    val language by settings.language.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_LANGUAGE)
    val audience by settings.reportAudience.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_REPORT_AUDIENCE,
    )
    val audiences = listOf(
        "scientific" to stringResource(R.string.audience_scientific),
        "ngo" to stringResource(R.string.audience_ngo),
        "citizen" to stringResource(R.string.audience_citizen),
    )
    SettingsGroup(stringResource(R.string.settings_section_reports)) {
        LabeledDropdown(
            title = stringResource(R.string.settings_label_report_language),
            description = stringResource(R.string.settings_desc_report_language),
            selectedText = SettingsRepository.SUPPORTED_LANGUAGES[language] ?: language,
            options = SettingsRepository.SUPPORTED_LANGUAGES.toList(),
            onSelect = { scope.launch { settings.setLanguage(it) } },
        )
        LabeledDropdown(
            title = stringResource(R.string.settings_label_report_audience),
            description = stringResource(R.string.settings_desc_report_audience),
            selectedText = audiences.toMap()[audience] ?: audience,
            options = audiences,
            onSelect = { scope.launch { settings.setReportAudience(it) } },
        )
    }
}

/** Contribution, online species info and the reset of all data. */
@Composable
fun PrivacyGroup(
    app: OceanGuardApp,
    viewModel: MainViewModel,
    scope: CoroutineScope,
    bounds: MutableMap<String, Rect>,
) {
    val settings = app.settingsRepository
    val online by settings.speciesOnlineEnrichmentEnabled.collectAsStateWithLifecycle(initialValue = false)
    SettingsGroup(stringResource(R.string.settings_group_privacy)) {
        Column(Modifier.spotlightTarget("settings_contribution", bounds)) {
            ContributionSettings(app, scope)
        }
        SettingsToggleRow(
            title = stringResource(R.string.settings_species_enrichment_title),
            description = stringResource(R.string.settings_species_enrichment_subtitle),
            checked = online,
            onCheckedChange = { scope.launch { settings.setSpeciesOnlineEnrichmentEnabled(it) } },
        )
        Column(Modifier.spotlightTarget("settings_data", bounds)) {
            ResetDataBlock(viewModel)
        }
    }
}

@Composable
private fun ResetDataBlock(viewModel: MainViewModel) {
    var confirming by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP / 2)) {
        FieldLabel(
            stringResource(R.string.settings_label_reset_all_data),
            stringResource(R.string.settings_desc_reset_all_data),
        )
        SonarButton(
            text = stringResource(R.string.settings_btn_reset_all_data),
            onClick = { confirming = true },
            style = SonarButtonStyle.Danger,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.settings_dialog_clear_title)) },
            text = { Text(stringResource(R.string.settings_dialog_clear_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAllData(); confirming = false }) {
                    Text(stringResource(R.string.settings_dialog_clear_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** Replay of the guided tours. */
@Composable
fun ToursBlock(onReplay: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ROW_GAP / 2)) {
        FieldLabel(
            stringResource(R.string.settings_section_guided_tours),
            stringResource(R.string.settings_desc_guided_tours),
        )
        SonarButton(
            text = stringResource(R.string.settings_btn_replay_tours),
            onClick = onReplay,
            style = SonarButtonStyle.Secondary,
            icon = Icons.Filled.Refresh,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
