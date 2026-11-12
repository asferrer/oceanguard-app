package com.oceanguard.ai.ui.screens.map.globe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.oceanguard.ai.R
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sin
import kotlin.math.sqrt

/** One frame of the globe: where the camera is and how opaque the whole scene is. */
internal data class GlobeFrame(
    val latitude: Double,
    val longitude: Double,
    val distance: Double,
    val opacity: Float,
)

/** Colours of the scene as packed 0xRRGGBB integers, taken from the app theme. */
internal data class GlobeColors(val space: Int, val glow: Int, val rim: Int)

/**
 * Draws the globe with OpenGL ES 2.0: star field, atmosphere glow, then the textured sphere.
 * The Blue Marble texture is bundled, so nothing here needs a network. All GL work happens on the
 * GL thread; [frame] is the only thing the UI thread touches.
 */
internal class GlobeRenderer(
    private val context: Context,
    private val colors: GlobeColors,
) : GLSurfaceView.Renderer {

    @Volatile
    var frame: GlobeFrame = GlobeFrame(0.0, 0.0, GlobeMath.MAX_DISTANCE, 1f)

    private val mesh = GlobeMesh()
    private var sphere = 0
    private var glow = 0
    private var space = 0
    private var texture = 0
    private var width = 1
    private var height = 1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        sphere = GlobeShaders.link(GlobeShaders.SPHERE_VERTEX, GlobeShaders.SPHERE_FRAGMENT)
        glow = GlobeShaders.link(GlobeShaders.GLOW_VERTEX, GlobeShaders.GLOW_FRAGMENT)
        space = GlobeShaders.link(GlobeShaders.SPACE_VERTEX, GlobeShaders.SPACE_FRAGMENT)
        texture = loadTexture()
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val f = frame
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (sphere == 0 || glow == 0 || space == 0) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        drawSpace(f)
        drawGlow(f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        drawSphere(f)
    }

    private fun drawSpace(f: GlobeFrame) {
        GLES20.glUseProgram(space)
        uniform3(space, "uSpaceColor", colors.space)
        GLES20.glUniform2f(location(space, "uResolution"), width.toFloat(), height.toFloat())
        GLES20.glUniform2f(location(space, "uDrift"), (sin(Math.toRadians(f.longitude)) * DRIFT_RANGE_PX).toFloat(), (f.latitude * DRIFT_PER_DEGREE).toFloat())
        GLES20.glUniform1f(location(space, "uOpacity"), f.opacity)
        drawQuad(space)
    }

    private fun drawGlow(f: GlobeFrame) {
        val silhouette = f.distance / sqrt(f.distance * f.distance - 1.0)
        GLES20.glUseProgram(glow)
        GLES20.glUniformMatrix4fv(location(glow, "uProjView"), 1, false, projectionView(f), 0)
        GLES20.glUniform1f(location(glow, "uHalfSize"), (silhouette * GLOW_EXTENT).toFloat())
        GLES20.glUniform1f(location(glow, "uSilhouette"), silhouette.toFloat())
        uniform3(glow, "uGlowColor", colors.glow)
        GLES20.glUniform1f(location(glow, "uOpacity"), f.opacity)
        drawQuad(glow)
    }

    private fun drawSphere(f: GlobeFrame) {
        GLES20.glUseProgram(sphere)
        GLES20.glUniformMatrix4fv(location(sphere, "uMvp"), 1, false, mvp(f), 0)
        GLES20.glUniformMatrix3fv(location(sphere, "uRot"), 1, false, GlobeMath.rotationMatrix(f.latitude, f.longitude), 0)
        GLES20.glUniform1f(location(sphere, "uDistance"), f.distance.toFloat())
        GLES20.glUniform1f(location(sphere, "uOpacity"), f.opacity)
        uniform3(sphere, "uRimColor", colors.rim)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(location(sphere, "uTexture"), 0)
        val pos = GLES20.glGetAttribLocation(sphere, "aPos")
        val uv = GLES20.glGetAttribLocation(sphere, "aUv")
        mesh.vertices.position(0)
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, GlobeMesh.STRIDE_BYTES, mesh.vertices)
        mesh.vertices.position(3)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, GlobeMesh.STRIDE_BYTES, mesh.vertices)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glEnableVertexAttribArray(uv)
        mesh.indices.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, mesh.indices)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(uv)
    }

    private fun drawQuad(program: Int) {
        val corner = GLES20.glGetAttribLocation(program, "aCorner")
        GlobeMesh.QUAD.position(0)
        GLES20.glVertexAttribPointer(corner, 2, GLES20.GL_FLOAT, false, 0, GlobeMesh.QUAD)
        GLES20.glEnableVertexAttribArray(corner)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(corner)
    }

    /** Projection times the camera's backwards step: view space to clip space. */
    private fun projectionView(f: GlobeFrame): FloatArray {
        val p = GlobeMath.projectionMatrix(f.distance, width.toFloat(), height.toFloat())
        // Translating by -distance in z only changes the last column: p * T = p with col3 += p * (0,0,-d,1).
        val out = p.copyOf()
        for (row in 0 until 4) out[12 + row] = p[8 + row] * (-f.distance).toFloat() + p[12 + row]
        return out
    }

    private fun mvp(f: GlobeFrame): FloatArray {
        val pv = projectionView(f)
        val r = GlobeMath.rotationMatrix(f.latitude, f.longitude)
        val out = FloatArray(16)
        for (col in 0 until 3) {
            for (row in 0 until 4) {
                out[col * 4 + row] = pv[row] * r[col * 3] + pv[4 + row] * r[col * 3 + 1] + pv[8 + row] * r[col * 3 + 2]
            }
        }
        for (row in 0 until 4) out[12 + row] = pv[12 + row]
        return out
    }

    private fun location(program: Int, name: String) = GLES20.glGetUniformLocation(program, name)

    private fun uniform3(program: Int, name: String, rgb: Int) {
        val r = (rgb shr 16 and 0xFF) / 255f
        val g = (rgb shr 8 and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        GLES20.glUniform3f(location(program, name), r, g, b)
    }

    /** Uploads the bundled map, halved until it fits the device's largest texture. */
    private fun loadTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val bitmap = decodeFitting(maxTextureSize()) ?: return ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        bitmap.recycle()
        return ids[0]
    }

    private fun maxTextureSize(): Int {
        val size = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, size, 0)
        return size[0].coerceAtLeast(MIN_TEXTURE_SIZE)
    }

    private fun decodeFitting(maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, R.drawable.globe_blue_marble, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSize) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
        }
        return BitmapFactory.decodeResource(context.resources, R.drawable.globe_blue_marble, options)
    }

    private companion object {
        /** The sphere's glow reaches this far past its silhouette. */
        const val GLOW_EXTENT = 1.17

        /** Nearest star layer shift, in px: sideways over a full turn (smooth at the date line), and per degree of latitude. */
        const val DRIFT_RANGE_PX = 420.0
        const val DRIFT_PER_DEGREE = 3.0
        const val MIN_TEXTURE_SIZE = 1024
    }
}
