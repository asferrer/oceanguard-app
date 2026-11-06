package com.oceanguard.ai.ui.components.dex

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.SpeciesSpriteImage
import com.oceanguard.ai.ui.components.sonar.springPress
import com.oceanguard.ai.ui.motion.OceanMotion
import com.oceanguard.ai.ui.motion.motionSpec
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Shared data model: lightweight transfer object for dex grid cells.
// Parametrizes both MarineDex and BioDex card/header composables.
// ---------------------------------------------------------------------------

/**
 * Lightweight UI model used by shared dex composables.
 *
 * @param key           Stable unique identifier (e.g. speciesKey or debris type name).
 * @param displayName   Localised common name resolved by the caller.
 * @param spritePath    Relative path under getExternalFilesDir for species sprites,
 *                      or null to fall back to the placeholder drawable.
 * @param discovered    Whether the user has seen this entry at least once.
 * @param count         Times observed/detected.
 * @param isFavorite    Whether the user has marked this entry as a favourite.
 * @param subtitle      Optional second line shown in the card (e.g. scientific name).
 */
data class DexItem(
    val key: String,
    val displayName: String,
    val spritePath: String?,
    val discovered: Boolean,
    val count: Int,
    val isFavorite: Boolean,
    val subtitle: String? = null,
)

/**
 * Draws the sprite of a [DexItem] in a card or a hero. [silhouette] is true for an entry that
 * has not been discovered: the slot must then paint a flat shape, never the real artwork.
 */
typealias DexSpriteSlot = @Composable (modifier: Modifier, size: Dp, silhouette: Boolean) -> Unit

/** Default slot: species artwork (or its neutral placeholder). */
fun speciesSpriteSlot(item: DexItem): DexSpriteSlot = { modifier, size, silhouette ->
    SpeciesSpriteImage(
        spritePath = item.spritePath,
        modifier = modifier,
        size = size,
        silhouette = silhouette,
        silhouetteColor = SonarTheme.colors.lineStrong,
    )
}

private val CardShape = RoundedCornerShape(16.dp)
private const val MAX_STAGGERED_CARDS = 8

// ---------------------------------------------------------------------------
// DexCollectionHeader: progress ring with the collection figures
// ---------------------------------------------------------------------------

/**
 * Full-width header with collection progress. [caption] is the line under the ring (for
 * example "3 / 11 discovered", or the completion message once [discoveredCount] == [totalCount]).
 */
@Composable
fun DexCollectionHeader(
    discoveredCount: Int,
    totalCount: Int,
    favoriteCount: Int,
    observationCount: Int,
    caption: String,
    discoveredLabel: String,
    favoritesLabel: String,
    observationsLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SonarTheme.colors.hull),
            border = BorderStroke(1.dp, SonarTheme.colors.line),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DexCircularProgress(discovered = discoveredCount, total = totalCount)
                Text(
                    text = caption,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
        DexStatsRow(
            discoveredCount = discoveredCount,
            favoriteCount = favoriteCount,
            observationCount = observationCount,
            discoveredLabel = discoveredLabel,
            favoritesLabel = favoritesLabel,
            observationsLabel = observationsLabel,
        )
    }
}

@Composable
private fun DexStatsRow(
    discoveredCount: Int,
    favoriteCount: Int,
    observationCount: Int,
    discoveredLabel: String,
    favoritesLabel: String,
    observationsLabel: String,
) {
    val colors = SonarTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = colors.hull),
        border = BorderStroke(1.dp, colors.line),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DexStat(label = discoveredLabel, value = discoveredCount.toString(), valueColor = colors.trace)
            VerticalDivider(modifier = Modifier.height(40.dp), color = colors.line)
            DexStat(label = favoritesLabel, value = favoriteCount.toString(), valueColor = colors.alert)
            VerticalDivider(modifier = Modifier.height(40.dp), color = colors.line)
            DexStat(label = observationsLabel, value = observationCount.toString(), valueColor = colors.echo)
        }
    }
}

// ---------------------------------------------------------------------------
// DexCircularProgress: animated arc showing the fraction discovered
// ---------------------------------------------------------------------------

@Composable
fun DexCircularProgress(discovered: Int, total: Int) {
    val colors = SonarTheme.colors
    val progress = if (total > 0) (discovered.toFloat() / total).coerceIn(0f, 1f) else 0f
    val sweepAngle by animateFloatAsState(
        targetValue = 360f * progress,
        animationSpec = motionSpec(OceanMotion.signature()),
        label = "dexProgress",
    )
    val arcColor = if (progress >= 1f) colors.echo else colors.trace
    val trackColor = colors.line

    Box(
        modifier = Modifier
            .size(96.dp)
            .drawBehind {
                val stroke = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                drawArc(trackColor, -90f, 360f, useCenter = false, style = stroke)
                drawArc(arcColor, -90f, sweepAngle, useCenter = false, style = stroke)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "$discovered", style = SonarTheme.type.dataLarge, color = colors.ink)
            Text(text = "/ $total", style = SonarTheme.type.dataSmall, color = colors.ink2)
        }
    }
}

// ---------------------------------------------------------------------------
// DexStat: single label+value column used in the stats row
// ---------------------------------------------------------------------------

@Composable
fun DexStat(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = SonarTheme.type.dataLarge, color = valueColor)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// DexCard: grid cell for a single DexItem (discovered or locked)
// ---------------------------------------------------------------------------

/**
 * A single collection card.
 *
 * A discovered entry shows its sprite (through [sprite]), name, optional subtitle, the count and
 * a heart when it is a favourite; tapping it calls [onNavigateToDetail]. An undiscovered entry
 * shows the silhouette of the sprite and a "?" in place of the name, and is not clickable.
 * [badge] is an optional marker in the top corner (the risk dot of MarineDex).
 */
@Composable
fun DexCard(
    item: DexItem,
    animationIndex: Int,
    onNavigateToDetail: (key: String) -> Unit,
    modifier: Modifier = Modifier,
    sprite: DexSpriteSlot = speciesSpriteSlot(item),
    badge: (@Composable () -> Unit)? = null,
) {
    val reduced = LocalReducedMotion.current
    var isVisible by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) {
        if (!reduced) delay(animationIndex.coerceAtMost(MAX_STAGGERED_CARDS) * OceanMotion.STAGGER_MS.toLong())
        isVisible = true
    }
    val appear by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = motionSpec(OceanMotion.popSpring()),
        label = "dexCardAppear",
    )
    val colors = SonarTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val description = if (item.discovered) {
        stringResource(R.string.dex_card_cd_discovered, item.displayName, item.count)
    } else {
        stringResource(R.string.dex_card_cd_locked, item.displayName)
    }
    val openLabel = stringResource(R.string.dex_card_open_label, item.displayName)

    Card(
        modifier = modifier
            .aspectRatio(0.75f)
            .graphicsLayer {
                val scale = 0.75f + 0.25f * appear
                scaleX = scale
                scaleY = scale
                alpha = appear.coerceIn(0f, 1f)
            }
            .then(
                if (item.discovered) {
                    Modifier
                        .springPress(interaction)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = openLabel,
                        ) { onNavigateToDetail(item.key) }
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = description },
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (item.discovered) colors.hull else colors.deep,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (item.discovered) colors.trace.copy(alpha = 0.7f) else colors.line,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            CardMarkers(item = item, badge = badge)
            sprite(Modifier, 48.dp, !item.discovered)
            if (item.discovered) DiscoveredCaption(item) else LockedCaption()
        }
    }
}

@Composable
private fun CardMarkers(item: DexItem, badge: (@Composable () -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.discovered && item.isFavorite) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = SonarTheme.colors.alert,
                modifier = Modifier.size(14.dp),
            )
        }
        if (item.discovered) badge?.invoke()
    }
}

@Composable
private fun DiscoveredCaption(item: DexItem) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = item.displayName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        item.subtitle?.let { sub ->
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Surface(shape = RoundedCornerShape(8.dp), color = SonarTheme.colors.traceContainer) {
            Text(
                text = "×${item.count}",
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = SonarTheme.type.dataSmall,
                color = SonarTheme.colors.onTraceContainer,
            )
        }
    }
}

@Composable
private fun LockedCaption() {
    Text(
        text = "?",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}
