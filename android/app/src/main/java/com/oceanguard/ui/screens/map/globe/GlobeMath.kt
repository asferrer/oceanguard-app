package com.oceanguard.ai.ui.screens.map.globe

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A point in 3D space; for a unit sphere, x is east, y is north and z points at the viewer. */
data class Vec3(val x: Double, val y: Double, val z: Double)

/** What the globe shows: the point facing the viewer and how close the camera is. */
data class GlobeCamera(val latitude: Double, val longitude: Double, val zoom: Double)

/** A screen position, in the same unit as the viewport it was projected into. */
data class ScreenPoint(val x: Float, val y: Float)

/**
 * Geometry of the globe, free of Android types so it can be tested on the JVM.
 *
 * The globe is a unit sphere seen by a pinhole camera on the +z axis at distance
 * [distanceForZoom], looking at the origin. [GlobeCamera.zoom] is the zoom the flat map would need
 * to show the same scale at the centre of the view on the equator; [mapZoom] corrects it for
 * latitude, which makes the two views line up.
 */
object GlobeMath {
    const val MAX_LATITUDE = 85.0
    const val FOV_DEGREES = 30.0

    /** Camera distance, in globe radii, when fully zoomed out. */
    const val MAX_DISTANCE = 4.6

    /** MapLibre world width at zoom 0, in dp. */
    const val TILE_SIZE = 512.0

    /** Mercator stretches the poles, so the map-equivalent scale stops following the cosine here. */
    private const val MERCATOR_LATITUDE_CAP = 70.0
    private const val MIN_DRAG_COS = 0.25
    private const val LIMB_FADE = 0.12
    private const val MAX_ZOOM_OVERSHOOT = 0.3
    private const val FLING_FRICTION = 3.2
    private val LN2 = ln(2.0)

    /** Focal length in the unit of [minDimension], the shorter side of the viewport. */
    fun focal(minDimension: Double): Double = minDimension / (2.0 * tan(Math.toRadians(FOV_DEGREES / 2.0)))

    /** Camera distance, in globe radii, that gives [zoom] on a viewport whose shorter side is [minDimensionDp]. */
    fun distanceForZoom(zoom: Double, minDimensionDp: Double): Double =
        1.0 + 2.0 * PI * focal(minDimensionDp) / (TILE_SIZE * 2.0.pow(zoom))

    /** Inverse of [distanceForZoom]. */
    fun zoomForDistance(distance: Double, minDimensionDp: Double): Double =
        ln(2.0 * PI * focal(minDimensionDp) / (TILE_SIZE * (distance - 1.0))) / LN2

    /** Smallest zoom, the whole globe in view. */
    fun minZoom(minDimensionDp: Double): Double = zoomForDistance(MAX_DISTANCE, minDimensionDp)

    /** Largest zoom: a little past the point where the flat map takes over, at [latitude]. */
    fun maxZoom(latitude: Double): Double =
        globeZoomForMap(GlobeTransition.ENTER_MAP_ZOOM + MAX_ZOOM_OVERSHOOT, latitude)

    /** Zoom the flat map needs for the same ground scale as [globeZoom] at [latitude]. */
    fun mapZoomForGlobe(globeZoom: Double, latitude: Double): Double = globeZoom + ln(latitudeCosine(latitude)) / LN2

    /** Inverse of [mapZoomForGlobe]. */
    fun globeZoomForMap(mapZoom: Double, latitude: Double): Double = mapZoom - ln(latitudeCosine(latitude)) / LN2

    /** Zoom of the flat map that matches [view]. */
    fun mapZoom(view: GlobeCamera): Double = mapZoomForGlobe(view.zoom, view.latitude)

    private fun latitudeCosine(latitude: Double): Double =
        cos(Math.toRadians(min(abs(latitude), MERCATOR_LATITUDE_CAP)))

    /** Keeps the poles from flipping and the zoom inside what the camera can do. */
    fun clamp(view: GlobeCamera, minDimensionDp: Double): GlobeCamera {
        val lat = view.latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val low = minZoom(minDimensionDp)
        return GlobeCamera(lat, wrapLongitude(view.longitude), view.zoom.coerceIn(low, max(low, maxZoom(lat))))
    }

    /** Longitude folded into [-180, 180). */
    fun wrapLongitude(longitude: Double): Double {
        val wrapped = (longitude + 180.0) % 360.0
        return (if (wrapped < 0) wrapped + 360.0 else wrapped) - 180.0
    }

    /** Signed longitude difference from [from] to [to] going the short way round, in [-180, 180). */
    fun shortestLongitudeDelta(from: Double, to: Double): Double = wrapLongitude(to - from)

    /** Point on the unit sphere, before any rotation: x east at longitude 90, y north, z at longitude 0. */
    fun toVector(latitude: Double, longitude: Double): Vec3 {
        val lat = Math.toRadians(latitude)
        val lon = Math.toRadians(longitude)
        return Vec3(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon))
    }

    /** Latitude and longitude, in degrees, of a point of the unit sphere. */
    fun toLatLon(v: Vec3): Pair<Double, Double> =
        Math.toDegrees(asin(v.y.coerceIn(-1.0, 1.0))) to Math.toDegrees(atan2(v.x, v.z))

    /** Where a point lands once the globe is turned so that ([centreLat], [centreLon]) faces the viewer. */
    fun rotateToView(latitude: Double, longitude: Double, centreLat: Double, centreLon: Double): Vec3 {
        val p = toVector(latitude, longitude - centreLon)
        val tilt = Math.toRadians(centreLat)
        return Vec3(p.x, p.y * cos(tilt) - p.z * sin(tilt), p.y * sin(tilt) + p.z * cos(tilt))
    }

    /** True when a rotated point is on the side of the globe the camera at [distance] can see. */
    fun isVisible(rotated: Vec3, distance: Double): Boolean = rotated.z > 1.0 / distance

    /** 0 on the horizon, 1 a little way in, so markers fade out instead of popping at the limb. */
    fun limbOpacity(rotated: Vec3, distance: Double): Float =
        ((rotated.z - 1.0 / distance) / LIMB_FADE).coerceIn(0.0, 1.0).toFloat()

    /**
     * Screen position of a coordinate in a [width] by [height] viewport, or null when it is on
     * the far side. All lengths, including the zoom's scale, are in the same unit (dp).
     */
    fun project(latitude: Double, longitude: Double, view: GlobeCamera, width: Double, height: Double): ScreenPoint? {
        val minDim = min(width, height)
        val distance = distanceForZoom(view.zoom, minDim)
        val p = rotateToView(latitude, longitude, view.latitude, view.longitude)
        if (!isVisible(p, distance)) return null
        val scale = focal(minDim) / (distance - p.z)
        return ScreenPoint((width / 2.0 + p.x * scale).toFloat(), (height / 2.0 - p.y * scale).toFloat())
    }

    /** Radius of the globe on screen, in the viewport's unit. */
    fun screenRadius(view: GlobeCamera, minDimension: Double): Double {
        val distance = distanceForZoom(view.zoom, minDimension)
        return focal(minDimension) / sqrt(distance * distance - 1.0)
    }

    /**
     * The view after dragging by ([dx], [dy]) in the viewport's unit. Longitude is scaled by the
     * latitude so the ground keeps up with the finger away from the equator, down to a floor
     * that stops it from racing near the poles.
     */
    fun pan(view: GlobeCamera, dx: Double, dy: Double, minDimension: Double): GlobeCamera {
        val perRadian = pixelsPerRadian(view, minDimension)
        val cosLat = max(cos(Math.toRadians(view.latitude)), MIN_DRAG_COS)
        val dLon = Math.toDegrees(-dx / (perRadian * cosLat))
        val dLat = Math.toDegrees(dy / perRadian)
        return clamp(view.copy(latitude = view.latitude + dLat, longitude = view.longitude + dLon), minDimension)
    }

    /** The view after a pinch that scaled the gap between fingers by [scale]. */
    fun zoomBy(view: GlobeCamera, scale: Double, minDimension: Double): GlobeCamera =
        clamp(view.copy(zoom = view.zoom + ln(scale) / LN2), minDimension)

    private fun pixelsPerRadian(view: GlobeCamera, minDimension: Double): Double =
        focal(minDimension) / (distanceForZoom(view.zoom, minDimension) - 1.0)

    /** [speed] after [seconds] of friction. */
    fun decay(speed: Double, seconds: Double, friction: Double = FLING_FRICTION): Double =
        speed * exp(-friction * seconds)

    /** Point [t] of the way from [from] to [to]; longitude takes the short way round. */
    fun interpolate(from: GlobeCamera, to: GlobeCamera, t: Double): GlobeCamera = GlobeCamera(
        latitude = from.latitude + (to.latitude - from.latitude) * t,
        longitude = wrapLongitude(from.longitude + shortestLongitudeDelta(from.longitude, to.longitude) * t),
        zoom = from.zoom + (to.zoom - from.zoom) * t,
    )

    /**
     * Column-major 3x3 rotation that turns the geographic frame so ([latitude], [longitude])
     * faces +z: first about the vertical axis by -longitude, then about x by latitude.
     */
    fun rotationMatrix(latitude: Double, longitude: Double): FloatArray {
        val lon = Math.toRadians(longitude)
        val lat = Math.toRadians(latitude)
        val (cl, sl) = cos(lon) to sin(lon)
        val (ct, st) = cos(lat) to sin(lat)
        // Rx(lat) * Ry(-lon), rows then flattened column by column.
        val rows = arrayOf(
            doubleArrayOf(cl, 0.0, -sl),
            doubleArrayOf(-st * sl, ct, -st * cl),
            doubleArrayOf(ct * sl, st, ct * cl),
        )
        return FloatArray(9) { rows[it % 3][it / 3].toFloat() }
    }

    /**
     * Column-major 4x4 that takes a point of the rotated globe to clip space: the camera sits
     * [distance] back on +z and the viewport is [widthPx] by [heightPx] with its shorter side
     * spanning the field of view.
     */
    fun projectionMatrix(distance: Double, widthPx: Float, heightPx: Float): FloatArray {
        val f = 1.0 / tan(Math.toRadians(FOV_DEGREES / 2.0))
        val minPx = min(widthPx, heightPx).toDouble()
        val near = (distance - 1.0) * 0.5
        val far = distance + 1.5
        val m = FloatArray(16)
        m[0] = (f * minPx / widthPx).toFloat()
        m[5] = (f * minPx / heightPx).toFloat()
        m[10] = (-(far + near) / (far - near)).toFloat()
        m[11] = -1f
        m[14] = (-2.0 * far * near / (far - near)).toFloat()
        return m
    }
}

/** When the globe hands over to the flat map and back. All zooms are the flat map's. */
object GlobeTransition {
    /** From here on the flat map is in charge. */
    const val ENTER_MAP_ZOOM = 3.0

    /** The globe starts fading out here, while the map follows its camera underneath. */
    const val FADE_START_ZOOM = 2.4

    /** Zooming the map out past this brings the globe back. */
    const val EXIT_MAP_ZOOM = 2.0

    /** Opacity of the globe at a map-equivalent zoom: 1 when far, 0 at [ENTER_MAP_ZOOM], smooth between. */
    fun globeOpacity(mapZoom: Double): Float {
        val t = ((mapZoom - FADE_START_ZOOM) / (ENTER_MAP_ZOOM - FADE_START_ZOOM)).coerceIn(0.0, 1.0)
        return (1.0 - t * t * (3.0 - 2.0 * t)).toFloat()
    }

    /** True once the globe has zoomed in far enough to give way to the map. */
    fun shouldEnterMap(mapZoom: Double): Boolean = mapZoom >= ENTER_MAP_ZOOM

    /** True when the map is zoomed out far enough for the globe to take over. */
    fun shouldEnterGlobe(mapZoom: Double): Boolean = mapZoom < EXIT_MAP_ZOOM

    /** True while the flat map has to follow the globe's camera so the fade lines up. */
    fun mapShouldFollow(mapZoom: Double): Boolean = mapZoom > FADE_START_ZOOM
}
