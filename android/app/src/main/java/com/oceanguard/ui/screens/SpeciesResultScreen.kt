package com.oceanguard.ai.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesIdSource
import com.oceanguard.ai.data.species.SpeciesNames
import com.oceanguard.ai.inference.species.IdentificationResult
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.components.sonar.SonarErrorState
import com.oceanguard.ai.ui.screens.models.SpeciesPackPlacement
import com.oceanguard.ai.ui.screens.models.SpeciesPackPrompt
import com.oceanguard.ai.ui.components.InferenceAnimationOverlay
import com.oceanguard.ai.ui.components.NormBox
import com.oceanguard.ai.ui.components.SpeciesFitWidthBoxOverlay
import com.oceanguard.ai.ui.components.bboxToNormBox
import com.oceanguard.ai.ui.theme.SonarDarkColors
import com.oceanguard.ai.ui.theme.SonarTheme
import java.util.Locale

/**
 * SpeciesResultScreen — immersive result display for the BioDex single-capture flow.
 *
 * Mirrors [CameraResultScreen] visually and behaviourally:
 *  - Full-screen image backdrop on [MaterialTheme.colorScheme.background].
 *  - [InferenceAnimationOverlay] while the pipeline runs.
 *  - Bottom action bar with result summary + "New Scan" / "View BioDex" actions.
 *  - Transparent back button in the top-start corner.
 *  - Error overlay with Retry / Back actions.
 *
 * Navigation:
 *  - "New Scan" → back to the camera in species mode.
 *  - "View BioDex" → biodex (species dex gallery).
 */
@Composable
fun SpeciesResultScreen(
    navController: NavController,
    viewModel: SpeciesViewModel,
    catalog: SpeciesCatalog,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val capturedUri by viewModel.capturedImageUri.collectAsStateWithLifecycle()

    val isComplete = uiState is SpeciesUiState.Complete
    val isIdentifying = uiState is SpeciesUiState.Identifying

    val onNewScan: () -> Unit = {
        viewModel.resetState()
        navController.navigate("camera?mode=species") {
            popUpTo("home") { inclusive = false }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Full-screen image backdrop. Once identification completes we swap the
        // plain photo for the same photo with the detected organism box(es)
        // overlaid; while identifying/error we keep the uncropped backdrop.
        val completeState = uiState as? SpeciesUiState.Complete
        if (completeState != null && capturedUri != null) {
            val unidentifiedLabel = stringResource(R.string.species_unidentified)
            val boxes = remember(completeState, catalog, unidentifiedLabel) {
                speciesNormBoxes(completeState, catalog, unidentifiedLabel)
            }
            SpeciesFitWidthBoxOverlay(
                imageUri = capturedUri.toString(),
                boxes = boxes,
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentHeight(Alignment.CenterVertically),
            )
        } else if (capturedUri != null) {
            AsyncImage(
                model = capturedUri,
                contentDescription = stringResource(R.string.session_detail_cd_annotated_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Inference animation while running
        AnimatedVisibility(
            visible = isIdentifying,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            InferenceAnimationOverlay(
                statusText = stringResource(R.string.species_identifying),
                subText = null,
                accentColor = SonarTheme.colors.trace,
            )
        }

        // Top back button (transparent circle)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            IconButton(
                onClick = {
                    viewModel.resetState()
                    navController.popBackStack()
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.biodex_cd_go_back),
                    tint = SonarDarkColors.ink,
                )
            }
        }

        // Bottom action bar — shown while identifying (spinner chip) or complete (summary + buttons)
        AnimatedVisibility(
            visible = isIdentifying || isComplete,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SpeciesBottomActionBar(
                uiState = uiState,
                catalog = catalog,
                onNewScan = onNewScan,
                onViewBioDex = {
                    viewModel.resetState()
                    navController.navigate("biodex") {
                        popUpTo("home") { inclusive = false }
                    }
                },
            )
        }

        // Error overlay
        val errorState = uiState as? SpeciesUiState.Error
        if (errorState != null) {
            SpeciesErrorCard(
                message = errorState.message,
                packMissing = errorState.packMissing,
                onRetry = {
                    capturedUri?.let { viewModel.identifyFromImage(it) }
                },
                onBack = {
                    viewModel.resetState()
                    navController.popBackStack()
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Result → overlay box mapping
// ---------------------------------------------------------------------------

/**
 * Build the list of normalised organism boxes for the overlay.
 *
 * For every result that carries a pixel bbox, resolves the localised species
 * name (common name → English → scientific) and normalises the bbox against the
 * analysed image's intrinsic dimensions via [bboxToNormBox]. Results with a
 * null bbox (whole-frame / centre-crop fallback) or unknown image size (0)
 * are skipped, so the photo simply renders without a box — never a crash.
 */
internal fun speciesNormBoxes(
    state: SpeciesUiState.Complete,
    catalog: SpeciesCatalog,
    unidentifiedLabel: String,
): List<NormBox> {
    if (state.imageWidth <= 0 || state.imageHeight <= 0) return emptyList()
    val lang = SpeciesNames.currentLanguage()
    return state.results.mapNotNull { result ->
        val bbox = result.bbox ?: return@mapNotNull null
        val label = SpeciesNames.resultName(
            result.speciesKey, result.scientificName, result.isUnidentified,
            catalog, unidentifiedLabel, lang,
        )
        bboxToNormBox(
            x = bbox.x, y = bbox.y, width = bbox.width, height = bbox.height,
            imgWidth = state.imageWidth, imgHeight = state.imageHeight,
            label = label, confidence = result.confidence,
        )
    }
}

// ---------------------------------------------------------------------------
// Bottom action bar
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesBottomActionBar(
    uiState: SpeciesUiState,
    catalog: SpeciesCatalog,
    onNewScan: () -> Unit,
    onViewBioDex: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.82f))
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (uiState) {
            is SpeciesUiState.Identifying -> IdentifyingChip()
            is SpeciesUiState.Complete    -> {
                SpeciesResultSummary(results = uiState.results, catalog = catalog)
                Spacer(modifier = Modifier.height(10.dp))
                SpeciesActionButtons(onNewScan = onNewScan, onViewBioDex = onViewBioDex)
            }
            else -> {}
        }
    }
}

@Composable
private fun IdentifyingChip() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = SonarTheme.colors.traceContainer,
    ) {
        Text(
            text = stringResource(R.string.species_identifying),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = SonarTheme.colors.onTraceContainer,
        )
    }
}

@Composable
private fun SpeciesResultSummary(results: List<IdentificationResult>, catalog: SpeciesCatalog) {
    if (results.isEmpty()) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SonarTheme.colors.traceContainer,
        ) {
            Text(
                text = stringResource(R.string.biodex_result_none),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = SonarTheme.colors.onTraceContainer,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            items(results, key = { "${it.speciesKey}_${it.cosineScore}" }) { result ->
                CompactSpeciesResultCard(result = result, catalog = catalog)
            }
        }
    }
}

@Composable
private fun CompactSpeciesResultCard(result: IdentificationResult, catalog: SpeciesCatalog) {
    val lang = Locale.getDefault().language
    val commonName = SpeciesNames.resultName(
        result.speciesKey, result.scientificName, result.isUnidentified,
        catalog, stringResource(R.string.species_unidentified), lang,
    )
    // Unidentified: show the closest catalogue candidates as hints, not as an answer.
    val subtitle = if (result.isUnidentified) {
        stringResource(R.string.species_unidentified_hint, result.candidates.take(3).joinToString { it.scientificName })
    } else result.scientificName
    val confidencePct = (result.confidence * 100).toInt()

    SonarCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = commonName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = if (result.isUnidentified) FontStyle.Normal else FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.biodex_result_confidence, confidencePct),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                IdSourceBadgeRow(result = result)
            }
        }
    }
}

@Composable
private fun IdSourceBadgeRow(result: IdentificationResult) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val sourceLabel = when (result.idSource) {
            SpeciesIdSource.RAG_CORE      -> stringResource(R.string.species_badge_confident)
            SpeciesIdSource.RAG_TENTATIVE ->
                if (result.vlmChosen) stringResource(R.string.species_badge_gemma) else stringResource(R.string.species_badge_possible)
            SpeciesIdSource.VLM_OPENVOCAB -> "Open Vocab"
            SpeciesIdSource.UNIDENTIFIED  -> stringResource(R.string.species_badge_unidentified)
        }
        val containerColor = when (result.idSource) {
            SpeciesIdSource.RAG_CORE      -> MaterialTheme.colorScheme.primaryContainer
            SpeciesIdSource.RAG_TENTATIVE -> MaterialTheme.colorScheme.secondaryContainer
            SpeciesIdSource.VLM_OPENVOCAB -> MaterialTheme.colorScheme.tertiaryContainer
            SpeciesIdSource.UNIDENTIFIED  -> MaterialTheme.colorScheme.surfaceVariant
        }
        val labelColor = when (result.idSource) {
            SpeciesIdSource.RAG_CORE      -> MaterialTheme.colorScheme.onPrimaryContainer
            SpeciesIdSource.RAG_TENTATIVE -> MaterialTheme.colorScheme.onSecondaryContainer
            SpeciesIdSource.VLM_OPENVOCAB -> MaterialTheme.colorScheme.onTertiaryContainer
            SpeciesIdSource.UNIDENTIFIED  -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        AssistChip(
            onClick = {},
            label = { Text(text = sourceLabel, style = MaterialTheme.typography.labelSmall) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = containerColor,
                labelColor = labelColor,
            ),
            shape = RoundedCornerShape(20.dp),
        )
        if (result.outOfRange) {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = stringResource(R.string.biodex_badge_out_of_range),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                shape = RoundedCornerShape(20.dp),
            )
        }
    }
}

@Composable
private fun SpeciesActionButtons(onNewScan: () -> Unit, onViewBioDex: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SonarButton(
            text = stringResource(R.string.species_result_btn_new_scan),
            onClick = onNewScan,
            modifier = Modifier.weight(1f),
            style = SonarButtonStyle.Secondary,
            icon = Icons.Filled.CameraAlt,
        )
        SonarButton(
            text = stringResource(R.string.species_result_btn_view_biodex),
            onClick = onViewBioDex,
            modifier = Modifier.weight(1f),
            icon = Icons.AutoMirrored.Filled.OpenInNew,
        )
    }
}

// ---------------------------------------------------------------------------
// Error overlay card
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesErrorCard(
    message: String,
    packMissing: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = SonarTheme.colors.hull,
        border = BorderStroke(1.dp, SonarTheme.colors.line),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SonarErrorState(
                title = stringResource(R.string.results_error_title),
                message = message,
                onRetry = onRetry,
            )
            if (packMissing) {
                // Download here; when it finishes the photo is identified again by itself.
                Spacer(modifier = Modifier.height(8.dp))
                SpeciesPackPrompt(placement = SpeciesPackPlacement.INLINE, onInstalled = onRetry)
            }
            Spacer(modifier = Modifier.height(8.dp))
            SonarButton(
                text = stringResource(R.string.common_back),
                onClick = onBack,
                style = SonarButtonStyle.Quiet,
            )
        }
    }
}
