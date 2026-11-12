package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.species.SpeciesObservation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import com.oceanguard.ai.ui.components.sonar.SonarEmptyState
import com.oceanguard.ai.ui.screens.map.MapStyles
import com.oceanguard.ai.ui.screens.map.globe.GlobeDot
import com.oceanguard.ai.ui.screens.map.globe.GlobeMapStage
import com.oceanguard.ai.ui.theme.LocalIsDarkTheme
import com.oceanguard.ai.ui.theme.SonarTheme
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position

/**
 * Embedded MapLibre map for the BioDex species Map tab.
 *
 * Mirrors [MarineDexTypeMap] exactly: same tile style (OpenFreeMap, dark/light
 * driven by [LocalIsDarkTheme]), same circle layer styling, same camera centering
 * heuristic.  Observations without GPS are silently skipped; if none carry a
 * location the appropriate empty-state is shown instead.
 *
 * @param observations All observations for the currently displayed species.
 * @param onObservationClick Invoked with the observation id when a map pin is tapped.
 * @param withGlobe Opens on the 3D globe and crosses into the flat map. Off where the map sits
 * inside a scrolling page, such as a report, and a drag has to scroll it.
 */
@Composable
fun SpeciesObservationMap(
    observations: List<SpeciesObservation>,
    onObservationClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    withGlobe: Boolean = true,
) {
    val located = remember(observations) { observations.filter { it.location != null } }

    if (located.isEmpty()) {
        SpeciesMapEmptyState(
            hasObservations = observations.isNotEmpty(),
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val isDark = LocalIsDarkTheme.current
    val styleUrl = remember(isDark) { MapStyles.forDarkMode(isDark) }
    val geoJson = remember(located) { located.toObservationGeoJson() }
    val centerPosition = remember(located) { computeObservationCenter(located) }
    val pinColor = SonarTheme.colors.echo
    val flat: @Composable (CameraState) -> Unit = { camera ->
        SpeciesFlatMap(camera, styleUrl, geoJson, pinColor, onObservationClick)
    }
    if (withGlobe) {
        val description = stringResource(R.string.map_globe_point_cd)
        val dots = remember(located, pinColor, description) { located.toGlobeDots(pinColor, description) }
        GlobeMapStage(dots = dots, frame = centerPosition, modifier = modifier, flatMap = flat)
    } else {
        val cameraState = rememberCameraState(firstPosition = centerPosition)
        Box(modifier = modifier) { flat(cameraState) }
    }
}

private fun List<SpeciesObservation>.toGlobeDots(color: Color, description: String): List<GlobeDot> =
    mapNotNull { obs ->
        val loc = obs.location ?: return@mapNotNull null
        GlobeDot(loc.latitude, loc.longitude, color, description, diameterDp = POINT_DOT_DP)
    }

private const val POINT_DOT_DP = 16

/** The flat map with one pin per observation. */
@Composable
private fun SpeciesFlatMap(
    cameraState: CameraState,
    styleUrl: String,
    geoJson: String,
    pinColor: Color,
    onObservationClick: (Long) -> Unit,
) {
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

        SpeciesObservationCircleLayer(
            source = source,
            pinColor = pinColor,
            outlineColor = SonarTheme.colors.abyss,
            onObservationClick = onObservationClick,
        )
    }
}

// ---------------------------------------------------------------------------
// Circle layer
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesObservationCircleLayer(
    source: org.maplibre.compose.sources.GeoJsonSource,
    pinColor: androidx.compose.ui.graphics.Color,
    outlineColor: androidx.compose.ui.graphics.Color,
    onObservationClick: (Long) -> Unit,
) {
    CircleLayer(
        id = "species-obs-points",
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
            val obsId = features.firstOrNull()
                ?.properties
                ?.get("observationId")
                ?.let {
                    it.toString().toLongOrNull()
                        ?: it.toString().trim('"').toLongOrNull()
                }
            if (obsId != null) {
                onObservationClick(obsId)
                ClickResult.Consume
            } else {
                ClickResult.Pass
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Camera center heuristic (mirrors computeCenter in MarineDexTypeMap)
// ---------------------------------------------------------------------------

private fun computeObservationCenter(observations: List<SpeciesObservation>): CameraPosition {
    val lats = observations.mapNotNull { it.location?.latitude }
    val lngs = observations.mapNotNull { it.location?.longitude }
    val centerLat = lats.average()
    val centerLng = lngs.average()
    val zoom = if (observations.size == 1) {
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
// GeoJSON serialiser for SpeciesObservation
// ---------------------------------------------------------------------------

private fun List<SpeciesObservation>.toObservationGeoJson(): String {
    val features = JSONArray()
    for (obs in this) {
        val loc = obs.location ?: continue
        val geometry = JSONObject().apply {
            put("type", "Point")
            put("coordinates", JSONArray().apply {
                put(loc.longitude)
                put(loc.latitude)
            })
        }
        val properties = JSONObject().apply {
            put("observationId", obs.id)
            put("confidence", obs.confidence)
            put("outOfRange", obs.outOfRange)
        }
        features.put(
            JSONObject().apply {
                put("type", "Feature")
                put("geometry", geometry)
                put("properties", properties)
            }
        )
    }
    return JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", features)
    }.toString()
}

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------

@Composable
private fun SpeciesMapEmptyState(hasObservations: Boolean, modifier: Modifier = Modifier) {
    val message = if (hasObservations) {
        stringResource(R.string.biodex_map_empty_no_location)
    } else {
        stringResource(R.string.biodex_map_empty_no_obs)
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        SonarEmptyState(
            icon = Icons.Filled.LocationOff,
            title = stringResource(R.string.biodex_detail_tab_map),
            message = message,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}
