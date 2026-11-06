package com.oceanguard.ai.ui.components.dex

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A point in a unit square centred on the origin, scaled by the caller. */
data class FxPoint(val x: Float, val y: Float)

/**
 * Pure maths of the six unlock effects. Every function takes the timeline position `k` in
 * `[0, 1]` (see [DURATION_MS]) and returns normalised values, so the drawing code stays free of
 * arithmetic and the behaviour can be tested without a device.
 */
object AchievementFxMath {

    /** Length of one unlock effect. It is a celebratory moment, longer than a UI transition. */
    const val DURATION_MS = 1_700

    const val PING_COUNT = 4
    private const val PING_SPACING = 0.16f

    const val SWEEP_RINGS = 4
    private const val SWEEP_TURNS = 7f
    private const val SWEEP_START = -1.6f

    const val NETWORK_POINTS = 26
    private const val NETWORK_PULL = 0.72f
    private const val NETWORK_LINKS_FROM = 0.2f

    const val RAY_COUNT = 14
    private const val RAY_GROWTH = 1.8f
    private const val RAY_SPIN = 1.1f

    private const val TWO_PI = (2.0 * PI).toFloat()

    /** Hand-placed sonar findings: polar angle in radians and radius as a share of the range. */
    val SWEEP_FINDINGS: List<Pair<Float, Float>> =
        listOf(1f to 0.4f, 2.6f to 0.7f, 4.3f to 0.9f, 5.4f to 0.6f)

    fun timeline(elapsedMs: Long): Float =
        (elapsedMs.toFloat() / DURATION_MS).coerceIn(0f, 1f)

    /**
     * Progress of the [index]-th ripple of the "first steps" wave, or null while it has not
     * started or after it has faded out. 0 is the centre, 1 the full radius.
     */
    fun pingProgress(k: Float, index: Int): Float? {
        val p = k - index * PING_SPACING
        return if (p > 0f && p < 1f) p else null
    }

    fun pingAlpha(progress: Float): Float = (1f - progress).coerceIn(0f, 1f)

    /** Current angle of the sonar arm, in radians. */
    fun sweepAngle(k: Float): Float = k * SWEEP_TURNS + SWEEP_START

    /** A finding lights up once the arm has passed over it. */
    fun findingVisible(k: Float, findingAngle: Float): Boolean =
        sweepAngle(k) > findingAngle + SWEEP_START

    /**
     * Position of the [index]-th sprite of [count] in the collector's orbit. The orbit starts
     * wide and closes in as the sprites gather around the trophy.
     */
    fun orbitPoint(index: Int, count: Int, k: Float): FxPoint {
        if (count <= 0) return FxPoint(0f, 0f)
        val gather = (k * 1.5f).coerceAtMost(1f)
        val radius = 0.34f * (2f - gather) * 0.55f + 0.2f
        val angle = index.toFloat() / count * TWO_PI + k * 1.6f
        return FxPoint(cos(angle) * radius, sin(angle) * radius * 0.9f)
    }

    fun orbitAlpha(k: Float): Float = (k * 3f).coerceIn(0f, 1f)

    /** Water level as a share of the height covered, from the bottom up. */
    fun tideLevel(k: Float): Float = (k * 0.72f).coerceAtMost(0.62f)

    /** Vertical offset of a wave crest, in units of [amplitude], at horizontal position [x]. */
    fun waveOffset(x: Float, k: Float, layer: Int, amplitude: Float): Float =
        sin(x / 38f + k * 9f + layer) * amplitude

    /** Where the [index]-th data point starts: spread over the screen by a fixed pattern. */
    fun networkStart(index: Int): FxPoint =
        FxPoint(((index * 97) % 1000) / 1000f, ((index * 173) % 1000) / 1000f)

    /** Point [start] pulled towards the centre (0.5, 0.5) as the network forms. */
    fun networkPoint(start: FxPoint, k: Float): FxPoint {
        val t = (k * 1.3f).coerceAtMost(1f) * NETWORK_PULL
        return FxPoint(start.x + (0.5f - start.x) * t, start.y + (0.5f - start.y) * t)
    }

    /** Pairs of point indices joined by a line, without repeats or self-links. */
    fun networkLinks(count: Int): List<Pair<Int, Int>> {
        if (count < 2) return emptyList()
        val links = LinkedHashSet<Pair<Int, Int>>()
        for (i in 0 until count) {
            val j = (i * 3 + 1) % count
            if (j != i) links += minOf(i, j) to maxOf(i, j)
        }
        return links.toList()
    }

    fun networkLinksVisible(k: Float): Boolean = k > NETWORK_LINKS_FROM

    /** Length of each golden ray as a share of its maximum. */
    fun rayLength(k: Float): Float = (k * RAY_GROWTH).coerceAtMost(1f)

    /** Rotation of the whole crown of rays, in radians. */
    fun raySpin(k: Float): Float = k * RAY_SPIN
}
