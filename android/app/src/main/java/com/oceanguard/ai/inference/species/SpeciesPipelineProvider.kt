package com.oceanguard.ai.inference.species

import android.util.Log
import com.oceanguard.ai.data.species.SpeciesCatalog
import java.io.File

/**
 * Builds the [SpeciesIdentifier] for the species pack installed in [dir] and
 * rebuilds it when the pack changes (download, update, deletion).
 *
 * Replaces the former `by lazy` identifier, which was resolved once in
 * `MainActivity.onCreate`: when the pack was missing at that moment the whole
 * process kept a demo pipeline whose "embedder" ignored the photo
 * (`abs(bitmap.hashCode()) % 4`) and always answered one of four hard-coded
 * species (Common octopus among them), even after the real pack was downloaded.
 * Now there is no demo path: without a pack [current] returns null and the UI
 * asks for the download.
 *
 * Call [current] off the main thread (it reads the index and catalogue).
 *
 * @param dir       Pack directory (`models/species/`).
 * @param catalog   Shared catalogue instance, reloaded when the pack changes.
 * @param vlmEngine Gemma 4 adapter for the locator and the candidate choice.
 */
class SpeciesPipelineProvider(
    private val dir: () -> File,
    private val catalog: SpeciesCatalog,
    private val vlmEngine: VlmImageEngine,
    private val onMergedKeys: (Map<String, String>) -> Unit = {},
) {
    companion object {
        private const val TAG = "SpeciesPipeline"
        const val DETECTOR_FILE = "detector_yolo26n_v2.onnx"
        private const val RASTER = "meow_raster_v1.bin"
        private const val HIERARCHY = "ecoregion_hierarchy_v1.json"
    }

    private var signature: String? = null
    private var identifier: SpeciesIdentifier? = null

    /** ONNX encoder of the current pipeline (for pre-warm); null without a pack. */
    @Volatile var embedder: OnnxSpeciesEmbedder? = null
        private set

    /** Version of the installed pack (`config.version`); null without a pack. */
    @Volatile var packVersion: Int? = null
        private set

    /** Optional organism detector of the current pipeline (for pre-warm). */
    @Volatile var detector: OnnxOrganismDetector? = null
        private set

    /** Identifier for the installed pack, or null when there is none or it fails to load. */
    @Synchronized
    fun current(): SpeciesIdentifier? {
        val d = dir()
        val config = SpeciesPackConfig.load(d)
        if (config == null) {
            release()
            return null
        }
        val sig = signatureOf(d, config)
        if (sig == signature) return identifier
        release()
        packVersion = config.version
        identifier = runCatching { build(d, config) }
            .onFailure { Log.e(TAG, "Species pack present but failed to load", it) }
            .getOrNull()
        signature = sig
        return identifier
    }

    private fun build(d: File, config: SpeciesPackConfig): SpeciesIdentifier {
        catalog.reload(File(d, config.catalogFile))
        val entries = catalog.all().associateBy { it.speciesKey }
        val confidence = config.calibration.toConfidence()
        val index = SpeciesReferenceIndex(entries, confidence, config.scoring).apply { load(File(d, config.indexFile)) }
        check(index.dimension == config.encoder.dim) {
            "Index dim ${index.dimension} != encoder dim ${config.encoder.dim}"
        }
        val resolver = if (File(d, RASTER).exists() && File(d, HIERARCHY).exists()) {
            MarineRegionResolver(File(d, RASTER), File(d, HIERARCHY)).apply { load() }
        } else null
        if (config.mergedKeys.isNotEmpty()) onMergedKeys(config.mergedKeys)
        val enc = OnnxSpeciesEmbedder(
            File(d, config.encoderFile), config.encoder, config.selfTest,
            optimization = config.optimization, fullCrop = config.fullCrop,
        )
        embedder = enc
        detector = File(d, DETECTOR_FILE).takeIf { it.exists() }?.let { f ->
            runCatching { OnnxOrganismDetector(f) }.onFailure { Log.w(TAG, "Detector init failed", it) }.getOrNull()
        }
        Log.i(TAG, "Species pack v${config.version}: ${index.speciesCount} species, " +
            "dim ${index.dimension}, encoder ${config.encoderFile}, Gemma top-${config.vlmChoiceTopK}")
        return SpeciesIdentifier(
            embedder = enc,
            index = index,
            resolver = resolver,
            confidence = confidence,
            describer = SpeciesDescriber(vlmEngine),
            locator = OrganismLocator(vlmEngine, detector, fullFrameSize = config.encoder.size.takeIf { config.fullCrop }),
            vlmChoiceTopK = config.vlmChoiceTopK,
            groupFallbackThreshold = config.groupThreshold,
        )
    }

    private fun signatureOf(d: File, config: SpeciesPackConfig): String =
        (config.requiredFiles() + SpeciesPackConfig.MANIFEST + DETECTOR_FILE).joinToString("|") { name ->
            File(d, name).let { "$name:${it.length()}:${it.lastModified()}" }
        }

    private fun release() {
        packVersion = null
        embedder?.close()
        embedder = null
        detector?.close()
        detector = null
        identifier = null
        signature = null
    }
}
