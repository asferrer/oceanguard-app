package com.oceanguard.ai.ui.screens.report

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.data.settings.ReportPeriod
import com.oceanguard.ai.data.settings.ReportPeriodSelection
import com.oceanguard.ai.inference.ReportType
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.components.sonar.SonarSection
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the person has chosen for the next report; every field is kept between visits. */
internal data class ReportChoices(
    val period: ReportPeriodSelection,
    val language: String,
    val audience: String,
)

internal data class ReportChoiceActions(
    val onPeriod: (ReportPeriod) -> Unit,
    val onPickDates: () -> Unit,
    val onLanguage: (String) -> Unit,
    val onAudience: (String) -> Unit,
)

/**
 * Zone, period, language and audience together, in the order they are decided. The zone
 * selector is always on view; without any located photo it explains why and offers to add one.
 */
@Composable
internal fun ReportSetupPanel(
    zones: ReportZones,
    hasSessions: Boolean,
    hasObservations: Boolean,
    type: ReportType,
    onType: (ReportType) -> Unit,
    selection: ReportSelection,
    choices: ReportChoices,
    actions: ReportChoiceActions,
    enabled: Boolean,
    boundsMap: MutableMap<String, Rect>,
    onAddLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SonarSection(stringResource(R.string.report_type_title)) { ReportTypeChips(type, onType, enabled) }
        SonarSection(stringResource(R.string.results_setup_scope)) {
            when {
                zones.clusters.isNotEmpty() -> Box(Modifier.spotlightTarget("reports_zone", boundsMap)) {
                    ZoneSelector(
                        zoneNames = zones.names,
                        selectedIndex = zones.selectedIndex,
                        onZoneSelected = { zones.selectedIndex = it },
                        enabled = enabled && zones.names.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                type != ReportType.DEBRIS && !hasObservations && (type == ReportType.SPECIES || !hasSessions) -> InfoCard(R.string.report_no_species_yet)
                type == ReportType.SPECIES && hasObservations -> InfoCard(R.string.report_species_no_gps)
                hasSessions && type != ReportType.SPECIES -> NoLocationCard(onAddLocation)
            }
            val zone = zones.selectedZone
            if (zone != null) {
                Box(Modifier.spotlightTarget("reports_preview", boundsMap)) {
                    if (type == ReportType.DEBRIS) {
                        ZonePreviewCard(zone = zone, zoneName = zones.selectedName, dayCount = zone.dayCount())
                    } else {
                        AreaPreviewCard(zones.selectedName, selection)
                    }
                }
            }
            PeriodChips(choices.period, actions, enabled)
        }
        LanguageAndAudience(choices, actions, enabled, boundsMap)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportTypeChips(type: ReportType, onType: (ReportType) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in ReportType.entries) {
            FilterChip(
                selected = type == option,
                enabled = enabled,
                onClick = { onType(option) },
                label = { Text(stringResource(option.labelRes())) },
            )
        }
    }
}

private fun ReportType.labelRes(): Int = when (this) {
    ReportType.DEBRIS -> R.string.report_type_debris
    ReportType.SPECIES -> R.string.report_type_species
    ReportType.COMBINED -> R.string.report_type_combined
}

/** What the chosen area and period hold for a species or combined report. */
@Composable
private fun AreaPreviewCard(name: String, selection: ReportSelection) {
    SonarCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (name.isNotEmpty()) Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            val species = selection.observations.map { it.scientificName }.distinct().size
            Text(
                text = stringResource(R.string.report_area_species, selection.observations.size, species),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (selection.type == ReportType.COMBINED) {
                Text(
                    text = stringResource(R.string.report_area_debris, selection.sessions.size, selection.sessions.sumOf { it.totalCount }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun InfoCard(message: Int) {
    SonarCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.LocationOff, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private fun com.oceanguard.ai.data.ZoneCluster.dayCount(): Int =
    com.oceanguard.ai.data.ZoneAggregator.groupByDay(this).size

@Composable
private fun NoLocationCard(onAddLocation: () -> Unit) {
    SonarCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.LocationOff, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = stringResource(R.string.results_report_no_gps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        SonarButton(
            text = stringResource(R.string.results_add_location),
            onClick = onAddLocation,
            style = SonarButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodChips(selection: ReportPeriodSelection, actions: ReportChoiceActions, enabled: Boolean) {
    val format = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (period in ReportPeriod.entries) {
            val custom = period == ReportPeriod.CUSTOM
            FilterChip(
                selected = selection.period == period,
                enabled = enabled,
                onClick = { if (custom) actions.onPickDates() else actions.onPeriod(period) },
                label = {
                    Text(
                        if (custom && selection.period == period) selection.customLabel(format)
                        else stringResource(period.labelRes()),
                    )
                },
            )
        }
    }
}

private fun ReportPeriodSelection.customLabel(format: SimpleDateFormat): String {
    val start = customStartMs?.let { format.format(Date(it)) } ?: "…"
    val end = customEndMs?.let { format.format(Date(it)) } ?: "…"
    return "$start – $end"
}

private fun ReportPeriod.labelRes(): Int = when (this) {
    ReportPeriod.ALL -> R.string.results_period_all
    ReportPeriod.LAST_7_DAYS -> R.string.results_period_7
    ReportPeriod.LAST_30_DAYS -> R.string.results_period_30
    ReportPeriod.CUSTOM -> R.string.results_period_custom
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageAndAudience(
    choices: ReportChoices,
    actions: ReportChoiceActions,
    enabled: Boolean,
    boundsMap: MutableMap<String, Rect>,
) {
    SonarSection(stringResource(R.string.results_setup_voice)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.spotlightTarget("reports_language", boundsMap)) {
                LanguageChip(choices.language, actions.onLanguage, enabled)
            }
            for (key in SettingsRepository.REPORT_AUDIENCES.keys) {
                FilterChip(
                    selected = choices.audience == key,
                    enabled = enabled,
                    onClick = { actions.onAudience(key) },
                    label = { Text(stringResource(audienceLabel(key))) },
                )
            }
        }
    }
}

private fun audienceLabel(key: String): Int = when (key) {
    "ngo" -> R.string.audience_ngo
    "citizen" -> R.string.audience_citizen
    else -> R.string.audience_scientific
}
