package com.oceanguard.ai.ui.screens.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.ui.theme.HealthCritical
import com.oceanguard.ai.ui.theme.HealthExcellent
import com.oceanguard.ai.ui.theme.HealthFair
import com.oceanguard.ai.ui.theme.HealthGood
import com.oceanguard.ai.ui.theme.HealthPoor
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.step
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.NumberValue
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.sources.GeoJsonSource

/**
 * Density layers of the heat view.
 *
 * Each GeoJSON feature is a **zone** (1 km grouping), not an individual session, pre-aggregated
 * by [com.oceanguard.ai.data.ZoneAggregator]. Large translucent circles stand in for a heatmap,
 * coloured by the zone's health score. Zones and photos are not drawn here but by
 * [MapMarkersOverlay], so they can fly open and show the photo.
 *
 * Visibility is toggled via opacity to avoid SourceReferenceEffect lifecycle
 * crashes in maplibre-compose 0.12.1.
 */
@Suppress("UNCHECKED_CAST")
@Composable
fun ZoneMapLayers(
    source: GeoJsonSource,
    showHeatmap: Boolean,
) {
    val densityOpacity = if (showHeatmap) 1f else 0f

    // ── Density mode (circle-based heatmap substitute) ─────────────────

    // Outer glow ring — large, very translucent
    CircleLayer(
        id = "density-glow",
        source = source,
        color = healthScoreStepExpression(),
        radius = interpolate(
            type = linear(),
            input = zoom(),
            3 to const(20.dp),
            10 to const(35.dp),
            14 to const(50.dp),
        ),
        opacity = const(0.12f * densityOpacity),
        blur = const(1f),
    )

    // Inner density dot
    CircleLayer(
        id = "density-core",
        source = source,
        color = healthScoreStepExpression(),
        radius = interpolate(
            type = linear(),
            input = zoom(),
            3 to const(8.dp),
            10 to const(14.dp),
            14 to const(22.dp),
        ),
        opacity = const(0.35f * densityOpacity),
    )
}

/**
 * Step expression mapping healthScore property to the app's health color palette.
 */
@Suppress("UNCHECKED_CAST")
@Composable
private fun healthScoreStepExpression(): Expression<ColorValue> = step(
    input = feature.get("healthScore") as Expression<NumberValue<Number>>,
    fallback = const(HealthCritical),
    20 to const(HealthPoor),
    40 to const(HealthFair),
    60 to const(HealthGood),
    80 to const(HealthExcellent),
)
