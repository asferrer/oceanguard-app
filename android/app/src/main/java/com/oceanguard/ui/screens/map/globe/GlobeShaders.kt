package com.oceanguard.ai.ui.screens.map.globe

import android.opengl.GLES20
import android.util.Log

/** GLSL ES 1.00 programs of the globe; every fragment shader outputs premultiplied alpha. */
internal object GlobeShaders {

    private const val FRAGMENT_PRECISION = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
    """

    /** Textured sphere: ambient plus a fixed key light from the upper left, and a cool rim. */
    const val SPHERE_VERTEX = """
        uniform mat4 uMvp;
        uniform mat3 uRot;
        attribute vec3 aPos;
        attribute vec2 aUv;
        varying vec2 vUv;
        varying vec3 vNormal;
        void main() {
            vUv = aUv;
            vNormal = uRot * aPos;
            gl_Position = uMvp * vec4(aPos, 1.0);
        }
    """

    val SPHERE_FRAGMENT = FRAGMENT_PRECISION + """
        uniform sampler2D uTexture;
        uniform vec3 uRimColor;
        uniform float uDistance;
        uniform float uOpacity;
        varying vec2 vUv;
        varying vec3 vNormal;
        void main() {
            vec3 n = normalize(vNormal);
            vec3 toCamera = normalize(vec3(0.0, 0.0, uDistance) - vNormal);
            vec3 light = normalize(vec3(-0.55, 0.45, 0.70));
            float sun = max(dot(n, light), 0.0);
            vec3 color = texture2D(uTexture, vUv).rgb * (0.42 + 0.78 * sun);
            float rim = pow(1.0 - max(dot(n, toCamera), 0.0), 3.0);
            color += uRimColor * rim * 0.55;
            gl_FragColor = vec4(color * uOpacity, uOpacity);
        }
    """

    /** Quad in the view plane, behind the sphere, that carries the atmosphere glow. */
    const val GLOW_VERTEX = """
        uniform mat4 uProjView;
        uniform float uHalfSize;
        attribute vec2 aCorner;
        varying vec2 vCorner;
        void main() {
            vCorner = aCorner * uHalfSize;
            gl_Position = uProjView * vec4(vCorner, 0.0, 1.0);
        }
    """

    val GLOW_FRAGMENT = FRAGMENT_PRECISION + """
        uniform vec3 uGlowColor;
        uniform float uSilhouette;
        uniform float uOpacity;
        varying vec2 vCorner;
        void main() {
            float t = (length(vCorner) - uSilhouette) / (uSilhouette * 0.24);
            float glow = pow(clamp(1.0 - t, 0.0, 1.0), 2.4) * step(-0.5, t);
            float a = glow * 0.85 * uOpacity;
            gl_FragColor = vec4(uGlowColor * a, a);
        }
    """

    /** Full-screen quad: the space colour with a sparse star field that drifts as the globe turns. */
    const val SPACE_VERTEX = """
        attribute vec2 aCorner;
        varying vec2 vCorner;
        void main() {
            vCorner = aCorner;
            gl_Position = vec4(aCorner, 0.0, 1.0);
        }
    """

    val SPACE_FRAGMENT = FRAGMENT_PRECISION + """
        uniform vec3 uSpaceColor;
        uniform vec2 uResolution;
        uniform vec2 uDrift;
        uniform float uOpacity;
        varying vec2 vCorner;
        float hash(vec2 p) {
            return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
        }
        void main() {
            vec2 cell = floor((vCorner * 0.5 + 0.5) * uResolution / 3.0 + uDrift);
            float h = hash(cell);
            float star = step(0.9965, h) * (0.35 + 0.65 * hash(cell + 7.0));
            vec3 color = uSpaceColor + vec3(star);
            gl_FragColor = vec4(color * uOpacity, uOpacity);
        }
    """

    /** Compiles and links a program; returns 0 and logs when the driver rejects it. */
    fun link(vertex: String, fragment: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        if (v == 0 || f == 0) return 0
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, v)
        GLES20.glAttachShader(program, f)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        if (status[0] == 0) {
            Log.w(TAG, "Globe program failed to link: " + GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w(TAG, "Globe shader failed to compile: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private const val TAG = "GlobeShaders"
}
