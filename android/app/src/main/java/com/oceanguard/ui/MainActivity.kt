package com.oceanguard.ai.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.service.InferenceService
import com.oceanguard.ai.ui.navigation.OceanApp
import com.oceanguard.ai.ui.screens.SpeciesViewModel
import com.oceanguard.ai.ui.theme.OceanGuardTheme

/**
 * MainActivity — single-activity host for the OceanGuard AI application.
 */
class MainActivity : AppCompatActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // No-op: notifications are optional. Inference still works without them,
            // but the persistent progress notification won't show on Android 13+.
        }

    /** Pending deep-link route from notification — mutable so setContent can observe it. */
    private var _pendingDeepLink = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val route = intent.getStringExtra(InferenceService.EXTRA_DEEP_LINK_ROUTE)
        if (!route.isNullOrEmpty() && route != "home") {
            _pendingDeepLink.value = route
        }
    }

    /**
     * Intercept VOLUME_UP / VOLUME_DOWN while the camera viewfinder is active so
     * the hardware volume rocker doubles as a physical shutter button — the
     * familiar behaviour of most stock camera apps. CameraScreen toggles the
     * [OceanGuardApp.consumeVolumeKeysForCapture] flag on/off as it mounts and
     * unmounts; the rest of the app keeps the default volume behaviour.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val app = application as? OceanGuardApp
        if (app != null && app.consumeVolumeKeysForCapture) {
            val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
            if (isVolumeKey) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    app.requestVolumeShutter()
                }
                // Consume both DOWN and UP so the system volume UI never appears.
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request POST_NOTIFICATIONS on Android 13+ for the foreground service notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val app = application as OceanGuardApp

        // Apply saved app language (empty = follow system)
        val savedLocale = app.settingsRepository.getAppLanguageSync()
        if (savedLocale.isNotEmpty()) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(savedLocale)
            )
        }

        val viewModel = MainViewModel(applicationContext, app.detectionOrchestrator, app.repository, app.settingsRepository, app.locationProvider)
        val speciesViewModel = SpeciesViewModel(applicationContext, { app.speciesIdentifierOrNull() }, app.speciesCollectionRepository, app.speciesImagePreprocessor, app.locationProvider, app.contributionRepository, app.speciesBatchHost) { app.speciesPipeline.packVersion }

        // Deep-link route from notification click (initial intent + onNewIntent)
        _pendingDeepLink.value = intent?.getStringExtra(InferenceService.EXTRA_DEEP_LINK_ROUTE)

        setContent {
            val darkMode by app.settingsRepository.darkMode.collectAsStateWithLifecycle(
                initialValue = SettingsRepository.DEFAULT_DARK_MODE,
            )
            OceanGuardTheme(useDarkTheme = darkMode) {
                OceanApp(
                    app = app,
                    viewModel = viewModel,
                    speciesViewModel = speciesViewModel,
                    pendingDeepLink = _pendingDeepLink,
                )
            }
        }
    }
}
