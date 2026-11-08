package com.oceanguard.ai.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.data.DetectionStatistics
import com.oceanguard.ai.data.collection.MarineDexEntry
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.TopDebrisTypesCard
import com.oceanguard.ai.ui.components.TopHotspotsCard
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget

/** What the Home buttons do. Built once in [HomeScreen] and shared by the sections. */
class HomeActions(
    val scan: (ScanTarget, Offset) -> Unit,
    val pick: (ScanTarget, PhotoSource) -> Unit,
    val download: (AnalysisMode) -> Unit,
)

/**
 * Selector, radar with the scan button, photo sources and mode chip. The spotlight covers the
 * radar and the sources, which is what the first tour stop explains.
 */
@Composable
fun HomeHub(
    target: ScanTarget,
    onTarget: (ScanTarget) -> Unit,
    model: HomeModelUi,
    bounds: SnapshotStateMap<String, Rect>,
    actions: HomeActions,
) {
    val enabled = HomeLogic.scanEnabled(target, model.kind)
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HomeEntry(index = 1) { ScanTargetSelector(target, onTarget) }
        Box(Modifier.spotlightTarget("home_scan_button", bounds)) {
            HomeEntry(index = 2) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ScanRadar(model.mode, target, enabled, onScan = { actions.scan(target, it) })
                    ScanSources(
                        target = target,
                        enabled = enabled,
                        onPick = { actions.pick(target, it) },
                        onDownload = actions.download,
                    )
                }
            }
        }
    }
}

@Composable
fun HomeStatusBlock(
    app: OceanGuardApp,
    model: HomeModelUi,
    bounds: SnapshotStateMap<String, Rect>,
    onDownloadReports: () -> Unit,
) {
    Box(Modifier.spotlightTarget("home_model_status", bounds)) {
        HomeEntry(index = 3) { HomeStatusLine(app, model, onDownloadReports) }
    }
}

@Composable
fun HomeStatsBlock(viewModel: MainViewModel, bounds: SnapshotStateMap<String, Rect>) {
    val totalSessions by viewModel.totalSessions.collectAsStateWithLifecycle(initialValue = 0)
    val avgHealth by viewModel.avgHealthScore.collectAsStateWithLifecycle(initialValue = null)
    val statistics by viewModel.statistics.collectAsStateWithLifecycle(
        initialValue = DetectionStatistics(0, 0, 0f, emptyMap(), emptyList(), null),
    )
    Box(Modifier.spotlightTarget("home_stats", bounds)) {
        HomeEntry(index = 4) {
            HomeStats(totalSessions, statistics.totalDebrisDetected, avgHealth)
        }
    }
}

/** MarineDex and BioDex. MarineDex carries the last stop of the Home tour. */
@Composable
fun HomeCollectionsBlock(
    dexEntries: List<MarineDexEntry>,
    discoveredCount: Int,
    bounds: SnapshotStateMap<String, Rect>,
    navController: NavController,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.spotlightTarget("home_marinedex", bounds)) {
            HomeEntry(index = 5) {
                MarineDexCard(dexEntries, discoveredCount, onClick = { navController.navigate("marinedex") })
            }
        }
        HomeEntry(index = 6) { BioDexCard(onClick = { navController.navigate("biodex") }) }
        HomeEntry(index = 7) {
        }
    }
}

/** Top debris types of the week and top hotspots. Both need at least one analysis. */
@Composable
fun HomeTopLists(viewModel: MainViewModel, navController: NavController) {
    val sessions by viewModel.allSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    if (sessions.isEmpty()) return
    val configuration = LocalConfiguration.current
    val language = remember(configuration) {
        ConfigurationCompat.getLocales(configuration).get(0)?.language?.lowercase() ?: "en"
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HomeEntry(index = 8) {
            TopDebrisTypesCard(
                sessions = sessions,
                language = language,
                // Opens the MarineDex detail on its gallery tab, with the images that detected this debris.
                onItemClick = { type -> navController.navigate("marinedex/${type.name}?tab=1") },
            )
        }
        HomeEntry(index = 9) {
            TopHotspotsCard(
                sessions = sessions,
                onHotspotClick = { lat, lon -> navController.navigate("map?lat=$lat&lon=$lon") },
            )
        }
    }
}
