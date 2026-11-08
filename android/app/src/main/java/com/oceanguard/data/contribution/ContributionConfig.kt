package com.oceanguard.ai.data.contribution

import com.oceanguard.ai.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

/**
 * Where contributions go and who can read them (contract v4).
 *
 * - [baseUrl]: the Cloudflare Worker (`https://<name>.<account>.workers.dev`, free plan).
 * - [recipientKey]: the NAS public key (SPKI, base64). Photos are encrypted to it on the
 *   phone; only the NAS private key opens them.
 *
 * Both are public values (no secret is embedded in the APK); they come from
 * `local.properties` through BuildConfig and are not in the repository. Without them the
 * APK has no destination: [isUsable] is false and the app does not offer contribution.
 *
 * Only `https://` is accepted, plus `http://` to the loopback address for local tests.
 */
data class ContributionConfig(
    val baseUrl: String,
    val recipientKey: String,
) {
    val url: HttpUrl?
        get() = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()?.takeIf { isAllowed(it) }

    /** Recipient SPKI DER, or null when missing or not an EC P-256 key. */
    val recipientSpki: ByteArray?
        get() = runCatching {
            Base64.getDecoder().decode(recipientKey.trim()).also { ContributionEnvelope.recipientKey(it) }
        }.getOrNull()

    val isLoopback: Boolean get() = url?.let { it.host in LOOPBACK } ?: false

    val isUsable: Boolean get() = url != null && recipientSpki != null

    companion object {
        private val LOOPBACK = setOf("localhost", "127.0.0.1")

        fun fromBuildConfig(): ContributionConfig = ContributionConfig(
            baseUrl = BuildConfig.CONTRIB_API_URL,
            recipientKey = BuildConfig.CONTRIB_RECIPIENT_KEY,
        )

        internal fun isAllowed(url: HttpUrl): Boolean =
            url.isHttps || (url.scheme == "http" && url.host in LOOPBACK)
    }
}
