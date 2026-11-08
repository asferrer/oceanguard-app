package com.oceanguard.ai.ui.screens.models

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.inference.LiteRTBenchmark
import com.oceanguard.ai.inference.LlamaCppBenchmark
import com.oceanguard.ai.inference.RTDETRBenchmarkRunner
import com.oceanguard.ai.inference.TextModelTier
import com.oceanguard.ai.ui.components.sonar.ButtonProgress
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.components.sonar.SonarProgressButton
import com.oceanguard.ai.ui.components.sonar.SonarSection
import com.oceanguard.ai.ui.theme.SonarTheme
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

private const val TAG = "Benchmarks"
private val BLOCK_GAP = 8.dp
private val SECTION_GAP = 20.dp

/** Progress and output of one benchmark run. */
@Stable
class BenchState {
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
    var result by mutableStateOf<String?>(null)
        private set

    /** Runs [block], keeping its text as the result; a failure becomes text built by [failed]. */
    suspend fun run(failed: (String) -> String, block: suspend BenchState.() -> String) {
        running = true
        result = null
        try {
            result = block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Benchmark failed", e)
            result = failed(e.message.orEmpty())
        } finally {
            running = false
            status = null
        }
    }
}

/** The three developer benchmarks, unchanged in what they measure. */
@Composable
fun BenchmarksSection(
    app: OceanGuardApp,
    downloader: ModelDownloader,
    ui: DownloadUi,
    modifier: Modifier = Modifier,
) {
    SonarSection(stringResource(R.string.models_bench_title), modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
            LiteRtBenchmark(app, downloader, ui)
            LlamaBenchmark(app)
            DetectorBenchmark(app)
        }
    }
}

@Composable
private fun LiteRtBenchmark(app: OceanGuardApp, downloader: ModelDownloader, ui: DownloadUi) {
    val manager = app.vlmModelManager
    val scope = rememberCoroutineScope()
    val state = remember { BenchState() }
    val ready = manager.isLiteRTModelAvailable()
    val running = stringResource(R.string.models_bench_running)
    val failed = rememberBenchFailure()
    BenchmarkCard(
        title = stringResource(R.string.models_bench_litert_title),
        description = stringResource(
            if (ready) R.string.models_bench_litert_ready else R.string.models_bench_litert_missing,
        ),
        state = state,
    ) {
        if (ready) {
            SonarButton(
                text = stringResource(R.string.models_bench_litert_run),
                onClick = {
                    scope.launch {
                        state.run(failed) {
                            status = running
                            runLiteRt(app)
                        }
                    }
                },
                style = SonarButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ModelDownloadButton(
                model = CatalogModel.GEMMA_BASE,
                idleLabel = stringResource(
                    R.string.models_download_with_size,
                    formatBytes(CatalogModel.GEMMA_BASE.approxBytes),
                ),
                ui = ui,
                onDownload = { downloader.request(CatalogModel.GEMMA_BASE) },
                onCancel = { manager.cancelDownload() },
            )
        }
    }
}

private suspend fun runLiteRt(app: OceanGuardApp): String {
    val manager = app.vlmModelManager
    val cacheDir = File(manager.getModelDirectory(), "litert-cache").apply { mkdirs() }
    val results = LiteRTBenchmark.runAll(
        modelPath = manager.getLiteRTModelPath(),
        cacheDir = cacheDir.absolutePath,
    )
    return results.joinToString("\n") { it.toString() }
}

@Composable
private fun LlamaBenchmark(app: OceanGuardApp) {
    val manager = app.vlmModelManager
    val scope = rememberCoroutineScope()
    val state = remember { BenchState() }
    val tierKey by app.settingsRepository.vlmModelTier.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_VLM_MODEL_TIER,
    )
    val tier = TextModelTier.fromKey(tierKey)
    val available = manager.isModelAvailable(tier)
    val running = stringResource(R.string.models_bench_running)
    val failed = rememberBenchFailure()
    BenchmarkCard(
        title = stringResource(R.string.models_bench_llama_title),
        description = stringResource(R.string.models_bench_llama_desc),
        state = state,
    ) {
        SonarButton(
            text = if (available) {
                stringResource(R.string.models_bench_llama_run, tier.displayName)
            } else {
                stringResource(R.string.models_bench_llama_missing)
            },
            onClick = {
                scope.launch {
                    state.run(failed) {
                        status = running
                        LlamaCppBenchmark.run(
                            modelPath = File(manager.getModelDirectory(), tier.filename).absolutePath,
                            tier = tier,
                        ).toString()
                    }
                }
            },
            enabled = available,
            style = SonarButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DetectorBenchmark(app: OceanGuardApp) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val state = remember { BenchState() }
    val running = stringResource(R.string.models_bench_detector_running)
    val failed = rememberBenchFailure()
    BenchmarkCard(
        title = stringResource(R.string.models_bench_detector_title),
        description = stringResource(R.string.models_bench_detector_desc),
        state = state,
    ) {
        SonarButton(
            text = stringResource(R.string.models_bench_detector_run),
            onClick = {
                scope.launch {
                    state.run(failed) {
                        status = running
                        val results = RTDETRBenchmarkRunner.runAll(context)
                        val p50 = results.firstOrNull()?.p50Ms?.toString() ?: "--"
                        Toast.makeText(
                            context,
                            resources.getString(R.string.models_bench_detector_toast, p50),
                            Toast.LENGTH_LONG,
                        ).show()
                        results.joinToString("\n") { r ->
                            "${r.modelLabel}: p50=${r.p50Ms}ms p95=${r.p95Ms}ms mean=${r.meanMs}ms [${r.delegateLabel}]"
                        }
                    }
                }
            },
            style = SonarButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Builds the text shown when a benchmark throws, from the exception message. */
@Composable
private fun rememberBenchFailure(): (String) -> String {
    val resources = LocalResources.current
    return remember(resources) { { message -> resources.getString(R.string.models_bench_failed, message) } }
}

/** Title, what it measures, its action (or progress while it runs) and the output as text. */
@Composable
private fun BenchmarkCard(
    title: String,
    description: String,
    state: BenchState,
    action: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
        FieldLabel(title, description)
        if (state.running) {
            SonarProgressButton(
                text = state.status.orEmpty(),
                progress = ButtonProgress.Indeterminate,
                onClick = {},
            )
        } else {
            action()
        }
        state.result?.let { text ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    text = text,
                    style = SonarTheme.type.dataSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(BLOCK_GAP),
                )
            }
        }
    }
}
