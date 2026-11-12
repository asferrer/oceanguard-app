package com.oceanguard.ai.ui.screens.map.globe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Latitude/longitude grid over the unit sphere. Each vertex is a position and a texture
 * coordinate into an equirectangular map, so the seam at longitude 180 has a duplicated column.
 * The index count stays under 65,536 vertices so 16-bit indices are enough on every GLES 2 device.
 */
internal class GlobeMesh(private val bands: Int = LAT_BANDS, private val segments: Int = LON_SEGMENTS) {

    val vertices: FloatBuffer = buildVertices()
    val indices: ShortBuffer = buildIndices()
    val indexCount: Int = bands * segments * 6

    private fun buildVertices(): FloatBuffer {
        val buffer = direct((bands + 1) * (segments + 1) * FLOATS_PER_VERTEX * 4).asFloatBuffer()
        for (row in 0..bands) {
            val v = row.toFloat() / bands
            val lat = Math.PI * (0.5 - v)
            for (col in 0..segments) {
                val u = col.toFloat() / segments
                val lon = Math.PI * (2.0 * u - 1.0)
                buffer.put((cos(lat) * sin(lon)).toFloat())
                buffer.put(sin(lat).toFloat())
                buffer.put((cos(lat) * cos(lon)).toFloat())
                buffer.put(u)
                buffer.put(v)
            }
        }
        return buffer.also { it.position(0) }
    }

    private fun buildIndices(): ShortBuffer {
        val buffer = direct(bands * segments * 6 * 2).asShortBuffer()
        val stride = segments + 1
        for (row in 0 until bands) {
            for (col in 0 until segments) {
                val a = (row * stride + col).toShort()
                val b = (row * stride + col + 1).toShort()
                val c = ((row + 1) * stride + col).toShort()
                val d = ((row + 1) * stride + col + 1).toShort()
                buffer.put(a).put(c).put(b).put(b).put(c).put(d)
            }
        }
        return buffer.also { it.position(0) }
    }

    companion object {
        const val FLOATS_PER_VERTEX = 5
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4
        private const val LAT_BANDS = 64
        private const val LON_SEGMENTS = 128

        /** Two triangles covering the [-1, 1] square, as a strip. */
        val QUAD: FloatBuffer = direct(8 * 4).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

        private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}
