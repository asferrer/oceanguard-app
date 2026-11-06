package com.oceanguard.ai.ui.screens.report

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.oceanguard.ai.data.SettingsRepository

/** Language of the report, picked from a menu; the choice is kept by the caller. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageChip(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val languageName = SettingsRepository.SUPPORTED_LANGUAGES[selectedLanguage] ?: selectedLanguage

    Box {
        FilterChip(
            selected = true,
            onClick = { if (enabled) expanded = true },
            label = { Text(languageName, style = MaterialTheme.typography.labelMedium) },
            enabled = enabled,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SettingsRepository.SUPPORTED_LANGUAGES.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onLanguageSelected(code)
                        expanded = false
                    },
                )
            }
        }
    }
}
