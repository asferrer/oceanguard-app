package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.ui.res.stringResource
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.colorForClass
import com.oceanguard.ai.ui.components.dex.debrisDisplayName
import com.oceanguard.ai.ui.components.sonar.SonarEmptyState
import com.oceanguard.ai.ui.screens.map.MapStyles
import com.oceanguard.ai.ui.theme.LocalIsDarkTheme
import com.oceanguard.ai.ui.theme.SonarTheme
import com.oceanguard.ai.utils.toGeoJsonFeatureCollection
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position

/**
 * Embedded MapLibre map showing pin locations for all sessions containing
 * the given [debrisType].
 *
 * Sessions without GPS coordinates are silently skipped. If none of the
 * sessions have location data, an empty-state placeholder is shown instead.
 */
@Composable
fun MarineDexTypeMap(
    debrisType: DebrisType,
    sessions: List<DetectionSession>,
    onSessionClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locatedSessions = remember(sessions) { sessions.filter { it.location != null } }

    if (locatedSessions.isEmpty()) {
        TypeMapEmptyState(
            debrisType = debrisType,
            hasSessions = sessions.isNotEmpty(),
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    // Read the app's effective dark-mode preference (driven by Settings),
    // not the system theme — keeps the embedded map consistent with the
    // main MapScreen and with the rest of the app under the user's override.
    val isDark = LocalIsDarkTheme.current
    val styleUrl = remember(isDark) { MapStyles.forDarkMode(isDark) }
    val geoJson = remember(locatedSessions) { locatedSessions.toGeoJsonFeatureCollection() }
    val centerPosition = remember(locatedSessions) { computeCenter(locatedSessions) }
    val cameraState = rememberCameraState(firstPosition = centerPosition)

    Box(modifier = modifier) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            cameraState = cameraState,
            baseStyle = BaseStyle.Uri(styleUrl),
            options = MapOptions(
                ornamentOptions = OrnamentOptions(
                    isLogoEnabled = false,
                    isCompassEnabled = false,
                    isScaleBarEnabled = false,
                    isAttributionEnabled = false,
                ),
            ),
        ) {
            val stableData = remember(geoJson) { GeoJsonData.JsonString(geoJson) }
            val stableOptions = remember { GeoJsonOptions(cluster = false) }
            val source = rememberGeoJsonSource(data = stableData, options = stableOptions)

            DebrisTypeCircleLayer(
                source = source,
                pinColor = colorForClass(debrisType.name),
                outlineColor = SonarTheme.colors.abyss,
                onSessionClick = onSessionClick,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Circle layer — simple colored pins, no clustering needed for per-type view
// ---------------------------------------------------------------------------

@Suppress("UNCHECKED_CAST")
@Composable
private fun DebrisTypeCircleLayer(
    source: org.maplibre.compose.sources.GeoJsonSource,
    pinColor: androidx.compose.ui.graphics.Color,
    outlineColor: androidx.compose.ui.graphics.Color,
    onSessionClick: (Long) -> Unit,
) {
    CircleLayer(
        id = "debris-type-points",
        source = source,
        color = const(pinColor),
        radius = interpolate(
            type = linear(),
            input = zoom(),
            3 to const(6.dp),
            10 to const(10.dp),
            14 to const(14.dp),
        ),
        strokeWidth = const(2.dp),
        strokeColor = const(outlineColor),
        onClick = { features ->
            val sessionId = features.firstOrNull()
                ?.properties
                ?.get("sessionId")
                ?.jsonPrimitive
                ?.longOrNull
            if (sessionId != null) {
                onSessionClick(sessionId)
                ClickResult.Consume
            } else {
                ClickResult.Pass
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Camera helpers
// ---------------------------------------------------------------------------

private fun computeCenter(sessions: List<DetectionSession>): CameraPosition {
    val lats = sessions.mapNotNull { it.location?.latitude }
    val lngs = sessions.mapNotNull { it.location?.longitude }
    val centerLat = lats.average()
    val centerLng = lngs.average()
    val zoom = if (sessions.size == 1) {
        14.0
    } else {
        val span = maxOf(lats.max() - lats.min(), lngs.max() - lngs.min())
        when {
            span < 0.01 -> 14.0
            span < 0.1 -> 11.0
            span < 1.0 -> 8.0
            else -> 5.0
        }
    }
    return CameraPosition(target = Position(longitude = centerLng, latitude = centerLat), zoom = zoom)
}

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------

@Composable
private fun TypeMapEmptyState(
    debrisType: DebrisType,
    hasSessions: Boolean,
    modifier: Modifier = Modifier,
) {
    val displayName = debrisDisplayName(debrisType)
    val message = if (hasSessions) {
        stringResource(R.string.dex_map_empty_no_location, displayName)
    } else {
        stringResource(R.string.dex_map_empty_no_captures, displayName)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        SonarEmptyState(
            icon = Icons.Filled.LocationOff,
            title = stringResource(R.string.dex_map_empty_title),
            message = message,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}
