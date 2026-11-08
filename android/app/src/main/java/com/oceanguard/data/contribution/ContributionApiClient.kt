package com.oceanguard.ai.data.contribution

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** What one request to the Worker ended in. */
sealed interface ApiOutcome {
    /** 200/201 to an upload. */
    data class Stored(val id: String?, val duplicate: Boolean) : ApiOutcome

    /** 202 to a withdrawal: what was still buffered is gone; the NAS removes the rest. */
    data class Withdrawn(val pendingDeleted: Int) : ApiOutcome

    /** 411/413/415/422: this photo will never be accepted. */
    data class Rejected(val httpCode: Int) : ApiOutcome

    /** Anything else: try again later. [status] says why, for Settings. */
    data class Failed(
        val status: ContributionSyncStatus,
        val httpCode: Int,
        val retryAfterSeconds: Long? = null,
    ) : ApiOutcome
}

/**
 * Client of the contribution Worker (contract v4). Every request carries:
 * - `X-Firebase-AppCheck`: short-lived token that proves the genuine app on a real device
 *   (the main control; the Worker refuses requests without it);
 * - the device public key and an ECDSA signature by the install's Keystore key over the
 *   canonical string ([ContributionSigner]) — no shared secret exists.
 *
 * Uploads carry an envelope already encrypted to the NAS ([ContributionEnvelope]).
 * TLS: system trust store, TLS 1.2+ with modern suites only, no cleartext, no redirects.
 * No certificate pinning: workers.dev certificates come from CAs Cloudflare rotates and
 * Cloudflare advises against pinning; the end-to-end encryption protects the photos even
 * if TLS were intercepted.
 */
class ContributionApiClient(
    private val config: ContributionConfig,
    private val identity: DeviceIdentity,
    private val http: OkHttpClient = defaultHttpClient(config),
    private val appCheckToken: suspend () -> String? = { AppCheck.token() },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    private val baseUrl = requireNotNull(config.url) { "contribution URL not allowed: ${config.baseUrl}" }
    private val random = SecureRandom()

    val clientId: String get() = identity.clientId

    fun resetIdentity() = identity.reset()

    /** POST /v1/contributions with an envelope sealed for [contributionId]. */
    suspend fun upload(envelope: ByteArray, contributionId: String): ApiOutcome =
        execute("/v1/contributions", envelope, OCTET, contributionId) { code, text ->
            val json = parseObject(text)
            ApiOutcome.Stored(
                id = json?.get("id")?.takeIf { it.isJsonPrimitive }?.asString,
                duplicate = json?.get("duplicate")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: (code == 200),
            )
        }

    /** POST /v1/withdrawals: deletes every photo this install sent. */
    suspend fun withdraw(): ApiOutcome =
        execute("/v1/withdrawals", "{}".toByteArray(), JSON, null) { _, text ->
            val n = parseObject(text)?.get("pending_deleted")?.takeIf { it.isJsonPrimitive }
            ApiOutcome.Withdrawn(runCatching { n?.asInt }.getOrNull() ?: 0)
        }

    private suspend fun execute(
        path: String,
        body: ByteArray,
        type: okhttp3.MediaType,
        contributionId: String?,
        onSuccess: (Int, String) -> ApiOutcome,
    ): ApiOutcome {
        val token = appCheckToken()
        return withContext(Dispatchers.IO) {
            val timestamp = nowSeconds()
            val nonce = ByteArray(12).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val payloadSha = ContributionSigner.sha256Hex(body)
            val message = ContributionSigner.canonical("POST", path, timestamp, nonce, payloadSha, contributionId)
            val request = Request.Builder()
                .url(baseUrl.newBuilder().encodedPath(path).build())
                .header("X-OG-Device-Key", B64URL.encodeToString(identity.publicKeySpki()))
                .header("X-OG-Timestamp", timestamp.toString())
                .header("X-OG-Nonce", nonce)
                .header("X-OG-Payload-SHA256", payloadSha)
                .header("X-OG-Signature", B64URL.encodeToString(identity.sign(message)))
                .apply {
                    if (contributionId != null) header("X-OG-Contribution-Id", contributionId)
                    if (token != null) header("X-Firebase-AppCheck", token)
                }
                .post(body.toRequestBody(type))
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.code in 200..202) {
                        onSuccess(response.code, text)
                    } else {
                        classifyFailure(response.code, text, response.header("Retry-After"))
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Request to $path failed: ${e.javaClass.simpleName}")
                ApiOutcome.Failed(ContributionSyncStatus.NETWORK, 0)
            }
        }
    }

    companion object {
        private const val TAG = "ContribApiClient"
        private val OCTET = "application/octet-stream".toMediaType()
        private val JSON = "application/json".toMediaType()
        private val B64URL = Base64.getUrlEncoder().withoutPadding()

        fun defaultHttpClient(config: ContributionConfig): OkHttpClient = OkHttpClient.Builder()
            .connectionSpecs(listOf(if (config.isLoopback) ConnectionSpec.CLEARTEXT else ConnectionSpec.RESTRICTED_TLS))
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        private fun parseObject(text: String): JsonObject? =
            runCatching { JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()

        /** Maps a non-success answer to the contract's categories (section 4). */
        internal fun classifyFailure(code: Int, body: String, retryAfter: String?): ApiOutcome {
            val error = parseObject(body)?.get("error")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val wait = retryAfter?.trim()?.toLongOrNull()
            return when {
                code == 411 || code == 413 || code == 415 || code == 422 -> ApiOutcome.Rejected(code)
                code == 401 && error == "attestation" -> ApiOutcome.Failed(ContributionSyncStatus.ATTESTATION, code)
                code == 401 -> ApiOutcome.Failed(ContributionSyncStatus.AUTH, code)
                code == 429 -> ApiOutcome.Failed(ContributionSyncStatus.RATE_LIMITED, code, wait)
                code == 503 && error == "quota" -> ApiOutcome.Failed(ContributionSyncStatus.QUOTA, code, wait)
                else -> ApiOutcome.Failed(ContributionSyncStatus.SERVER, code)
            }
        }
    }
}
