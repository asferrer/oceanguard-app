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
            float rim = pow(1.0 - max(dot(n, toCamera), 0.0), 4.5);
            color += mix(uRimColor, vec3(0.85, 0.95, 1.0), rim) * rim * (0.45 + 0.55 * sun) * 0.9;
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

    /**
     * Thin atmosphere: a bright, narrow core hugging the limb that fades outward into the glow
     * colour, like the thin blue line seen from orbit.
     */
    val GLOW_FRAGMENT = FRAGMENT_PRECISION + """
        uniform vec3 uGlowColor;
        uniform float uSilhouette;
        uniform float uOpacity;
        varying vec2 vCorner;
        void main() {
            float t = (length(vCorner) - uSilhouette) / (uSilhouette * 0.13);
            float k = clamp(1.0 - t, 0.0, 1.0);
            float core = pow(k, 7.0);
            float haze = pow(k, 2.6);
            vec3 color = mix(uGlowColor, vec3(0.80, 0.94, 1.0), core);
            float a = (haze * 0.5 + core * 0.5) * smoothstep(-0.12, 0.0, t) * uOpacity;
            gl_FragColor = vec4(color * a, a);
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

    /**
     * Deep space, drawn per pixel with no texture: three star layers of different size, brightness
     * and colour temperature, a faint Milky Way band with dust lanes and denser stars inside it,
     * and soft violet and blue nebula tones. Each layer drifts a little differently as the globe
     * turns, which reads as depth.
     */
    val SPACE_FRAGMENT = FRAGMENT_PRECISION + """
        uniform vec3 uSpaceColor;
        uniform vec2 uResolution;
        uniform vec2 uDrift;
        uniform float uOpacity;
        varying vec2 vCorner;

        float hash(vec2 p) {
            p = fract(p * vec2(123.34, 456.21));
            p += dot(p, p + 45.32);
            return fract(p.x * p.y);
        }

        float noise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                       mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }

        float fbm(vec2 p) {
            float v = 0.0;
            float a = 0.5;
            for (int i = 0; i < 4; i++) {
                v += a * noise(p);
                p = p * 2.03 + 17.0;
                a *= 0.5;
            }
            return v;
        }

        // One layer of stars on a grid of cell-sized squares; at most one star per cell.
        vec3 stars(vec2 px, float cell, float density, float maxSize, float seed) {
            vec2 g = px / cell;
            vec2 id = floor(g);
            vec2 f = fract(g) - 0.5;
            if (hash(id + seed) > density) return vec3(0.0);
            vec2 off = (vec2(hash(id + seed + 1.3), hash(id + seed + 2.7)) - 0.5) * 0.6;
            float size = mix(0.015, maxSize, pow(hash(id + seed + 5.1), 2.0));
            float shape = smoothstep(size, 0.0, length(f - off));
            float bright = 0.25 + 0.75 * pow(hash(id + seed + 9.2), 1.5);
            float temp = hash(id + seed + 3.3);
            vec3 tint = mix(mix(vec3(1.0, 0.72, 0.55), vec3(1.0), 0.5), mix(vec3(0.62, 0.78, 1.0), vec3(1.0), 0.4), temp);
            return tint * shape * bright;
        }

        void main() {
            vec2 px = (vCorner * 0.5 + 0.5) * uResolution;
            vec2 q = px / uResolution.y;

            // Milky Way: a tilted band, broken up by dust, with its own colour.
            vec2 qb = q * mat2(0.906, -0.423, 0.423, 0.906) + uDrift / uResolution.y * 0.08;
            float across = qb.y - 0.05 * sin(qb.x * 2.4);
            float band = exp(-across * across / 0.045);
            float dust = fbm(qb * vec2(3.0, 5.5) + 3.1);
            float mw = band * smoothstep(0.28, 0.85, dust);
            vec3 color = uSpaceColor;
            color += mix(vec3(0.20, 0.24, 0.42), vec3(0.42, 0.34, 0.50), dust) * mw * 0.42;

            // Nebula tones, deep blue and violet, kept faint.
            vec2 qn = q * 1.5 + uDrift / uResolution.y * 0.05;
            float n1 = fbm(qn + 4.0);
            float n2 = fbm(qn * 1.3 - 9.0);
            color += vec3(0.05, 0.12, 0.30) * smoothstep(0.45, 0.85, n1) * 0.45;
            color += vec3(0.20, 0.07, 0.30) * smoothstep(0.50, 0.90, n2) * 0.38;

            // Stars: far and dense and faint, mid, near and sparse and bright. Many more in the band.
            color += stars(px + uDrift * 0.35, 9.0, 0.16 + band * 0.45, 0.07, 1.0) * 0.55;
            color += stars(px + uDrift * 0.65, 19.0, 0.20 + band * 0.25, 0.11, 21.0) * 0.85;
            color += stars(px + uDrift, 41.0, 0.24, 0.17, 57.0) * 1.15;

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
