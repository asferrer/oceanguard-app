package com.oceanguard.ai.inference

import android.content.Context
import android.util.Log
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.data.model.BestModel
import com.oceanguard.ai.inference.species.SpeciesPackAssets
import com.oceanguard.ai.inference.species.SpeciesPackConfig
import com.oceanguard.ai.inference.species.SpeciesPackUpdate
import com.oceanguard.ai.utils.ModelUpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the VLM model lifecycle: availability checks and on-demand downloads
 * from HuggingFace (Apache 2.0, no auth required).
 *
 * Three tiers of models:
 * - **Fast text** (~1.0 GB): Qwen3.5-1.5B-Q4_K_M.gguf — snappy reports
 * - **Quality text** (~1.9 GB): Qwen3.5-3B-Q4_K_M.gguf — better coherence
 * - **Vision** (~1.6 GB total): Qwen3.5-2B-Q4_K_M.gguf + mmproj — image-aware reports
 *
 * Download state is exposed via [downloadState] for UI and [VlmDownloadService].
 */
class VlmModelManager(
    private val context: Context,
    private val settings: SettingsRepository? = null,
) {

    companion object {
        private const val TAG = "VlmModelManager"

        // HuggingFace direct-download base (Apache 2.0 models, no auth)
        private const val HF_BASE = "https://huggingface.co"

        // Text model URLs per tier (string literals — const val cannot reference enum properties)
        // Qwen3.5 family: 0.8B / 2B / 4B / 9B. No 1.5B or 3B variants exist.
        private const val TEXT_FAST_URL =
            "$HF_BASE/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf"
        private const val TEXT_BALANCED_URL =
            "$HF_BASE/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf"
        private const val TEXT_QUALITY_URL =
            "$HF_BASE/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf"

        // Gemma 4 E2B — Apache 2.0, LiteRT-LM (.litertlm) for on-device inference
        // GGUF variant removed: LiteRT-LM is faster on the target phone (figure pending measurement)
        private const val GEMMA4_MMPROJ_URL =
            "$HF_BASE/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/mmproj-gemma-4-e2b-it-f16.gguf"

        // Vision model URLs — Qwen (2B + mmproj)
        private const val VISION_MODEL_URL =
            "$HF_BASE/unsloth/Qwen3.5-2B-GGUF/resolve/main/${LlamaVisionEngine.QWEN_MODEL_FILENAME}"
        private const val MMPROJ_URL =
            "$HF_BASE/unsloth/Qwen3.5-2B-GGUF/resolve/main/${LlamaVisionEngine.QWEN_MMPROJ_FILENAME}"

        // Gemma 4 filenames
        const val GEMMA4_MMPROJ_FILENAME = "mmproj-gemma-4-e2b-it-f16.gguf"
        private const val GEMMA4_LITERTLM_URL =
            "$HF_BASE/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        const val GEMMA4_LITERTLM_FILENAME = "gemma-4-E2B-it.litertlm"
        private const val MIN_GEMMA4_LITERTLM_BYTES = 2_000_000_000L // 2 GB (~2.58 GB)

        // Gemma 4 — OceanGuard fine-tuned variant (LiteRT-LM, int8, ~5.0 GB, text + vision).
        // When false, the app neither offers nor downloads this variant.
        const val FINETUNED_LITERTLM_PUBLISHED = true
        const val GEMMA4_LITERTLM_FINETUNED_FILENAME = "gemma-4-E2B-it-oceanguard.litertlm"
        private const val GEMMA4_LITERTLM_FINETUNED_URL =
            "$HF_BASE/asferrer/gemma-4-E2B-it-oceanguard-marine-debris/resolve/main/gemma-4-E2B-it-oceanguard.litertlm"

        // OceanGuard fine-tuned GGUF — exp11g adapter merged + Q4_K_M, served via llama.cpp
        // mAP@0.5 on TEST-200: 0.2941 vs 0.1381 base (x2.13); VAL-100: 0.2820 vs 0.1081.
        const val OCEANGUARD_GGUF_FILENAME = "gemma-4-E2B-it-oceanguard-Q4_K_M.gguf"
        private const val OCEANGUARD_GGUF_URL =
            "$HF_BASE/asferrer/gemma-4-E2B-it-oceanguard-marine-debris/resolve/main/gemma-4-E2B-it-oceanguard-Q4_K_M.gguf"
        private const val MIN_OCEANGUARD_GGUF_BYTES = 3_000_000_000L // 3.0 GB minimum (actual 3.42 GB Q4_K_M, large 262k vocab)

        // --- BioDex species pack (M4) ---
        private const val SPECIES_REPO = "$HF_BASE/asferrer/oceanguard-biodex/resolve/main"
        // filename -> minimum valid bytes (guards against HF HTML error pages).
        // The file list lives in SpeciesPackAssets (v1 layout or species_pack.json).

        // Minimum valid file sizes (small files = error HTML pages from HF)
        private val MIN_TEXT_BYTES = mapOf(
            TextModelTier.FAST          to   400_000_000L, //  400 MB — Qwen3.5-0.8B (~533 MB)
            TextModelTier.BALANCED      to   900_000_000L, //  900 MB — Qwen3.5-2B  (~1.1 GB)
            TextModelTier.QUALITY       to 2_200_000_000L, // 2.2 GB — Qwen3.5-4B  (~2.74 GB)
            TextModelTier.GEMMA4_E2B    to MIN_GEMMA4_LITERTLM_BYTES, // 2 GB — Gemma 4 E2B (~2.58 GB)
            TextModelTier.OCEANGUARD_FT to MIN_OCEANGUARD_GGUF_BYTES, // 3.0 GB — Q4_K_M GGUF (actual 3.42 GB)
        )
        private const val MIN_VISION_MODEL_BYTES = 500_000_000L // 500 MB — 2B
        private const val MIN_MMPROJ_BYTES       =  50_000_000L //  50 MB — mmproj
        private const val MIN_GEMMA4_MMPROJ_BYTES = 500_000_000L // 500 MB — Gemma 4 mmproj (~986 MB)

        private const val BUFFER_SIZE        = 8 * 1024 * 1024 // 8 MB
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS    = 60_000
    }

    private val _downloadState = MutableStateFlow<VlmDownloadState>(VlmDownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    /**
     * True while the last download asked for was the species pack, so a screen can tell its
     * progress and failure apart from those of a report or detector model (they share
     * [downloadState]).
     */
    private val _speciesPackSelected = MutableStateFlow(false)
    val speciesPackSelected = _speciesPackSelected.asStateFlow()

    @Volatile
    private var cancelled = false

    /** Latest manifest best-model entry. Populated by [refreshBestModelFromManifest]. */
    @Volatile
    private var cachedBestModel: BestModel? = null

    // -----------------------------------------------------------------------
    // Manifest-aware finetuned URL/filename helpers
    // -----------------------------------------------------------------------

    /**
     * Refreshes [cachedBestModel] from the network or DataStore-cached manifest.
     * Call once at startup (e.g. from [OceanGuardApp]) and again after a fresh manifest check.
     */
    suspend fun refreshBestModelFromManifest(
        checker: ModelUpdateChecker,
        force: Boolean = false,
    ): BestModel? {
        if (!force && cachedBestModel != null) return cachedBestModel
        val manifest = checker.fetchManifestCached() ?: return cachedBestModel
        cachedBestModel = manifest.bestModel
        return cachedBestModel
    }

    /** Dynamic finetuned filename from manifest, with hardcoded fallback for offline use. */
    fun finetunedFilename(): String =
        cachedBestModel?.filename ?: GEMMA4_LITERTLM_FINETUNED_FILENAME

    /** Dynamic finetuned download URL from manifest, with hardcoded fallback for offline use. */
    fun finetunedUrl(): String =
        cachedBestModel?.url ?: GEMMA4_LITERTLM_FINETUNED_URL

    /**
     * Latest known best-model version from the cached manifest (e.g. "1.0.0").
     * Returns null if no manifest has been fetched yet.
     */
    fun bestModelVersion(): String? = cachedBestModel?.version

    // -----------------------------------------------------------------------
    // File paths
    // -----------------------------------------------------------------------

    /** App-private external storage directory for all models. */
    fun getModelDirectory(): File =
        File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    /** Absolute path to the text model file for [tier] and [variant]. */
    fun getTextModelPath(
        tier: TextModelTier = TextModelTier.FAST,
        variant: TextModelVariant = TextModelVariant.BASE,
    ): String {
        val effectiveVariant = tier.variantFallback(variant)
        val filename = if (tier == TextModelTier.GEMMA4_E2B && effectiveVariant == TextModelVariant.FINETUNED)
            finetunedFilename()
        else
            tier.filenameFor(effectiveVariant)
        return File(getModelDirectory(), filename).absolutePath
    }

    /** Absolute path to the Qwen vision LLM GGUF file. */
    fun getVisionModelPath(): String =
        File(getModelDirectory(), LlamaVisionEngine.QWEN_MODEL_FILENAME).absolutePath

    /** Absolute path to the Qwen CLIP mmproj GGUF file. */
    fun getMmprojPath(): String =
        File(getModelDirectory(), LlamaVisionEngine.QWEN_MMPROJ_FILENAME).absolutePath

    /** Absolute path to the Gemma 4 mmproj GGUF file. */
    fun getGemma4MmprojPath(): String =
        File(getModelDirectory(), GEMMA4_MMPROJ_FILENAME).absolutePath

    // -----------------------------------------------------------------------
    // Availability checks
    // -----------------------------------------------------------------------

    /** True if the text model for [tier] and [variant] is present and has a valid size. */
    fun isModelAvailable(
        tier: TextModelTier = TextModelTier.FAST,
        variant: TextModelVariant = TextModelVariant.BASE,
    ): Boolean {
        val effectiveVariant = tier.variantFallback(variant)
        val filename = if (tier == TextModelTier.GEMMA4_E2B && effectiveVariant == TextModelVariant.FINETUNED)
            finetunedFilename()
        else
            tier.filenameFor(effectiveVariant)
        val f = File(getModelDirectory(), filename)
        val minBytes = if (effectiveVariant == TextModelVariant.FINETUNED)
            MIN_GEMMA4_LITERTLM_BYTES
        else
            MIN_TEXT_BYTES[tier] ?: 50_000_000L
        return f.exists() && f.length() > minBytes
    }

    /** True if [tier] is downloaded in any variant: a fine-tuned file is enough without the base one. */
    fun isAnyVariantAvailable(tier: TextModelTier): Boolean =
        TextModelVariant.entries.any { variant -> isModelAvailable(tier, variant) }

    /** True if any text tier/variant is downloaded (fast check for report button). */
    fun isAnyTextModelAvailable(): Boolean =
        TextModelTier.entries.any { tier ->
            TextModelVariant.entries.any { variant -> isModelAvailable(tier, variant) }
        }

    /**
     * True if the Qwen 2B vision model + mmproj are present AND native vision is compiled.
     */
    fun isVisionModelAvailable(): Boolean {
        if (!LlamaCppBridge.nativeIsVisionSupported()) return false
        val modelDir = getModelDirectory()
        val model  = File(modelDir, LlamaVisionEngine.QWEN_MODEL_FILENAME)
        val mmproj = File(modelDir, LlamaVisionEngine.QWEN_MMPROJ_FILENAME)
        return model.exists()  && model.length()  > MIN_VISION_MODEL_BYTES
            && mmproj.exists() && mmproj.length() > MIN_MMPROJ_BYTES
    }

    /**
     * True if the Gemma 4 E2B .litertlm is available (vision is built-in, no mmproj needed).
     */
    fun isGemma4VisionAvailable(): Boolean = isAnyVariantAvailable(TextModelTier.GEMMA4_E2B)

    /** True if the Gemma 4 mmproj is downloaded (text model checked via isModelAvailable). */
    fun isGemma4MmprojAvailable(): Boolean {
        val mmproj = File(getModelDirectory(), GEMMA4_MMPROJ_FILENAME)
        return mmproj.exists() && mmproj.length() > MIN_GEMMA4_MMPROJ_BYTES
    }

    // -----------------------------------------------------------------------
    // Storage: size on disk and deletion
    // -----------------------------------------------------------------------

    /** Bytes the file of [tier] and [variant] takes on disk; 0 when it is not installed. */
    fun installedBytes(
        tier: TextModelTier,
        variant: TextModelVariant = TextModelVariant.BASE,
    ): Long {
        if (!isModelAvailable(tier, variant)) return 0L
        return File(getTextModelPath(tier, variant)).length()
    }

    /**
     * Deletes the file of [tier] and [variant] plus any partial download of it. Returns the bytes
     * freed. A variant that is not what [isModelAvailable] would load (an unpublished fine-tuned
     * file resolves to the base one) is left alone: it must never delete another model's file.
     * The caller releases the engine that may have the file loaded.
     */
    fun deleteModel(tier: TextModelTier, variant: TextModelVariant = TextModelVariant.BASE): Long {
        if (tier.variantFallback(variant) != variant) return 0L
        val file = File(getTextModelPath(tier, variant))
        val partial = File(file.parent, "${file.name}.tmp")
        val freed = (if (file.exists()) file.length() else 0L) + partial.length()
        partial.delete()
        file.delete()
        Log.i(TAG, "Deleted ${file.name}: $freed bytes")
        return freed
    }

    /** Names of the files that make up the BioDex species pack. */
    val speciesFileNames: List<String> get() = SpeciesPackAssets.fileNames(getSpeciesDirectory())

    /** Bytes the species pack takes on disk. */
    fun speciesPackBytes(): Long =
        speciesFileNames.sumOf { File(getSpeciesDirectory(), it).let { f -> if (f.exists()) f.length() else 0L } }

    // -----------------------------------------------------------------------
    // Human-readable labels for download dialogs
    // -----------------------------------------------------------------------

    fun getModelSizeLabel(tier: TextModelTier = TextModelTier.FAST): String = tier.sizeLabel

    fun getVisionModelSizeLabel(): String = "~1.6 GB"

    fun getGemma4MmprojSizeLabel(): String = "~986 MB"

    // -----------------------------------------------------------------------
    // Downloads
    // -----------------------------------------------------------------------

    /**
     * Download the text model for [tier] and [variant] from HuggingFace.
     * No-op if the file is already present and valid.
     * For FINETUNED variant on non-supporting tiers, silently falls back to BASE.
     */
    suspend fun downloadModel(
        tier: TextModelTier = TextModelTier.FAST,
        variant: TextModelVariant = TextModelVariant.BASE,
    ) = withContext(Dispatchers.IO) {
            _speciesPackSelected.value = false
            val effectiveVariant = tier.variantFallback(variant)
            if (isModelAvailable(tier, effectiveVariant)) {
                _downloadState.value = VlmDownloadState.Complete
                return@withContext
            }
            cancelled = false
            val isFinetuned = tier == TextModelTier.GEMMA4_E2B && effectiveVariant == TextModelVariant.FINETUNED
            val filename = if (isFinetuned) finetunedFilename() else tier.filenameFor(effectiveVariant)
            val url = when {
                isFinetuned                          -> finetunedUrl()
                tier == TextModelTier.OCEANGUARD_FT  -> OCEANGUARD_GGUF_URL
                tier == TextModelTier.GEMMA4_E2B     -> GEMMA4_LITERTLM_URL
                tier == TextModelTier.FAST           -> TEXT_FAST_URL
                tier == TextModelTier.BALANCED       -> TEXT_BALANCED_URL
                else                                 -> TEXT_QUALITY_URL
            }
            val target = File(getModelDirectory(), filename)
            val minBytes = if (effectiveVariant == TextModelVariant.FINETUNED)
                MIN_GEMMA4_LITERTLM_BYTES
            else
                MIN_TEXT_BYTES[tier] ?: 50_000_000L
            try {
                downloadFile(url = url, target = target, minValidBytes = minBytes, filename = filename)
                if (isFinetuned) {
                    val version = cachedBestModel?.version ?: ""
                    if (version.isNotBlank()) settings?.setInstalledFinetunedVersion(version)
                }
            } catch (e: VlmDownloadException) {
                _downloadState.value = VlmDownloadState.Error(e.message ?: "Download failed", e.kind)
                throw e
            }
        }

    /**
     * Download both Qwen vision model files (2B + mmproj) from HuggingFace.
     * Progress is emitted per file; completes only when both are valid.
     */
    suspend fun downloadVisionModel() = withContext(Dispatchers.IO) {
        _speciesPackSelected.value = false
        if (isVisionModelAvailable()) {
            _downloadState.value = VlmDownloadState.Complete
            return@withContext
        }
        cancelled = false
        val modelDir = getModelDirectory()

        try {
            val model2b = File(modelDir, LlamaVisionEngine.QWEN_MODEL_FILENAME)
            if (!model2b.exists() || model2b.length() < MIN_VISION_MODEL_BYTES) {
                downloadFile(
                    url          = VISION_MODEL_URL,
                    target       = model2b,
                    minValidBytes = MIN_VISION_MODEL_BYTES,
                    filename     = LlamaVisionEngine.QWEN_MODEL_FILENAME,
                )
                if (cancelled) return@withContext
            }

            val mmproj = File(modelDir, LlamaVisionEngine.QWEN_MMPROJ_FILENAME)
            if (!mmproj.exists() || mmproj.length() < MIN_MMPROJ_BYTES) {
                downloadFile(
                    url          = MMPROJ_URL,
                    target       = mmproj,
                    minValidBytes = MIN_MMPROJ_BYTES,
                    filename     = LlamaVisionEngine.QWEN_MMPROJ_FILENAME,
                )
            }
        } catch (e: VlmDownloadException) {
            _downloadState.value = VlmDownloadState.Error(e.message ?: "Download failed", e.kind)
            throw e
        }
    }

    /**
     * Download the Gemma 4 mmproj file from HuggingFace.
     * The text model is downloaded via [downloadModel] with [TextModelTier.GEMMA4_E2B].
     */
    suspend fun downloadGemma4Mmproj() = withContext(Dispatchers.IO) {
        if (isGemma4MmprojAvailable()) {
            _downloadState.value = VlmDownloadState.Complete
            return@withContext
        }
        cancelled = false
        try {
            val target = File(getModelDirectory(), GEMMA4_MMPROJ_FILENAME)
            downloadFile(
                url           = GEMMA4_MMPROJ_URL,
                target        = target,
                minValidBytes = MIN_GEMMA4_MMPROJ_BYTES,
                filename      = GEMMA4_MMPROJ_FILENAME,
            )
        } catch (e: VlmDownloadException) {
            _downloadState.value = VlmDownloadState.Error(e.message ?: "Download failed", e.kind)
            throw e
        }
    }

    // -----------------------------------------------------------------------
    // LiteRT-LM benchmark spike
    // -----------------------------------------------------------------------

    /** Check if the Gemma 4 E2B .litertlm model is downloaded and valid for [variant]. */
    fun isLiteRTModelAvailable(variant: TextModelVariant = TextModelVariant.BASE): Boolean =
        isModelAvailable(TextModelTier.GEMMA4_E2B, variant)

    /** Absolute path to the .litertlm model file for [variant] (may not exist yet). */
    fun getLiteRTModelPath(variant: TextModelVariant = TextModelVariant.BASE): String =
        getTextModelPath(TextModelTier.GEMMA4_E2B, variant)

    /** Download Gemma 4 E2B .litertlm from HuggingFace (~2.58 GB). */
    suspend fun downloadLiteRTModel(variant: TextModelVariant = TextModelVariant.BASE) =
        downloadModel(TextModelTier.GEMMA4_E2B, variant)

    // -----------------------------------------------------------------------
    // BioDex species pack (M4)
    // -----------------------------------------------------------------------

    /** Directory holding the downloadable BioDex assets (`models/species/`). */
    fun getSpeciesDirectory(): File = File(getModelDirectory(), "species").also { it.mkdirs() }

    /** True once the encoder + index + catalog of the pack are present (raster is optional). */
    fun isSpeciesPackAvailable(): Boolean = SpeciesPackConfig.load(getSpeciesDirectory()) != null

    /**
     * Download the BioDex species pack from Hugging Face into [getSpeciesDirectory].
     * First `species_pack_v3.json`, then `species_pack.json` (encoder contract +
     * calibrated thresholds; the rc2 app only asks for the latter and keeps the v2
     * pack); when the repository has none, the published v1 layout. Skips files already
     * present and large enough. Without a pack, species identification is
     * unavailable (there is no demo fallback any more).
     */
    suspend fun downloadSpeciesPack() = withContext(Dispatchers.IO) {
        _speciesPackSelected.value = true
        cancelled = false
        try {
            val dir = getSpeciesDirectory()
            val installed = SpeciesPackConfig.load(dir)
            if (installed != null) {
                updateSpeciesPack(dir, installed)
                // Retry the artwork that failed before (only what is missing or differs is fetched).
                SpeciesPackConfig.load(dir)?.takeIf { !cancelled }?.let { downloadSpeciesOptional(dir, it) }
                _downloadState.value = VlmDownloadState.Complete
                return@withContext
            }
            val manifest = speciesManifest(dir)
            val assets = manifest?.let(SpeciesPackAssets::forConfig) ?: SpeciesPackAssets.V1
            for ((name, minBytes) in assets) {
                if (cancelled) break
                val target = File(dir, name)
                if (target.exists() && target.length() >= minBytes) continue
                downloadFile(url = "$SPECIES_REPO/$name", target = target, minValidBytes = minBytes, filename = name)
            }
            if (manifest != null && !cancelled) downloadSpeciesOptional(dir, manifest)
            _downloadState.value = VlmDownloadState.Complete
        } catch (e: VlmDownloadException) {
            _downloadState.value = VlmDownloadState.Error(e.message ?: "Species pack download failed", e.kind)
            throw e
        }
    }

    /** Version of the installed species pack, null when none is installed. */
    fun installedSpeciesPackVersion(): Int? = SpeciesPackConfig.load(getSpeciesDirectory())?.version

    /**
     * Version of the species pack published in the repository (first manifest of
     * [REMOTE_SPECIES_MANIFESTS] that exists); null when it cannot be read (offline, error).
     */
    suspend fun remoteSpeciesPackVersion(): Int? = remoteSpeciesPackConfig()?.version

    /** Installed species pack manifest, null when none is installed. */
    fun installedSpeciesPackConfig(): SpeciesPackConfig? = SpeciesPackConfig.load(getSpeciesDirectory())

    /** Size of the installed species pack file [name] (0 when missing). */
    fun installedSpeciesFileLength(name: String): Long = File(getSpeciesDirectory(), name).length()

    /** Manifest of the species pack published in the repository; null when it cannot be read (offline, error). */
    suspend fun remoteSpeciesPackConfig(): SpeciesPackConfig? = withContext(Dispatchers.IO) {
        for (name in REMOTE_SPECIES_MANIFESTS) {
            val conn = openConnection("$SPECIES_REPO/$name")
            try {
                if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) continue
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                return@withContext SpeciesPackConfig.parse(text)
            } catch (e: IOException) {
                Log.i(TAG, "Remote species manifest check failed: ${e.message}")
                return@withContext null
            } finally {
                conn.disconnect()
            }
        }
        null
    }

    /**
     * Update the installed pack to a newer remote manifest. Everything new is downloaded with the
     * [SpeciesPackUpdate.STAGE_SUFFIX] and verified (size, and SHA-256 when the manifest has it)
     * before it replaces the installed files; a failure or a cancel leaves the old pack untouched.
     */
    private suspend fun updateSpeciesPack(dir: File, installed: SpeciesPackConfig) {
        val manifestStage = File(dir, SpeciesPackConfig.MANIFEST + SpeciesPackUpdate.STAGE_SUFFIX)
        val staged = mutableListOf<Pair<File, File>>()
        try {
            fetchFirstAvailable { remote ->
                downloadFile("$SPECIES_REPO/$remote", manifestStage, 50L, manifestStage.name)
            }
            val config = manifestStage.takeIf { it.exists() && !cancelled }
                ?.let { SpeciesPackConfig.parse(it.readText(Charsets.UTF_8)) }
            if (config == null || !SpeciesPackUpdate.isNewer(installed.version, config.version)) return
            for ((name, minBytes) in SpeciesPackAssets.forConfig(config)) {
                if (cancelled) return
                val target = File(dir, name)
                val reuse = SpeciesPackUpdate.canReuse(
                    name, config, target.exists(), target.length(), minBytes,
                ) { SpeciesPackUpdate.sha256(target) }
                if (reuse) continue
                val stage = File(dir, name + SpeciesPackUpdate.STAGE_SUFFIX)
                downloadFile("$SPECIES_REPO/$name", stage, minBytes, stage.name)
                if (cancelled) return
                if (!SpeciesPackUpdate.matches(stage, config.sha256?.get(name))) {
                    throw VlmDownloadException("Checksum mismatch for $name", kind = DownloadFailureKind.INCOMPLETE)
                }
                staged += stage to target
            }
            for ((name, minBytes) in SpeciesPackAssets.optionalFor(config)) {
                if (cancelled) return
                val target = File(dir, name)
                val reuse = SpeciesPackUpdate.canReuse(
                    name, config, target.exists(), target.length(), minBytes,
                ) { SpeciesPackUpdate.sha256(target) }
                if (reuse) continue
                val stage = File(dir, name + SpeciesPackUpdate.STAGE_SUFFIX)
                if (fetchOptional(name, stage, minBytes, config.sha256?.get(name))) staged += stage to target
            }
            // All verified: swap the files in, the manifest last, then drop what the new pack no longer uses.
            for ((stage, target) in staged + (manifestStage to File(dir, SpeciesPackConfig.MANIFEST))) {
                stage.copyTo(target, overwrite = true)
            }
            SpeciesPackUpdate.orphans(installed, config).forEach { File(dir, it).delete() }
            Log.i(TAG, "Species pack updated v${installed.version} -> v${config.version}")
        } finally {
            staged.forEach { it.first.delete() }
            manifestStage.delete()
        }
    }

    /**
     * Download the optional files of [config] (species artwork) that are missing or differ from the manifest.
     * A failure only leaves that sprite on the placeholder: the pack stays usable.
     */
    private suspend fun downloadSpeciesOptional(dir: File, config: SpeciesPackConfig) {
        for ((name, minBytes) in SpeciesPackAssets.optionalFor(config)) {
            if (cancelled) return
            val target = File(dir, name)
            val ok = SpeciesPackUpdate.canReuse(
                name, config, target.exists(), target.length(), minBytes,
            ) { SpeciesPackUpdate.sha256(target) }
            if (ok) continue
            val stage = File(dir, name + SpeciesPackUpdate.STAGE_SUFFIX)
            if (fetchOptional(name, stage, minBytes, config.sha256?.get(name))) {
                stage.copyTo(target, overwrite = true)
                stage.delete()
            }
        }
    }

    /** Download the optional pack file [name] to [stage] and check it; false (nothing left) on any failure. */
    private suspend fun fetchOptional(name: String, stage: File, minBytes: Long, sha256: String?): Boolean {
        stage.parentFile?.mkdirs()
        return try {
            downloadFile("$SPECIES_REPO/$name", stage, minBytes, stage.name)
            val ok = stage.exists() && SpeciesPackUpdate.matches(stage, sha256)
            if (!ok) stage.delete()
            ok
        } catch (e: VlmDownloadException) {
            Log.w(TAG, "Optional species file $name not downloaded: ${e.message}")
            stage.delete()
            false
        }
    }

    /** Fetch and parse the remote `species_pack.json`; null (and no file left) when absent or invalid. */
    private suspend fun speciesManifest(dir: File): SpeciesPackConfig? {
        val name = SpeciesPackConfig.MANIFEST
        val target = File(dir, name)
        try {
            fetchFirstAvailable { remote -> downloadFile("$SPECIES_REPO/$remote", target, 50L, name) }
        } catch (e: VlmDownloadException) {
            // Keep a manifest installed earlier (e.g. offline retry); otherwise v1.
            Log.i(TAG, "No $name downloaded (${e.message}); using the installed one or the v1 layout")
            return target.takeIf { it.exists() }?.let { SpeciesPackConfig.parse(it.readText(Charsets.UTF_8)) }
        }
        return SpeciesPackConfig.parse(target.readText(Charsets.UTF_8)) ?: run {
            Log.w(TAG, "Invalid $name; using the v1 layout")
            target.delete()
            null
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private suspend fun downloadFile(
        url: String,
        target: File,
        minValidBytes: Long,
        filename: String,
    ) = withContext(Dispatchers.IO) {
        val temp = File(target.parent, "$filename.tmp")
        if (temp.exists() && temp.length() < minValidBytes) temp.delete()

        _downloadState.value = VlmDownloadState.Preparing
        Log.i(TAG, "Downloading $filename from $url")

        val connection = openConnection(url)
        try {
            val responseCode = connection.responseCode

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw VlmDownloadException(
                    "HTTP $responseCode downloading $filename",
                    kind = DownloadFailure.fromHttpStatus(responseCode),
                )
            }

            if (connection.contentType?.contains("text/html", ignoreCase = true) == true) {
                throw VlmDownloadException(
                    "Server returned HTML for $filename — check URL",
                    kind = DownloadFailureKind.NOT_FOUND,
                )
            }

            val contentLength = connection.contentLengthLong
            val totalBytes = if (contentLength > 0) contentLength else minValidBytes * 2

            connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                java.io.FileOutputStream(temp, false).buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L

                    // Throttle UI emissions to avoid recomposing the Home tree
                    // ~325 times per download. Emit at most 5 Hz OR whenever the
                    // integer percent changes — whichever happens first. The
                    // final-byte emit happens after the loop so 100% is never
                    // lost to throttling.
                    var lastEmitMs = 0L
                    var lastProgressPct = -1
                    val emitMinIntervalMs = 200L

                    while (isActive && !cancelled) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        val now = System.currentTimeMillis()
                        val progress = (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                        val progressPct = (progress * 100f).toInt()
                        if (now - lastEmitMs >= emitMinIntervalMs || progressPct != lastProgressPct) {
                            _downloadState.value = VlmDownloadState.Downloading(
                                progress       = progress,
                                downloadedBytes = downloaded,
                                totalBytes     = totalBytes,
                                currentFile    = filename,
                            )
                            lastEmitMs = now
                            lastProgressPct = progressPct
                        }
                    }

                    // Final emit so the UI never freezes on the second-to-last
                    // throttled snapshot before transitioning to Installing.
                    _downloadState.value = VlmDownloadState.Downloading(
                        progress       = (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f),
                        downloadedBytes = downloaded,
                        totalBytes     = totalBytes,
                        currentFile    = filename,
                    )
                }
            }
        } catch (e: IOException) {
            // A dropped connection or a full disk: leave no partial file behind and say why.
            temp.delete()
            throw VlmDownloadException(e.message ?: "I/O error", e, DownloadFailure.fromIo(e))
        } finally {
            connection.disconnect()
        }

        if (cancelled) {
            temp.delete()
            _downloadState.value = VlmDownloadState.Idle
            return@withContext
        }

        if (temp.length() < minValidBytes) {
            temp.delete()
            throw VlmDownloadException(
                "Downloaded $filename is too small (${temp.length()} bytes)",
                kind = DownloadFailureKind.INCOMPLETE,
            )
        }

        _downloadState.value = VlmDownloadState.Installing
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        _downloadState.value = VlmDownloadState.Complete
        Log.i(TAG, "$filename downloaded: ${target.length()} bytes → ${target.absolutePath}")
    }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout    = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "OceanGuardAI/1.0")
        return conn
    }

    fun cancelDownload() {
        cancelled = true
        _downloadState.value = VlmDownloadState.Idle
    }

    fun resetState() {
        _downloadState.value = if (isAnyTextModelAvailable()) {
            VlmDownloadState.Complete
        } else {
            VlmDownloadState.Idle
        }
    }
}

// ---------------------------------------------------------------------------
// Download state
// ---------------------------------------------------------------------------

sealed class VlmDownloadState {
    data object Idle : VlmDownloadState()
    data object Preparing : VlmDownloadState()
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val currentFile: String = "",
    ) : VlmDownloadState()
    data object Installing : VlmDownloadState()
    data object Complete : VlmDownloadState()
    /** [message] is for logs; the UI explains the failure from [kind]. */
    data class Error(
        val message: String,
        val kind: DownloadFailureKind = DownloadFailureKind.UNKNOWN,
    ) : VlmDownloadState()
}

class VlmDownloadException(
    message: String,
    cause: Throwable? = null,
    val kind: DownloadFailureKind = DownloadFailureKind.UNKNOWN,
) : Exception(message, cause)
