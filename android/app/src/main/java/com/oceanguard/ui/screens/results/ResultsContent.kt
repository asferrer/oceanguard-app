package com.oceanguard.ai.ui.screens.results

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.AnalysisModels
import com.oceanguard.ai.data.ZoneAggregator
import com.oceanguard.ai.inference.AnalysisResult
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.components.sonar.SonarSection
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget

/** Everything a finished single analysis shows, in the order it is read. */
@Composable
internal fun FullResultContent(
    imageUri: Uri?,
    annotatedThumbnailUri: String?,
    result: AnalysisResult,
    sessionSaved: Boolean,
    boundsMap: MutableMap<String, Rect>,
    onSave: () -> Unit,
    onNewScan: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val debris = result.vlmAnalysis.debrisList
    val repeated = remember(debris) { findRepeatedObjects(debris.map { it.type.name }) }
    val showConfidence = AnalysisModels.hasModelConfidence(result.modelName)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Box(Modifier.spotlightTarget("results_image", boundsMap)) {
                ResultPhoto(
                    imageUri = imageUri,
                    annotatedUri = annotatedThumbnailUri,
                    detections = result.rtdetrDetections,
                    showConfidence = showConfidence,
                    showActions = true,
                )
            }
        }
        item { Box(Modifier.spotlightTarget("results_health", boundsMap)) { ResultHealth(result) } }
        item { AnalysisInfoRow(result.modelName, result.processingTimeMs) }
        item { ResultTiles(result) }
        repeated?.let { notice -> item { RepeatedObjectsNotice(notice) } }
        findingsSections(debris = debris, modelName = result.modelName)
        item {
            Box(Modifier.spotlightTarget("results_actions", boundsMap)) {
                ResultActions(sessionSaved = sessionSaved, onSave = onSave, onNewScan = onNewScan)
            }
        }
    }
}

@Composable
private fun ResultHealth(result: AnalysisResult) {
    if (result.totalDebrisCount > 0) {
        HealthCard(result.healthScore, ZoneAggregator.computeFactors(result.vlmAnalysis.debrisList))
    } else {
        NoDetectionsCard()
    }
}

@Composable
private fun ResultTiles(result: AnalysisResult) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(
            label = stringResource(R.string.results_stat_total_debris),
            value = result.totalDebrisCount.toString(),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(R.string.results_stat_deep_analysis),
            value = stringResource(if (result.hasVLMAnalysis) R.string.results_stat_yes else R.string.results_stat_no),
            modifier = Modifier.weight(1f),
        )
    }
}

/** Material chips and the list of findings; shared by the fresh result and the saved detail. */
internal fun LazyListScope.findingsSections(
    debris: List<com.oceanguard.ai.data.Debris>,
    modelName: String?,
) {
    if (debris.isEmpty()) return
    val materials = debris.groupBy { it.material }.mapValues { it.value.size }
    item {
        SonarSection(stringResource(R.string.results_label_material_breakdown)) {
            MaterialChipsRow(materials)
        }
    }
    item {
        Text(
            text = stringResource(R.string.results_label_detected_objects),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    items(debris) { FindingCard(it, modelName) }
}

@Composable
private fun ResultActions(sessionSaved: Boolean, onSave: () -> Unit, onNewScan: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (sessionSaved) {
            SavedBadge()
        } else {
            SonarButton(
                text = stringResource(R.string.results_btn_save_history),
                onClick = onSave,
                icon = Icons.Filled.Save,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SonarButton(
            text = stringResource(R.string.results_btn_new_scan),
            onClick = onNewScan,
            style = SonarButtonStyle.Secondary,
            icon = Icons.Filled.Refresh,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SavedBadge() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = stringResource(R.string.results_session_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
