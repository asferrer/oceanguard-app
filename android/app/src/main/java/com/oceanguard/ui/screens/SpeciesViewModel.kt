package com.oceanguard.ai.ui.screens

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oceanguard.ai.R
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.contribution.ContributionOutputs
import com.oceanguard.ai.data.contribution.ContributionRepository
import com.oceanguard.ai.data.species.SpeciesCollectionRepository
import com.oceanguard.ai.inference.species.IdentificationResult
import com.oceanguard.ai.inference.species.SpeciesIdentifier
import com.oceanguard.ai.utils.ImagePreprocessor
import com.oceanguard.ai.utils.LocationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.oceanguard.ai.service.BatchNotifications
import com.oceanguard.ai.utils.ImagePersistence
import com.oceanguard.ai.utils.ExifLocationExtractor
import java.util.Locale

// ---------------------------------------------------------------------------
// UI state — single-capture path
// ---------------------------------------------------------------------------

sealed interface SpeciesUiState {
    data object Idle : SpeciesUiState
    data object Identifying : SpeciesUiState
    /**
     * @param results        One result per detected organism (each may carry a bbox).
     * @param imageWidth     Intrinsic width (px) of the analysed full-res frame.
     * @param imageHeight    Intrinsic height (px) of the analysed full-res frame.
     *                       Needed to normalise each result's pixel bbox for the
     *                       overlay; 0 when unknown (renders box-less).
     */
    data class Complete(
        val results: List<IdentificationResult>,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
    ) : SpeciesUiState
    /** [packMissing]: the species pack is not installed, so the screen offers to download it. */
    data class Error(val message: String, val packMissing: Boolean = false) : SpeciesUiState
}

// ---------------------------------------------------------------------------
// UI state — batch (gallery multi-select) path
// ---------------------------------------------------------------------------

/** Processing state of a single image within a species batch job. */
sealed interface SpeciesBatchItemState {
    data object Pending : SpeciesBatchItemState
    data object Analyzing : SpeciesBatchItemState
    /**
     * @param results     One result per detected organism in this image.
     * @param imageWidth  Intrinsic width (px) of the analysed frame (0 = unknown).
     * @param imageHeight Intrinsic height (px) of the analysed frame (0 = unknown).
     */
    data class Done(
        val results: List<IdentificationResult>,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
    ) : SpeciesBatchItemState
    data class Failed(val message: String) : SpeciesBatchItemState
}

/** One item in the batch list exposed to the batch result screen. */
data class SpeciesBatchItem(
    val uri: Uri,
    val state: SpeciesBatchItemState = SpeciesBatchItemState.Pending,
)

sealed interface SpeciesBatchUiState {
    data object Idle : SpeciesBatchUiState
    /** Processing in progress — snapshot of the current items list. */
    data class Running(
        val items: List<SpeciesBatchItem>,
        val currentIndex: Int,
        val startTimeMs: Long,
    ) : SpeciesBatchUiState
    data class Complete(
        val items: List<SpeciesBatchItem>,
        val startTimeMs: Long,
        val endTimeMs: Long,
    ) : SpeciesBatchUiState
    data class Error(val message: String, val packMissing: Boolean = false) : SpeciesBatchUiState
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

/**
 * ViewModel for the BioDex species identification flow.
 *
 * Orchestrates:
 * 1. Image preprocessing via [ImagePreprocessor].
 * 2. Optional GPS acquisition via [LocationProvider].
 * 3. Inference via the [SpeciesIdentifier] of the installed species pack
 *    ([identifierProvider]; null when no pack is installed -> error state).
 * 4. Persistence of every confidently identified result via
 *    [SpeciesCollectionRepository.saveObservation] (only confident ones; tentative
 *    and unidentified results are shown but not added to the BioDex).
 *
 * Supports both the camera single-capture path ([identifyFromImage]) and the
 * gallery multi-select batch path ([identifyBatch]).
 */
class SpeciesViewModel(
    private val appContext: Context,
    private val identifierProvider: () -> SpeciesIdentifier?,
    private val repo: SpeciesCollectionRepository,
    private val imagePreprocessor: ImagePreprocessor,
    private val locationProvider: LocationProvider,
    /** Research contribution: with the user's consent, saved photos go up with the app's answer. */
    private val contribution: ContributionRepository? = null,
    /** Where the batch runs and keeps its state: the app's host in the app, a private one in tests. */
    private val batch: SpeciesBatchHost = SpeciesBatchHost(),
    private val packVersion: () -> Int? = { null },
) : ViewModel() {

    companion object {
        /**
         * Target "long edge" passed to [ImagePreprocessor.loadAndPreprocess] for
         * the species path. [Int.MAX_VALUE] disables down-scaling so the organism
         * locator can crop the subject bbox from the original full-resolution
         * frame (more detail on the subject, less background noise to embed).
         */
        private const val FULL_RES = Int.MAX_VALUE

        /** Results that enter the BioDex: only the confident ones (not "possible", not unidentified). */
        internal fun resultsToSave(results: List<IdentificationResult>): List<IdentificationResult> =
            results.filter { it.isConfident }
    }

    /**
     * Where the photo was taken: the GPS in its EXIF first, the phone's position only when the photo has none
     * (camera captures, or photos without location). The geographic prior and the saved observation must refer to
     * the place of the photo, not to where the user is when analysing it (a trip photo analysed at home).
     */
    private suspend fun photoLocation(uri: Uri): Location? {
        val exif = withContext(Dispatchers.IO) {
            runCatching { ExifLocationExtractor.extract(appContext, uri) }.getOrNull()
        }
        return exif ?: runCatching { locationProvider.getLastKnownLocation() }.getOrNull()
    }

    private val _uiState = MutableStateFlow<SpeciesUiState>(SpeciesUiState.Idle)
    val uiState: StateFlow<SpeciesUiState> = _uiState.asStateFlow()

    /** URI of the image currently displayed on the single-result screen. */
    private val _capturedImageUri = MutableStateFlow<Uri?>(null)
    val capturedImageUri: StateFlow<Uri?> = _capturedImageUri.asStateFlow()

    private val _batchUiState = batch.state
    val batchUiState: StateFlow<SpeciesBatchUiState> = _batchUiState.asStateFlow()

    /**
     * Run the full identification pipeline for the image at [uri].
     *
     * State transitions: Idle → Identifying → Complete | Error.
     */
    fun identifyFromImage(uri: Uri) {
        _capturedImageUri.value = uri
        viewModelScope.launch {
            _uiState.value = SpeciesUiState.Identifying
            runCatching {
                // Full-resolution load: the organism locator crops the subject
                // bbox from this frame, so down-scaling here would lose detail.
                val identifier = requireIdentifier()
                val bitmap = imagePreprocessor.loadAndPreprocess(uri, FULL_RES)
                val loc = photoLocation(uri)
                val lang = Locale.getDefault().language
                val imageWidth = bitmap.width
                val imageHeight = bitmap.height
                val results = identifier.identify(bitmap, loc, language = lang)
                saveIdentified(results, uri, loc, analysisId = System.currentTimeMillis())
                _uiState.value = SpeciesUiState.Complete(
                    results = results,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                )
            }.onFailure { e ->
                _uiState.value = SpeciesUiState.Error(
                    e.message ?: "Identification failed",
                    packMissing = e is SpeciesPackMissingException,
                )
            }
        }
    }

    /**
     * Run species identification on each URI in [uris] sequentially.
     *
     * State transitions: Idle → Running(per-item updates) → Complete | Error.
     * Each item moves through Pending → Analyzing → Done | Failed independently.
     */
    fun identifyBatch(uris: List<Uri>) {
        // One batch at a time: a second pick while one runs just shows the running one.
        if (uris.isEmpty() || batch.isRunning) return
        val items = uris.map { SpeciesBatchItem(it) }.toMutableList()
        val startTime = System.currentTimeMillis()
        // One id for the whole batch: the history shows it as a single analysis.
        val analysisId = startTime
        _batchUiState.value = SpeciesBatchUiState.Running(
            items = items.toList(),
            currentIndex = 0,
            startTimeMs = startTime,
        )

        batch.job = (batch.scope ?: viewModelScope).launch {
            try {
                runBatch(uris, items, startTime, analysisId)
            } catch (e: CancellationException) {
                // Cancelled from the notification: what was done stays, the rest is marked as cancelled.
                val cancelled = appContext.getString(R.string.species_batch_cancelled)
                _batchUiState.value = SpeciesBatchUiState.Complete(
                    items = items.map {
                        if (it.state is SpeciesBatchItemState.Pending || it.state is SpeciesBatchItemState.Analyzing) {
                            it.copy(state = SpeciesBatchItemState.Failed(cancelled))
                        } else it
                    },
                    startTimeMs = startTime,
                    endTimeMs = System.currentTimeMillis(),
                )
                throw e
            } finally {
                batch.job = null
            }
        }
        batch.onStarted()
    }

    private suspend fun runBatch(
        uris: List<Uri>,
        items: MutableList<SpeciesBatchItem>,
        startTime: Long,
        analysisId: Long,
    ) {
        run {
            // Without the pack no photo can be identified: ask for the download instead of
            // failing every photo one by one.
            if (withContext(Dispatchers.IO) { identifierProvider() } == null) {
                _batchUiState.value = SpeciesBatchUiState.Error(
                    appContext.getString(R.string.species_pack_missing), packMissing = true,
                )
                return
            }
            for ((index, uri) in uris.withIndex()) {
                items[index] = items[index].copy(state = SpeciesBatchItemState.Analyzing)
                _batchUiState.value = SpeciesBatchUiState.Running(
                    items = items.toList(),
                    currentIndex = index,
                    startTimeMs = startTime,
                )
                runCatching {
                    // Full-resolution load: see [identifyFromImage] rationale.
                    val identifier = requireIdentifier()
                    val bitmap = imagePreprocessor.loadAndPreprocess(uri, FULL_RES)
                    val imageWidth = bitmap.width
                    val imageHeight = bitmap.height
                    val loc = photoLocation(uri)
                    val lang = Locale.getDefault().language
                    val results = identifier.identify(bitmap, loc, language = lang)
                    saveIdentified(results, uri, loc, analysisId)
                    items[index] = items[index].copy(
                        state = SpeciesBatchItemState.Done(
                            results = results,
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                        ),
                    )
                }.onFailure { e ->
                    if (e is CancellationException) throw e  // a cancel must stop the batch, not fail one photo
                    items[index] = items[index].copy(
                        state = SpeciesBatchItemState.Failed(e.message ?: "Failed"),
                    )
                }
                _batchUiState.value = SpeciesBatchUiState.Running(
                    items = items.toList(),
                    currentIndex = index + 1,
                    startTimeMs = startTime,
                )
            }
            _batchUiState.value = SpeciesBatchUiState.Complete(
                items = items.toList(),
                startTimeMs = startTime,
                endTimeMs = System.currentTimeMillis(),
            )
        }
    }

    /** Identifier of the installed pack (built off the main thread), or an error naming the cause. */
    private suspend fun requireIdentifier(): SpeciesIdentifier =
        withContext(Dispatchers.IO) { identifierProvider() }
            ?: throw SpeciesPackMissingException(appContext.getString(R.string.species_pack_missing))

    /** Persist the confident results; "possible" (tentative) and unidentified ones are not added to the BioDex. */
    private suspend fun saveIdentified(results: List<IdentificationResult>, uri: Uri, loc: Location?, analysisId: Long) {
        val toSave = resultsToSave(results)
        if (toSave.isEmpty()) return
        // Keep a copy in app storage (as the debris flow does): photo-picker content:// URIs lose their read
        // permission when the app restarts, which left the BioDex gallery, history and reports without photos.
        val stored = withContext(Dispatchers.IO) { ImagePersistence.persistImage(appContext, uri) }
        toSave.forEach { result ->
            repo.saveObservation(
                result = result, imageUri = stored, thumbnailUri = null, location = loc,
                analysisId = analysisId,
            )
        }
        contribution?.maybeEnqueueSpecies(
            stored,
            results.map { ContributionOutputs.speciesOf(it, packVersion()) },
        )
    }

    /**
     * Reset both UI states back to Idle (e.g. before navigating away). A running batch is left alone: it goes on in
     * the background and its screen can be opened again from the notification.
     */
    fun resetState() {
        leaveSingleResult()
        if (batch.isRunning) return
        if (_batchUiState.value !is SpeciesBatchUiState.Idle) {
            // The results were seen and are cleared: a "batch finished" notice would now open an empty screen.
            BatchNotifications.clearFinished(appContext)
        }
        _batchUiState.value = SpeciesBatchUiState.Idle
    }

    /** Leave the single-result screen without touching the batch (it may have been opened from a batch photo). */
    fun leaveSingleResult() {
        _uiState.value = SpeciesUiState.Idle
        _capturedImageUri.value = null
    }

    /**
     * Show the result of batch photo [index] on the single-result screen, while the rest of the batch goes on.
     * @return false when that photo has no result yet.
     */
    fun openBatchItem(index: Int): Boolean {
        val items = when (val s = _batchUiState.value) {
            is SpeciesBatchUiState.Running -> s.items
            is SpeciesBatchUiState.Complete -> s.items
            else -> return false
        }
        val item = items.getOrNull(index) ?: return false
        val done = item.state as? SpeciesBatchItemState.Done ?: return false
        _capturedImageUri.value = item.uri
        _uiState.value = SpeciesUiState.Complete(done.results, done.imageWidth, done.imageHeight)
        return true
    }

    // ---------------------------------------------------------------------------
    // Factory
    // ---------------------------------------------------------------------------

    class Factory(
        private val appContext: Context,
        private val identifierProvider: () -> SpeciesIdentifier?,
        private val repo: SpeciesCollectionRepository,
        private val imagePreprocessor: ImagePreprocessor,
        private val locationProvider: LocationProvider,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SpeciesViewModel::class.java))
            return SpeciesViewModel(
                appContext = appContext,
                identifierProvider = identifierProvider,
                repo = repo,
                imagePreprocessor = imagePreprocessor,
                locationProvider = locationProvider,
            ) as T
        }
    }
}

/** The species pack is not installed (or failed to load): nothing can be identified until it is. */
class SpeciesPackMissingException(message: String) : IllegalStateException(message)
