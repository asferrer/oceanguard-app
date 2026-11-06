package com.oceanguard.ai.ui.screens.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.inference.DetectorType
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.inference.TextModelVariant
import com.oceanguard.ai.inference.VlmModelManager
import com.oceanguard.ai.inference.VlmProvider
import com.oceanguard.ai.ui.components.sonar.SonarSection
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val THRESHOLD_STEPS = 19
private val FIELD_GAP = 16.dp
private val CHIP_GAP = 12.dp

/**
 * Technical options, shown only with "Show advanced options". Everything the former developer
 * mode offered is here: threshold, precision, detection engine, provider, text model and variant.
 */
@Composable
fun AdvancedSection(
    app: OceanGuardApp,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val settings = app.settingsRepository
    SonarSection(stringResource(R.string.models_advanced_title), modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
            ThresholdField(settings, scope)
            PrecisionField(settings, scope)
            EngineField(app, scope)
            ProviderFields(app, scope)
            Text(
                text = stringResource(R.string.models_adv_tech_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThresholdField(settings: SettingsRepository, scope: CoroutineScope) {
    val threshold by settings.confidenceThreshold.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_CONFIDENCE_THRESHOLD,
    )
    Column {
        FieldLabel(
            stringResource(R.string.settings_label_confidence_threshold),
            stringResource(R.string.settings_desc_confidence_threshold),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
            Slider(
                value = threshold,
                onValueChange = { scope.launch { settings.setConfidenceThreshold(it) } },
                valueRange = 0f..1f,
                steps = THRESHOLD_STEPS,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.sonar_percent, Math.round(threshold * 100)),
                style = SonarTheme.type.data,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(56.dp),
            )
        }
    }
}

@Composable
private fun PrecisionField(settings: SettingsRepository, scope: CoroutineScope) {
    val precision by settings.detectorPrecision.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_DETECTOR_PRECISION,
    )
    var restartHint by remember { mutableStateOf(false) }
    Column {
        LabeledDropdown(
            title = stringResource(R.string.settings_label_detector_precision),
            description = stringResource(R.string.settings_desc_detector_precision),
            selectedText = SettingsRepository.DETECTOR_PRECISIONS[precision] ?: precision,
            options = SettingsRepository.DETECTOR_PRECISIONS.toList(),
            onSelect = { key ->
                scope.launch { settings.setDetectorPrecision(key) }
                if (key != precision) restartHint = true
            },
        )
        if (restartHint) {
            Text(
                text = stringResource(R.string.settings_hint_restart),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun EngineField(app: OceanGuardApp, scope: CoroutineScope) {
    val settings = app.settingsRepository
    val modeKey by settings.detectorMode.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_DETECTOR_MODE,
    )
    val refine by settings.refineWithGemma.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_REFINE_WITH_GEMMA,
    )
    val current = DetectorType.fromKey(modeKey)
    Column(verticalArrangement = Arrangement.spacedBy(FIELD_GAP / 2)) {
        LabeledDropdown(
            title = stringResource(R.string.settings_label_detector_mode),
            description = stringResource(R.string.settings_desc_detector_mode),
            selectedText = current.displayLabel,
            options = listOf(DetectorType.RT_DETR_V2, DetectorType.GEMMA4_VISION).map { it to it.displayLabel },
            onSelect = { type ->
                scope.launch { settings.setDetectorMode(type.key) }
                app.applyDetectorMode()
            },
        )
        if (current == DetectorType.RT_DETR_V2) {
            SettingsToggleRow(
                title = stringResource(R.string.settings_label_refine_gemma),
                description = stringResource(R.string.settings_desc_refine_gemma),
                checked = refine,
                onCheckedChange = { enabled ->
                    scope.launch {
                        settings.setRefineWithGemma(enabled)
                        app.applyDetectorMode()
                    }
                },
            )
        }
    }
}

@Composable
private fun ProviderFields(app: OceanGuardApp, scope: CoroutineScope) {
    val settings = app.settingsRepository
    val providerKey by settings.vlmProvider.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_VLM_PROVIDER,
    )
    val provider = VlmProvider.fromKey(providerKey)
    Column(verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        LabeledDropdown(
            title = stringResource(R.string.settings_label_vlm_provider),
            description = stringResource(R.string.models_adv_provider_desc),
            selectedText = provider.displayLabel,
            options = VlmProvider.entries.map { it to it.displayLabel },
            onSelect = { picked ->
                scope.launch {
                    settings.setVlmProvider(picked.key)
                    TextModelTier.forProvider(picked).firstOrNull()
                        ?.let { settings.setVlmModelTier(it.name.lowercase()) }
                }
            },
        )
        if (provider == VlmProvider.GEMMA4_GGUF) {
            Text(
                text = stringResource(R.string.models_adv_gguf_note),
                style = MaterialTheme.typography.bodySmall,
                color = SonarTheme.colors.echo,
            )
        }
        TierField(app, provider, scope)
        VariantField(app, provider, scope)
    }
}

@Composable
private fun TierField(app: OceanGuardApp, provider: VlmProvider, scope: CoroutineScope) {
    val settings = app.settingsRepository
    val tierKey by settings.vlmModelTier.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_VLM_MODEL_TIER,
    )
    val manager = app.vlmModelManager
    val missing = stringResource(R.string.settings_vlm_not_downloaded)
    fun label(tier: TextModelTier) =
        tierLabel(tier, manager.isAnyVariantAvailable(tier), missing)
    LabeledDropdown(
        title = stringResource(R.string.settings_label_vlm_tier),
        description = stringResource(R.string.settings_desc_vlm_tier),
        selectedText = label(TextModelTier.fromKey(tierKey)),
        options = TextModelTier.forProvider(provider).map { it to label(it) },
        onSelect = { scope.launch { settings.setVlmModelTier(it.name.lowercase()) } },
    )
}

@Composable
private fun VariantField(app: OceanGuardApp, provider: VlmProvider, scope: CoroutineScope) {
    val settings = app.settingsRepository
    val tierKey by settings.vlmModelTier.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_VLM_MODEL_TIER,
    )
    val variantKey by settings.vlmModelVariant.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_VLM_MODEL_VARIANT,
    )
    val installedVersion by settings.installedFinetunedVersion.collectAsStateWithLifecycle(initialValue = "")
    val tier = TextModelTier.fromKey(tierKey)
    if (provider != VlmProvider.GEMMA4 || !tier.supportsFinetuned ||
        !VlmModelManager.FINETUNED_LITERTLM_PUBLISHED
    ) {
        return
    }
    val manager = app.vlmModelManager
    val missing = stringResource(R.string.settings_vlm_not_downloaded)
    fun label(variant: TextModelVariant): String {
        val available = manager.isModelAvailable(tier, variant)
        return if (variant == TextModelVariant.FINETUNED) {
            variant.displayLabel + finetunedSuffix(available, installedVersion, manager.bestModelVersion(), missing)
        } else {
            variant.displayLabel + if (available) "" else " ($missing)"
        }
    }
    val current = TextModelVariant.fromKey(variantKey)
    Column(Modifier.fillMaxWidth()) {
        LabeledDropdown(
            title = stringResource(R.string.settings_label_vlm_variant),
            description = stringResource(R.string.settings_desc_vlm_variant),
            selectedText = label(current),
            options = TextModelVariant.entries.map { it to label(it) },
            onSelect = { scope.launch { settings.setVlmModelVariant(it.key) } },
        )
        Text(
            text = stringResource(R.string.vlm_variant_finetuned_metric),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Name - size", plus the missing note when no file of any variant is installed. */
internal fun tierLabel(tier: TextModelTier, available: Boolean, notDownloaded: String): String =
    "${tier.displayName} - ${tier.sizeLabel}" + if (available) "" else " ($notDownloaded)"

/**
 * What to append to the fine-tuned variant name: not downloaded, up to date or update available,
 * with the versions when the manifest and the install know them.
 */
internal fun finetunedSuffix(
    available: Boolean,
    installedVersion: String,
    bestVersion: String?,
    notDownloaded: String,
): String = when {
    !available && bestVersion != null -> " (v$bestVersion - $notDownloaded)"
    !available -> " ($notDownloaded)"
    bestVersion != null && installedVersion.isNotBlank() && installedVersion != bestVersion ->
        " (v$installedVersion -> v$bestVersion)"
    installedVersion.isNotBlank() -> " (v$installedVersion)"
    bestVersion != null -> " (v$bestVersion)"
    else -> ""
}
