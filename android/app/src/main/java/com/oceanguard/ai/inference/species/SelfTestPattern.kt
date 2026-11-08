package com.oceanguard.ai.inference.species

import kotlin.math.sqrt

/**
 * Deterministic synthetic encoder input used to verify, on the device, that
 * the ONNX session produces the same embedding as the PC in fp32.
 *
 * Accelerator paths (NNAPI with fp16 relaxation, vendor drivers) can silently
 * degrade a ViT; when that happens every photo maps to nearly the same vector
 * and the index answers the same species for everything. The pack ships the
 * PC embedding of this pattern (`species_pack.json` -> selfTest.expected) and
 * [OnnxSpeciesEmbedder] compares it with its own output once per session.
 *
 * The generator must stay identical to `tools/species/biodex_common.py::self_test_pattern`.
 */
object SelfTestPattern {
    const val GRADIENT_V1 = "gradient_v1"

    /** Integer RGB value of pixel (x, y) for [GRADIENT_V1] on a [size] grid. */
    internal fun rgb(x: Int, y: Int, size: Int): Triple<Int, Int, Int> {
        val m = size - 1
        return Triple(x * 255 / m, y * 255 / m, (x + y) * 255 / (2 * m))
    }

    /** NCHW float tensor of [pattern], normalised with [spec] mean/std. Null if unknown. */
    fun tensor(pattern: String, spec: SpeciesPackConfig.EncoderSpec): FloatArray? {
        if (pattern != GRADIENT_V1) return null
        val s = spec.size
        val plane = s * s
        val out = FloatArray(3 * plane)
        for (y in 0 until s) {
            for (x in 0 until s) {
                val (r, g, b) = rgb(x, y, s)
                val i = y * s + x
                out[i] = (r / 255f - spec.mean[0]) / spec.std[0]
                out[plane + i] = (g / 255f - spec.mean[1]) / spec.std[1]
                out[2 * plane + i] = (b / 255f - spec.mean[2]) / spec.std[2]
            }
        }
        return out
    }

    /** Cosine similarity of two vectors (any norm). 0 when either is zero or sizes differ. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }
}
