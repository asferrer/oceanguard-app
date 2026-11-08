# OceanGuard AI -- Technical Architecture

This document describes the system architecture of OceanGuard AI, focusing on the inference pipelines, model management, GPU strategy, and data flow.

---

## System Overview

OceanGuard AI runs two independent inference subsystems on-device:

1. **Detection subsystem** -- identifies and localizes marine debris in images
2. **Text generation subsystem** -- produces environmental impact reports from detection data

Both subsystems share a common interface-based design that allows swapping implementations without changing the UI or orchestration layer.

```
+-----------------------------------------------------------------------+
|                        Android Application                            |
|                                                                       |
|  +------------------+   +--------------------+   +-----------------+  |
|  | Compose UI       |   | Foreground         |   | Room Database   |  |
|  | (screens,        |<->| Services           |<->| (sessions,      |  |
|  |  navigation,     |   | (inference,        |   |  reports,       |  |
|  |  state flows)    |   |  report gen,       |   |  MarineDex,     |  |
|  |                  |   |  VLM download)      |   |  achievements)  |  |
|  +------------------+   +--------------------+   +-----------------+  |
|           |                       |                                    |
|  +--------|--------------------------------------------+              |
|  |        v        DetectionOrchestrator               |              |
|  |  +-----------+     +-----------+     +------------+ |              |
|  |  | RT-DETRv2 |     | Gemma 4   |     | VLM Vision | |              |
|  |  | (TFLite)  |     | Vision    |     | (llama.cpp)| |              |
|  |  +-----------+     | (LiteRT)  |     +------------+ |              |
|  |                    +-----------+                    |              |
|  +-----------------------------------------------------+              |
|                                                                       |
|  +-----------------------------------------------------+              |
|  |          Text/Report Generation                     |              |
|  |  +-----------+            +-----------+             |              |
|  |  | Qwen 3.5  |            | Gemma 4   |             |              |
|  |  | (llama.cpp|            | E2B       |             |              |
|  |  |  + Vulkan)|            | (LiteRT)  |             |              |
|  |  +-----------+            +-----------+             |              |
|  +-----------------------------------------------------+              |
+-----------------------------------------------------------------------+
```

---

## Detection Pipeline

### Interface: `ObjectDetector`

All detectors implement a common interface (`DetectorInterface.kt`):

```kotlin
interface ObjectDetector {
    val displayName: String
    val inputSize: Int
    fun isReady(): Boolean
    suspend fun initialize()
    suspend fun warmUp()
    suspend fun detect(bitmap: Bitmap, confidenceThreshold: Float): List<DetectionResult>
    fun release()
}
```

Output is always a list of `DetectionResult` with **normalized [0,1] bounding boxes** in `[x1, y1, x2, y2]` format, regardless of the backend.

### Implementation 1: RT-DETRv2 (Fast Detection)

| Property | Value |
|----------|-------|
| File | `RTDETRInference.kt` |
| Model format | TFLite FP16 (83 MB, FP16 weights / FP32 compute). INT8 was discarded (the full-integer build fails when allocating tensors; the weight-only build takes 56 s per image on a PC). Detector model `exp08_real_synth_minor_s3`; conversion and VAL validation in `tools/rtdetrv2/README.md` |
| Input | 640x640 RGB, fixed size |
| Output | `pred_boxes[1,150,4]` (cxcywh) + `pred_logits[1,150,8]` |
| Postprocessing | Sigmoid on logits, threshold filter (default 0.6, chosen on VAL), cxcywh-to-xyxy, per-class NMS (IoU 0.5), max 100 |
| Delegates | NNAPI preferred, XNNPACK fallback |
| Latency | Galaxy S22 Ultra, `RTDETRBenchmarkRunner` (5 warm-up + 50 runs): median 3.98 s, p95 4.12 s with NNAPI; median 4.19 s with XNNPACK only |

**Delegate strategy** is configurable via `DelegateStrategy` enum:
- `NNAPI_PREFERRED` -- default
- `XNNPACK_ONLY` -- pure CPU, for benchmarking

**Erf-free model**: The TFLite model uses a custom ONNX surgery that replaces the `Erf` operator (unsupported by some delegates) with a `tanh` approximation, ensuring NNAPI compatibility.

### Implementation 2: Gemma 4 Vision (Deep Detection)

| Property | Value |
|----------|-------|
| File | `Gemma4VisionDetector.kt` |
| Model format | `.litertlm` (~2.6 GB, LiteRT-LM container) |
| Input | Any size (LiteRT-LM handles resizing internally) |
| Output | JSON array with `box_2d` coordinates + class labels |
| Backend | `LiteRTTextEngine` -- GPU auto-detect, CPU fallback |
| Latency | TODO: pending measurement on the Galaxy S22 |

**How it works**: Sends image + structured prompt to the VLM. Gemma 4 outputs `box_2d` coordinates on a 1000x1000 grid in `[y_min, x_min, y_max, x_max]` order. These are parsed from JSON and converted to normalized `[0,1]` `[x1, y1, x2, y2]` coordinates.

Gemma 4 does not expose token probabilities through LiteRT-LM, so the confidence is derived from how the label was resolved to a debris type (exact matches high, generic catch-alls low), plus 0.05 when the model's material agrees with the material inferred from the type, clamped to [0.30, 0.95]. It is not a calibrated probability.

See [GEMMA4_DETECTION.md](GEMMA4_DETECTION.md) for detailed prompt engineering documentation.

### DetectionOrchestrator

The orchestrator (`DetectionOrchestrator.kt`) runs one detector, with an optional second stage. There is no parallel
dual pipeline:

1. Load and preprocess the image at the input size of the active detector (640x640 for RT-DETRv2).
2. If the detector is not ready, initialize and warm it up.
3. Run the active detector once (`detect`, confidence threshold from the settings, default 0.6).
4. Optional second stage (setting "Refine with Gemma 4"): when the detector is RT-DETRv2, Gemma 4 runs on the same
   image and `DetectionRefiner` copies sub-type and material onto the RT-DETRv2 boxes it overlaps. Boxes, classes
   and confidences stay RT-DETRv2's. A failure here keeps the RT-DETRv2 output.
5. Map every detection to one of the 11 canonical debris types, compute the health score and return an
   `AnalysisResult`.

State progression exposed to the UI:

```
Idle -> LoadingImage -> WarmingUpModel (only if the model is cold) -> Detecting (RT-DETRv2)
                                                                   or AnalyzingDeep (Gemma 4 as detector or refiner)
     -> DetectionsReady -> Complete
     -> Error (from any step)
```

`InferenceService` calls the orchestrator for photos, batches and video, then saves the session in Room and checks
achievements. Report generation is a separate two-phase process, described in [FLOWS.md](FLOWS.md) together with the
photo, species and download flows.

### Detector Selection

Users choose their detector in Settings (through the analysis modes). `OceanGuardApp.getActiveDetector()` returns:

```
DetectorType.RT_DETR_V2    -> RTDETRInference (TFLite)
DetectorType.GEMMA4_VISION -> Gemma4VisionDetector (LiteRT-LM), or RT-DETRv2 if Gemma 4 is not downloaded
DetectorType.PICODET_S     -> declared in the enum, not implemented
```

`OceanGuardApp.getRefiner()` returns Gemma 4 only when the refinement setting is on, RT-DETRv2 is the active
detector and Gemma 4 is downloaded.

---

## Text/Report Generation Pipeline

### Interface: `VlmTextEngine`

```kotlin
interface VlmTextEngine {
    val displayName: String
    fun isReady(): Boolean
    suspend fun initialize(modelPath: String)
    suspend fun warmUp()
    fun release()
    suspend fun generateText(
        prompt: String,
        maxTokens: Int,
        systemMessage: String?,
        assistantPrefill: String?,
        thinkingEnabled: Boolean,
        onPartialResult: (String) -> Unit,
    ): String
}
```

Extended by `VlmVisionEngine` for multimodal (image + text) generation.

### Implementation 1: Qwen 3.5 via llama.cpp

| Property | Value |
|----------|-------|
| File | `LlamaTextEngine.kt` |
| Models | Qwen 3.5 0.8B / 2B / 4B (Q4_K_M GGUF) |
| Runtime | llama.cpp via JNI (`LlamaCppBridge`) |
| GPU | Vulkan (auto-detect, CPU fallback) |
| Thinking | Enabled for 2B+ tiers (temperature 1.0) |
| Context | 12288 tokens (prompt ~3500 + output 6144 + margin) |

Three quality tiers:

| Tier | Model | Size | Speed | Context |
|------|-------|------|-------|---------|
| Fast | Qwen 3.5 0.8B | 533 MB | TODO | 12288 |
| Balanced | Qwen 3.5 2B | 1.1 GB | TODO | 12288 |
| Quality | Qwen 3.5 4B | 2.74 GB | TODO | 12288 |

### Implementation 2: Gemma 4 E2B via LiteRT-LM

| Property | Value |
|----------|-------|
| File | `LiteRTTextEngine.kt` |
| Model | Gemma 4 E2B (5.1B params, 2.3B effective) |
| Format | `.litertlm` (~2.6 GB) |
| Runtime | LiteRT-LM `Engine` + `Conversation` API |
| GPU | `Backend.GPU()` auto-detect, `Backend.CPU()` fallback |
| Context | 5120 tokens (`MAX_TOKENS` in `LiteRTTextEngine`: prompt plus output) |
| Sampling | topK=20, topP=0.95, temperature=0.5 (text) / 0.3 (vision) |

**LiteRT-LM advantages over llama.cpp** for Gemma 4:
- Faster decode than llama.cpp on the target phone (TODO: figure pending measurement on the S22)
- Native chat template handling (no manual prompt formatting)
- Built-in vision support (image bytes sent directly, no mmproj needed)
- GPU backend via `Backend.GPU()` (driver-level optimization)

**Trade-off**: LiteRT-LM does not support assistant-turn prefilling. Workaround: the prompt includes explicit instructions to start with the required heading, and `finalizeOutput()` post-processes to ensure correct formatting.

### Tool-calling reports (Gemma 4)

With LiteRT-LM the report is written in two phases (`ToolReportGenerator`, flow in [FLOWS.md](FLOWS.md)):

1. **Tool dispatch.** `ToolAgentLoop` drives a throwaway conversation with `automaticToolCalling = false` (at most 14
   rounds). The model calls the typed Kotlin methods of `OceanGuardTools`, so every number is computed in Kotlin. There
   are nine `@Tool` methods: `getDebrisSummary`, `getMaterialBreakdown`, `getTypeBreakdown`, `getRiskAssessment`,
   `getCollectionWaypoints`, `getTemporalTrend`, `getEcologicalImpacts`, `getPerSessionDetails` and
   `getSurveyStatistics`. Seven are required for a generic report; the zone report also requires the temporal trend.
   The waypoints tool is optional. If the model tries to finish without the required tools, the draft is discarded and a
   corrective message is sent (up to three times).
2. **Prose.** A fresh conversation without tools receives the data bundle (`ToolDataBundleFormatter`) and writes the
   narrative. Tables written by the model are removed and replaced with the canonical ones, hallucinated values are
   repaired, and `ReportValidator` scores the final text against the sessions.

The llama.cpp engines (Qwen 3.5, fine-tuned GGUF) use `ReportGenerator` instead, without tools.

### Vision Generation (Multimodal)

`LiteRTTextEngine.generateWithImage()` handles multimodal input:

1. Bitmap converted to JPEG bytes (quality 90)
2. Sent as `Content.ImageBytes` alongside `Content.Text` in a `Contents` message
3. Streaming response via `MessageCallback` with 200ms throttled partial results

This is used both by `Gemma4VisionDetector` (for box_2d detection) and by `ReportGenerator` (for vision-aware reports).

---

## GPU Strategy

### Exynos 2200 Constraints

The Samsung Galaxy S22 Ultra (European variant) has specific GPU limitations:

| Feature | Status |
|---------|--------|
| Xclipse 920 (AMD RDNA2) | Available |
| TFLite GPU delegate | NOT supported |
| MediaPipe GPU | NOT supported |
| NNAPI NPU | NOT accessible (EDEN SDK not public) |
| NNAPI CPU scheduling | Works (+28% via big.LITTLE) |
| Vulkan | Supported (llama.cpp) |
| LiteRT-LM `Backend.GPU()` | Auto-probed at runtime |

### Auto-Detection Strategy

Each engine probes GPU availability at initialization and falls back gracefully:

**LiteRT-LM** (`LiteRTTextEngine.selectBackends()`):
```
try Backend.GPU() -> success: use GPU for both text and vision
                  -> exception: fall back to Backend.CPU()
```

**llama.cpp** (`LlamaCppBridge`):
```
Probe Vulkan at native layer -> success: use GPU layers
                              -> failure: CPU-only inference
```

**TFLite** (`RTDETRInference`):
```
NNAPI_PREFERRED -> NnApiDelegate() -> success: NNAPI scheduling
                                    -> failure: XNNPACK CPU
```

The active backend is exposed to the UI via `LiteRTTextEngine.activeBackendName`.

---

## Coordinate Systems

### RT-DETRv2 Output
- Raw: `[cx, cy, w, h]` normalized [0,1] (center-x, center-y, width, height)
- Converted to: `[x1, y1, x2, y2]` normalized [0,1] in Kotlin postprocessing

### Gemma 4 box_2d Output
- Raw: `[y_min, x_min, y_max, x_max]` on a 1000x1000 integer grid
- Note the **y-first** axis order (different from most object detection conventions)
- Converted to: `[x1, y1, x2, y2]` normalized [0,1] by dividing by 1000

### Pipeline-Wide Convention
All `DetectionResult` objects use **normalized [0,1] `[x1, y1, x2, y2]`** coordinates, regardless of the detector backend. This is the contract of the `ObjectDetector` interface.

---

## Memory Management

### Model Lifecycle States

```
NotLoaded -> Loading -> WarmingUp -> Ready -> Standby -> (re-load on demand)
                                           -> Error
```

### Multi-model flow (v1.0.0)
- Default: RT-DETRv2 (TFLite FP16) detects; Gemma 4 E2B writes the report (`ToolReportGenerator`, `ToolAgentLoop`).
- Optional (Settings > Detection Engine > "Refine with Gemma 4"): after RT-DETRv2, Gemma 4 runs on the same
  image and `DetectionRefiner` copies sub-type / material onto the RT-DETRv2 boxes it overlaps (IoU >= 0.3,
  same canonical family). Boxes, classes and confidences stay RT-DETRv2's. Costs one extra Gemma 4 pass.
- Gemma 4 Vision can still be selected as the detector itself. Live camera and video always use RT-DETRv2.

### Lazy Loading
- RT-DETRv2: loaded at app startup (bundled in APK, ~83 MB, fast init)
- VLM models: loaded on-demand when user triggers detection or report generation
- Gemma 4 Vision detector: lazy-initialized via Kotlin `by lazy`

### Idle Release

VLM engines are auto-released after 30 minutes of inactivity to free RAM, and immediately on memory pressure
(`onTrimMemory` at the critical levels, `onLowMemory`):

```kotlin
private const val VLM_RETAIN_MS = 30L * 60L * 1000L

// After each generation completes:
vlmReleaseJob?.cancel()
vlmReleaseJob = applicationScope.launch {
    delay(VLM_RETAIN_MS)
    releaseVlmEngine()
}
```

The long retain avoids the reload cost between reports of the same session; the memory callbacks bound it by the RAM the system actually has.

### Memory Budget (Samsung S22 Ultra, 8 GB RAM)

| Configuration | Estimated RAM |
|--------------|---------------|
| RT-DETRv2 only | ~200 MB |
| RT-DETRv2 + Qwen 0.8B | ~1.2 GB |
| RT-DETRv2 + Qwen 2B | ~1.8 GB |
| RT-DETRv2 + Gemma 4 E2B | ~3.2 GB |
| RT-DETRv2 + Qwen 4B | ~3.6 GB |

---

## Model Download and Management

`VlmModelManager` handles the full model lifecycle:

1. **Availability check**: Validates file exists and exceeds minimum size (prevents corrupt HTML error pages from HuggingFace)
2. **Download**: HTTP GET from Hugging Face, 8 MB buffer, written to `<file>.tmp` in the app-private models folder. The temporary file is overwritten, not appended to: **a download is not resumed**, it starts again. A failed or cancelled download deletes the temporary file
3. **Progress**: Exposed via `StateFlow<VlmDownloadState>` for UI progress bars
4. **Validation**: HTTP 200, a response that is not an HTML page, and a size above the minimum of that model; then the file is renamed to its final name
5. **Foreground service**: `VlmDownloadService` keeps download alive when app is backgrounded

All models are Apache 2.0 licensed and require no HuggingFace authentication.

---

## Database Schema (Room v14)

`OceanGuardDatabase` is at version 14 with exported schemas. Migrations are written by hand and never destructive, because
sessions and observations are user data.

| Entity | Purpose |
|--------|---------|
| `DetectionSession` | One analyzed image: image and thumbnail URI, debris list, count, health score, location, image quality, processing time, `modelName` |
| `GeneratedReport` | A report: text, language, audience, session ids, location and date range of the zone, validation score and details, `modelName`, and the figures it states (`figPhotos`, `figObjects`, `figHealth`) |
| `MarineDexEntry` | Collected debris types (the 11 canonical ones) |
| `Achievement` | Gamification milestones |
| `VideoAnalysis` | One analyzed video |
| `ContributionQueueItem` | Sessions queued for the optional data contribution |
| `SpeciesObservation` | One organism identified by BioDex |
| `SpeciesDexEntry` | Collected species |

Recent migrations (`DatabaseMigrations.kt`): 12 to 13 adds `modelName` to sessions and reports (which model ran; NULL on
old rows); 13 to 14 adds `figPhotos`, `figObjects` and `figHealth` to reports, so a report keeps the figures it was
written from (NULL on old reports, which fall back to the sessions that still exist).

Type converters serialize complex types (debris lists, GPS coordinates, bounding boxes) as JSON strings in Room columns.

---

## Foreground Services

| Service | Purpose | Runs When |
|---------|---------|-----------|
| `InferenceService` | Batch image analysis | User triggers multi-image detection |
| `ReportGenerationService` | VLM report generation | User requests environmental report |
| `VlmDownloadService` | Model download | User downloads a VLM model |

All services use foreground notifications to prevent Android from killing them during long-running operations (VLM inference can take 30-120s for reports).

---

## Performance Summary (Galaxy S22, Exynos 2200)

Only the RT-DETRv2 detector has been measured (in-app `RTDETRBenchmarkRunner`, 5 warm-up + 50 runs, one session, Galaxy
S22 Ultra). The other latencies are still pending measurement; no figure is claimed for them.

| Operation | Latency | Backend |
|-----------|---------|---------|
| RT-DETRv2 detection | 3.98 s median (p95 4.12 s); 4.19 s with XNNPACK only | NNAPI / XNNPACK (CPU) |
| Gemma 4 vision detection | TODO (pending) | LiteRT-LM (CPU/GPU) |
| Qwen 0.8B report (2000 words) | TODO (pending) | llama.cpp (Vulkan) |
| Qwen 2B report (2000 words) | TODO (pending) | llama.cpp (Vulkan) |
| Gemma 4 E2B report (2000 words) | TODO (pending) | LiteRT-LM (CPU/GPU) |
| Model load (RT-DETRv2) | TODO (pending) | TFLite |
| Model load (Gemma 4 E2B) | TODO (pending) | LiteRT-LM |
| Model load (Qwen 2B) | TODO (pending) | llama.cpp |
