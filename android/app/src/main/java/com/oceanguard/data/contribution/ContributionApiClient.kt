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
 * Client of the contribution Worker (contract v4). Identity and attestation both come from
 * the install's Android Keystore key ([DeviceIdentity]); no shared secret exists and no
 * Google service is involved, so it works for the APK distributed through GitHub:
 *
 * - once (and again when the Worker says `unregistered`), POST /v1/devices sends the key's
 *   attestation certificate chain; the Worker checks it up to a Google attestation root and
 *   requires this app, signed by the author, on a locked device with verified boot;
 * - every request carries the device public key and an ECDSA signature over the canonical
 *   string ([ContributionSigner]).
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
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val partBytes: Int = PART_BYTES,
) {
    private val baseUrl = requireNotNull(config.url) { "contribution URL not allowed: ${config.baseUrl}" }
    private val random = SecureRandom()

    @Volatile
    private var registered = false

    val clientId: String get() = identity.clientId

    fun resetIdentity() {
        identity.reset()
        registered = false
    }

    /**
     * Uploads an envelope sealed for [contributionId] in parts of [partBytes] (one D1 row each,
     * contract v4 §5), then commits. Every request is signed over the SHA-256 of the WHOLE
     * envelope plus the path with `?parts=&bytes=`, so the NAS can re-check the result.
     */
    suspend fun upload(envelope: ByteArray, contributionId: String): ApiOutcome {
        if (envelope.size > MAX_ENVELOPE_BYTES) return ApiOutcome.Rejected(413)
        val sha = ContributionSigner.sha256Hex(envelope)
        val parts = (envelope.size + partBytes - 1) / partBytes
        val query = "parts=$parts&bytes=${envelope.size}"
        for (n in 0 until parts) {
            val slice = envelope.copyOfRange(n * partBytes, minOf(envelope.size, (n + 1) * partBytes))
            val answer = attested { send("/v1/contributions/$contributionId/parts/$n", query, slice, sha, OCTET, contributionId) }
            if (answer !is Answer.Ok || answer.code != 202) return stored(answer)
        }
        return stored(attested { send("/v1/contributions/$contributionId/commit", query, ByteArray(0), sha, OCTET, contributionId) })
    }

    /** POST /v1/withdrawals: deletes every photo this install sent. */
    suspend fun withdraw(): ApiOutcome {
        val body = "{}".toByteArray()
        return when (val answer = attested { send("/v1/withdrawals", null, body, ContributionSigner.sha256Hex(body), JSON, null) }) {
            is Answer.Ok -> {
                val n = parseObject(answer.text)?.get("pending_deleted")?.takeIf { it.isJsonPrimitive }
                ApiOutcome.Withdrawn(runCatching { n?.asInt }.getOrNull() ?: 0)
            }
            is Answer.Refused -> answer.outcome
        }
    }

    /** Registers first if needed; re-registers once if the Worker forgot this device. */
    private suspend fun attested(request: suspend () -> Answer): Answer {
        if (!registered) register()?.let { return it }
        val answer = request()
        if (answer is Answer.Refused && answer.error == "unregistered") {
            registered = false
            register()?.let { return it }
            return request()
        }
        return answer
    }

    /** POST /v1/devices with the attestation chain; null when registered. */
    private suspend fun register(): Answer? {
        val chain = withContext(Dispatchers.IO) { identity.attestationChain() }
        val body = JsonObject().apply {
            add("chain", com.google.gson.JsonArray().apply { chain.forEach { add(Base64.getEncoder().encodeToString(it)) } })
        }.toString().toByteArray()
        return when (val answer = send("/v1/devices", null, body, ContributionSigner.sha256Hex(body), JSON, null)) {
            is Answer.Ok -> {
                registered = true
                null
            }
            is Answer.Refused -> {
                Log.w(TAG, "Device registration refused: ${answer.error}")
                answer
            }
        }
    }

    private sealed interface Answer {
        data class Ok(val code: Int, val text: String) : Answer
        data class Refused(val outcome: ApiOutcome, val error: String = "") : Answer
    }

    private fun stored(answer: Answer): ApiOutcome = when (answer) {
        is Answer.Refused -> answer.outcome
        is Answer.Ok -> {
            val json = parseObject(answer.text)
            ApiOutcome.Stored(
                id = json?.get("id")?.takeIf { it.isJsonPrimitive }?.asString,
                duplicate = json?.get("duplicate")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: (answer.code == 200),
            )
        }
    }

    private suspend fun send(
        path: String,
        query: String?,
        body: ByteArray,
        payloadSha: String,
        type: okhttp3.MediaType,
        contributionId: String?,
    ): Answer = withContext(Dispatchers.IO) {
        val timestamp = nowSeconds()
        val nonce = ByteArray(12).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val signedPath = if (query == null) path else "$path?$query"
        val message = ContributionSigner.canonical("POST", signedPath, timestamp, nonce, payloadSha, contributionId)
        val request = Request.Builder()
            .url(baseUrl.newBuilder().encodedPath(path).encodedQuery(query).build())
            .header("X-OG-Device-Key", B64URL.encodeToString(identity.publicKeySpki()))
            .header("X-OG-Timestamp", timestamp.toString())
            .header("X-OG-Nonce", nonce)
            .header("X-OG-Payload-SHA256", payloadSha)
            .header("X-OG-Signature", B64URL.encodeToString(identity.sign(message)))
            .post(body.toRequestBody(type))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code in 200..202) {
                    Answer.Ok(response.code, text)
                } else {
                    val error = parseObject(text)?.get("error")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    Answer.Refused(classifyFailure(response.code, text, response.header("Retry-After")), error)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Request to $path failed: ${e.javaClass.simpleName}")
            Answer.Refused(ApiOutcome.Failed(ContributionSyncStatus.NETWORK, 0))
        }
    }

    companion object {
        private const val TAG = "ContribApiClient"

        /** Part size the Worker expects: one D1 row (2 000 000-byte limit) per part. */
        const val PART_BYTES = 1_900_000

        /** Largest envelope the Worker accepts (a 108 MP JPEG fits); larger photos are not sent. */
        const val MAX_ENVELOPE_BYTES = 40 * 1024 * 1024 + 4096
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
                code == 401 && (error == "attestation" || error == "unregistered") ->
                    ApiOutcome.Failed(ContributionSyncStatus.ATTESTATION, code)
                code == 401 -> ApiOutcome.Failed(ContributionSyncStatus.AUTH, code)
                code == 429 -> ApiOutcome.Failed(ContributionSyncStatus.RATE_LIMITED, code, wait)
                code == 503 && error == "quota" -> ApiOutcome.Failed(ContributionSyncStatus.QUOTA, code, wait)
                else -> ApiOutcome.Failed(ContributionSyncStatus.SERVER, code)
            }
        }
    }
}
