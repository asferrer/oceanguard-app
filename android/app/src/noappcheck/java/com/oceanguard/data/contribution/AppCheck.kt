package com.oceanguard.ai.data.contribution

import android.app.Application

/**
 * Build without `google-services.json`: no Firebase code in the APK and no App Check token.
 * Requests are signed with HMAC only, which is fine for local tests against a gateway with
 * `OG_APPCHECK_ENFORCE=false`, and not enough for a public release (contract section 3b).
 * The real implementation lives in `src/appcheck/java`.
 */
object AppCheck {
    const val AVAILABLE: Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun install(app: Application) = Unit

    suspend fun token(): String? = null
}
