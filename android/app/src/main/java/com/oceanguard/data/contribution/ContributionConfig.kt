package com.oceanguard.ai.data.contribution

import com.oceanguard.ai.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where contributions go and the HMAC key that signs them (contract section 7).
 *
 * Values come from `local.properties` through BuildConfig and are never in the repository.
 * An APK built without them has no destination: [isUsable] is false and the app does not
 * offer contribution at all (no prompt, Settings switch disabled), so nothing can stay
 * queued for a server that does not exist.
 *
 * Only `https://` is accepted, plus `http://` to the loopback address for local tests on the
 * JVM. TLS uses the system trust store; there is deliberately no custom trust manager.
 *
 * About the embedded secret: an HMAC secret inside an APK can be extracted, so it is not the
 * main defence. It gives integrity, revocation by key id and a cheap filter. The defence that
 * cannot be copied from the APK is Firebase App Check (see [AppCheck]), and the gateway must
 * run with App Check enforced for any public build.
 */
data class ContributionConfig(
    val baseUrl: String,
    val keyId: String,
    val secret: String,
) {
    /** Parsed base URL when it passes the scheme rule, else null. */
    val url: HttpUrl?
        get() = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()?.takeIf { isAllowed(it) }

    val isUsable: Boolean
        get() = url != null && keyId.isNotBlank() && secret.isNotBlank()

    override fun toString(): String = "ContributionConfig(baseUrl=$baseUrl, keyId=$keyId, secret=***)"

    companion object {
        private val LOOPBACK = setOf("localhost", "127.0.0.1")

        fun fromBuildConfig(): ContributionConfig = ContributionConfig(
            baseUrl = BuildConfig.CONTRIB_API_URL,
            keyId = BuildConfig.CONTRIB_API_KEY_ID,
            secret = BuildConfig.CONTRIB_API_SECRET,
        )

        internal fun isAllowed(url: HttpUrl): Boolean =
            url.isHttps || (url.scheme == "http" && url.host in LOOPBACK)
    }
}
