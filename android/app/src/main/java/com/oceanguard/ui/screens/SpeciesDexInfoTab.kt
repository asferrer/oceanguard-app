package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.data.species.enrichment.SpeciesEnrichment
import com.oceanguard.ai.ui.components.dex.DexItem
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.theme.SonarTheme

/** How worrying an IUCN status is. The label always carries the code; colour only reinforces it. */
internal enum class IucnTone { THREATENED, WATCH, SAFE, UNKNOWN }

internal fun iucnTone(status: String): IucnTone = when (status.uppercase()) {
    "EX", "EW", "CR", "EN" -> IucnTone.THREATENED
    "VU", "NT" -> IucnTone.WATCH
    "LC" -> IucnTone.SAFE
    else -> IucnTone.UNKNOWN
}

/** Info tab of a species: names, description, observation count and optional online data. */
@Composable
internal fun SpeciesInfoTabContent(
    speciesKey: String,
    displayName: String,
    catalogEntry: SpeciesCatalogEntry?,
    dexEntry: DexItem?,
    lang: String,
    enrichmentProvider: (suspend (aphiaId: Long?, scientificName: String) -> SpeciesEnrichment?)? = null,
) {
    val description = catalogEntry?.descriptions?.get(lang) ?: catalogEntry?.descriptions?.get("en")
    val scientificName = catalogEntry?.scientificName ?: speciesKey

    // null = not fetched yet | SpeciesEnrichment = data | Unit = fetched, nothing to show
    val enrichmentResult by produceState<Any?>(
        initialValue = null,
        key1 = speciesKey,
        key2 = enrichmentProvider,
    ) {
        if (enrichmentProvider == null) {
            value = Unit
            return@produceState
        }
        value = enrichmentProvider.invoke(null, scientificName) ?: Unit
    }
    val enrichment = enrichmentResult as? SpeciesEnrichment
    val enrichmentLoaded = enrichmentProvider == null || enrichmentResult != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SpeciesTitleSection(
            displayName = displayName,
            scientificName = catalogEntry?.scientificName,
            iucnStatus = catalogEntry?.iucnStatus,
            cosmopolitan = catalogEntry?.cosmopolitan ?: false,
        )
        description?.let { desc ->
            SonarCard {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (dexEntry != null) {
            SpeciesStatCard(
                label = stringResource(R.string.biodex_detail_stat_observations),
                value = dexEntry.count.toString(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            !enrichmentLoaded -> Unit // still fetching: show nothing, avoids a hint flash
            enrichment != null -> OnlineInfoCard(enrichment = enrichment)
            else -> OnlineInfoDisabledHint()
        }
    }
}

// ---------------------------------------------------------------------------
// Online enrichment
// ---------------------------------------------------------------------------

@Composable
private fun OnlineInfoCard(enrichment: SpeciesEnrichment) {
    SonarCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.biodex_enrichment_section_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = SonarTheme.colors.trace,
            )
            enrichment.acceptedName?.let { name ->
                val label = buildString {
                    append(name)
                    enrichment.authority?.let { append(" $it") }
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            enrichment.rank?.let { rank ->
                EnrichmentRow(stringResource(R.string.biodex_enrichment_label_status), rank)
            }
            enrichment.iucnStatus?.let { status ->
                EnrichmentRow(stringResource(R.string.biodex_enrichment_label_status), status)
            }
            enrichment.distributionSummary?.let { EnrichmentDistribution(summary = it) }
            enrichment.representativePhotoUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = enrichment.acceptedName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
            }
            if (enrichment.sources.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.biodex_enrichment_source_prefix, enrichment.sources.joinToString(", ")),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EnrichmentDistribution(summary: String) {
    Text(
        text = stringResource(R.string.biodex_enrichment_label_distribution),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun EnrichmentRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun OnlineInfoDisabledHint() {
    Text(
        text = stringResource(R.string.biodex_enrichment_disabled_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

// ---------------------------------------------------------------------------
// Title and badges
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesTitleSection(
    displayName: String,
    scientificName: String?,
    iucnStatus: String?,
    cosmopolitan: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        scientificName?.let { sci ->
            Text(
                text = sci,
                style = MaterialTheme.typography.titleSmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            iucnStatus?.let { IucnStatusBadge(status = it) }
            if (cosmopolitan) CosmopolitanBadge()
        }
    }
}

@Composable
private fun IucnStatusBadge(status: String) {
    val colors = SonarTheme.colors
    val (container, content) = when (iucnTone(status)) {
        IucnTone.THREATENED -> colors.alertContainer to colors.onAlertContainer
        IucnTone.WATCH -> colors.echoContainer to colors.onEchoContainer
        IucnTone.SAFE -> colors.traceContainer to colors.onTraceContainer
        IucnTone.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Badge(text = stringResource(R.string.biodex_iucn_badge, status), container = container, content = content)
}

@Composable
private fun CosmopolitanBadge() {
    Badge(
        text = stringResource(R.string.biodex_badge_cosmopolitan),
        container = SonarTheme.colors.traceContainer,
        content = SonarTheme.colors.onTraceContainer,
    )
}

@Composable
private fun Badge(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(20.dp), color = container) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SpeciesStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    SonarCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                style = SonarTheme.type.data,
                color = SonarTheme.colors.trace,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
