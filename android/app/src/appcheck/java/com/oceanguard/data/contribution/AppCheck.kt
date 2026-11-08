package com.oceanguard.ai.data.contribution

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.tasks.await

/**
 * Firebase App Check (contract section 3b): a short-lived token, minted on the device, that
 * proves the request comes from this app. Nothing reusable is stored in the APK.
 *
 * Debug builds use the debug provider (register the token it prints in the Firebase
 * console); release builds use Play Integrity ([AppCheckFactory] per build type).
 * Any failure leaves the request without the header; the gateway decides whether to accept it.
 */
object AppCheck {
    private const val TAG = "AppCheck"
    const val AVAILABLE: Boolean = true

    @Volatile
    private var installed = false

    fun install(app: Application) {
        try {
            val firebase = FirebaseApp.getApps(app).firstOrNull() ?: FirebaseApp.initializeApp(app)
            if (firebase == null) {
                Log.w(TAG, "Firebase options missing (no google-services.json): App Check off")
                return
            }
            FirebaseAppCheck.getInstance(firebase).installAppCheckProviderFactory(AppCheckFactory.create())
            installed = true
        } catch (e: Exception) {
            Log.w(TAG, "App Check init failed (non-fatal): ${e.javaClass.simpleName}")
        }
    }

    suspend fun token(): String? {
        if (!installed) return null
        return try {
            FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
        } catch (e: Exception) {
            Log.w(TAG, "App Check token unavailable: ${e.javaClass.simpleName}")
            null
        }
    }
}
