package com.oceanguard.ai.inference.species

import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.sqrt

/**
 * ONNX Runtime Mobile implementation of [SpeciesEmbedder].
 *
 * The input contract (size, mean/std, tensor names, output dimension) comes
 * from the species pack ([SpeciesPackConfig.EncoderSpec]) instead of being
 * hard-coded, so the encoder can change without an app update.
 *
 * ## Execution provider and self-test
 * The session first tries the NNAPI EP with fp16 relaxation (fast on the
 * Exynos NPU/GPU) and falls back to CPU. When the pack carries a
 * [SpeciesPackConfig.SelfTest], the first session runs the synthetic
 * [SelfTestPattern] and compares the result with the fp32 PC embedding; if the
 * cosine is below `minCosine` the NNAPI session is dropped and a CPU-only
 * session is used instead. If the CPU session also fails, [embed] throws: an
 * encoder that does not reproduce the PC embedding would make every photo look
 * like the same species.
 *
 * ## Preprocessing (same as tools/species/biodex_common.py::clip_tensor)
 *   1. Centre-square crop + bilinear resize to `size` (skipped when the input
 *      already is `size`x`size`, the normal path through [RagCropPreparer]).
 *   2. Normalise with the pack mean/std; NCHW float32 [1, 3, size, size].
 *   3. Output [1, dim] is L2-normalised.
 */
class OnnxSpeciesEmbedder(
    private val modelFile: File,
    private val spec: SpeciesPackConfig.EncoderSpec = SpeciesPackConfig.EncoderSpec(),
    private val selfTest: SpeciesPackConfig.SelfTest? = null,
) : SpeciesEmbedder {

    companion object {
        private const val TAG = "OnnxSpeciesEmbedder"
        private const val CPU_THREADS = 4
    }

    private var ortEnv: OrtEnvironment? = null
    @Volatile private var ortSession: OrtSession? = null

    /** Execution provider of the live session ("NNAPI", "CPU") for diagnostics. */
    @Volatile var executionProvider: String = "none"
        private set

    /** Cosine of the last self-test (device vs PC); null when the pack has none. */
    @Volatile var selfTestCosine: Float? = null
        private set

    /** Serialises the one-time session creation (prewarm racing the first embed). */
    private val initMutex = Mutex()

    override val isReady: Boolean
        get() = modelFile.exists()

    override val dimension: Int
        get() = spec.dim

    /**
     * Create the ORT session once, validating it with the self-test.
     * @throws IllegalStateException if the model is missing or no execution
     *         provider reproduces the reference embedding.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (ortSession != null) return@withContext
            check(modelFile.exists()) { "Species encoder not found at ${modelFile.absolutePath}" }
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            val nnapi = runCatching { env.createSession(modelFile.absolutePath, options(useNnapi = true)) }
                .onFailure { Log.w(TAG, "NNAPI session failed: ${it.message}") }
                .getOrNull()
            if (nnapi != null && passesSelfTest(env, nnapi, "NNAPI")) {
                ortSession = nnapi
                executionProvider = "NNAPI"
                return@withContext
            }
            nnapi?.close()
            val cpu = env.createSession(modelFile.absolutePath, options(useNnapi = false))
            check(passesSelfTest(env, cpu, "CPU")) {
                cpu.close()
                "Species encoder self-test failed on CPU (cosine=$selfTestCosine): pack and encoder do not match"
            }
            ortSession = cpu
            executionProvider = "CPU"
        }
    }

    private fun options(useNnapi: Boolean) = OrtSession.SessionOptions().apply {
        if (useNnapi) {
            runCatching { addNnapi(EnumSet.of(NNAPIFlags.USE_FP16)) }.onFailure { addNnapi() }
        }
        setIntraOpNumThreads(CPU_THREADS)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }

    /** True when there is no self-test or the session reproduces the PC embedding. */
    private fun passesSelfTest(env: OrtEnvironment, session: OrtSession, ep: String): Boolean {
        val test = selfTest ?: return true
        val input = SelfTestPattern.tensor(test.pattern, spec) ?: return true
        if (test.expected.size != spec.dim) return true
        val start = System.currentTimeMillis()
        val out = run(env, session, input)
        val cos = SelfTestPattern.cosine(out, test.expected.toFloatArray())
        selfTestCosine = cos
        Log.i(TAG, "Self-test $ep: cosine=$cos (min ${test.minCosine}) in ${System.currentTimeMillis() - start} ms")
        return cos >= test.minCosine
    }

    /** Load the session and run once, so the first real photo does not pay the compile. */
    suspend fun prewarm() = withContext(Dispatchers.IO) {
        try {
            if (!isReady) return@withContext
            initialize()
            val session = ortSession ?: return@withContext
            val env = ortEnv ?: return@withContext
            val start = System.currentTimeMillis()
            run(env, session, FloatArray(3 * spec.size * spec.size))
            Log.i(TAG, "Encoder pre-warmed ($executionProvider) in ${System.currentTimeMillis() - start} ms")
        } catch (e: Exception) {
            Log.w(TAG, "Encoder prewarm failed (non-fatal): ${e.message}")
        }
    }

    override suspend fun embed(bitmap: Bitmap): FloatArray = withContext(Dispatchers.IO) {
        if (ortSession == null) initialize()
        val session = ortSession ?: error("ORT session is null")
        val env = ortEnv ?: error("ORT environment is null")
        run(env, session, preprocess(bitmap))
    }

    private fun run(env: OrtEnvironment, session: OrtSession, input: FloatArray): FloatArray {
        val shape = longArrayOf(1, 3, spec.size.toLong(), spec.size.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(spec.inputName to tensor)).use { output ->
                val raw = (output[spec.outputName].get().value as Array<*>)[0] as FloatArray
                check(raw.size == spec.dim) { "Encoder output dim ${raw.size} != pack dim ${spec.dim}" }
                return l2Normalize(raw)
            }
        }
    }

    override fun close() {
        ortSession?.close()
        ortSession = null
        ortEnv = null
        executionProvider = "none"
    }

    /** Centre-square + resize (when needed) + NCHW float32 + pack normalisation. */
    private fun preprocess(bitmap: Bitmap): FloatArray {
        val size = spec.size
        val scaled: Bitmap
        val cropped: Bitmap?
        if (bitmap.width == size && bitmap.height == size) {
            scaled = bitmap
            cropped = null
        } else {
            val side = minOf(bitmap.width, bitmap.height)
            cropped = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
            scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
        }
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        val plane = size * size
        val tensor = FloatArray(3 * plane)
        for (i in pixels.indices) {
            val p = pixels[i]
            tensor[i] = (((p shr 16) and 0xFF) / 255f - spec.mean[0]) / spec.std[0]
            tensor[plane + i] = (((p shr 8) and 0xFF) / 255f - spec.mean[1]) / spec.std[1]
            tensor[2 * plane + i] = ((p and 0xFF) / 255f - spec.mean[2]) / spec.std[2]
        }
        // Only recycle bitmaps allocated here; never the caller's input.
        if (cropped != null && cropped !== bitmap) cropped.recycle()
        if (scaled !== bitmap && scaled !== cropped) scaled.recycle()
        return tensor
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 1e-8f) for (i in v.indices) v[i] /= norm
        return v
    }
}
