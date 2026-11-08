package com.oceanguard.ai.data.contribution

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What one request to the gateway ended in. */
sealed interface ApiOutcome {
    /** 200/201 to an upload. */
    data class Stored(val id: String?, val duplicate: Boolean) : ApiOutcome

    /** 200 to a withdrawal. */
    data class Withdrawn(val count: Int) : ApiOutcome

    /** 413/415/422: this photo will never be accepted. */
    data class Rejected(val httpCode: Int) : ApiOutcome

    /** Anything else: try again later. [status] says why, for Settings. */
    data class Failed(
        val status: ContributionSyncStatus,
        val httpCode: Int,
        val retryAfterSeconds: Long? = null,
    ) : ApiOutcome
}

/**
 * Client of the OceanGuard contribution gateway (contribution-api, contract v3).
 *
 * Every request carries the HMAC headers (section 3) and, when the build has App Check, the
 * `X-Firebase-AppCheck` token (section 3b). TLS is OkHttp's default: system trust store and
 * hostname verification, no exceptions.
 */
class ContributionApiClient(
    private val config: ContributionConfig,
    private val http: OkHttpClient = defaultHttpClient(),
    private val appCheckToken: suspend () -> String? = { AppCheck.token() },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    private val baseUrl = requireNotNull(config.url) { "contribution URL not allowed: ${config.baseUrl}" }

    /** POST /v1/contributions with the photo exactly as given (EXIF included). */
    suspend fun upload(
        image: ByteArray,
        fileName: String,
        annotationsJson: String,
        clientId: String,
        appVersion: String,
        capturedAt: String?,
    ): ApiOutcome {
        val sha = ContributionSigner.sha256Hex(image)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", fileName, image.toRequestBody(JPEG))
            .addFormDataPart("annotations", "$fileName.json", annotationsJson.toByteArray().toRequestBody(JSON))
            .addFormDataPart("image_sha256", sha)
            .addFormDataPart("client_id", clientId)
            .addFormDataPart("app_version", appVersion)
            .apply { if (capturedAt != null) addFormDataPart("captured_at", capturedAt) }
            .build()
        return execute("v1/contributions", body, sha) { code, text ->
            val json = parseObject(text)
            ApiOutcome.Stored(
                id = json?.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() },
                duplicate = json?.get("duplicate")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: (code == 200),
            )
        }
    }

    /** POST /v1/withdrawals: deletes this install's photos (all, or one by server id). */
    suspend fun withdraw(clientId: String, contributionId: String? = null): ApiOutcome {
        val json = JsonObject().apply {
            addProperty("client_id", clientId)
            if (contributionId != null) addProperty("contribution_id", contributionId)
        }
        val raw = json.toString().toByteArray(Charsets.UTF_8)
        return execute("v1/withdrawals", raw.toRequestBody(JSON), ContributionSigner.sha256Hex(raw)) { _, text ->
            val count = parseObject(text)?.get("withdrawn")?.takeIf { it.isJsonPrimitive }
            ApiOutcome.Withdrawn(runCatching { count?.asInt }.getOrNull() ?: 0)
        }
    }

    private suspend fun execute(
        path: String,
        body: RequestBody,
        payloadSha: String,
        onSuccess: (Int, String) -> ApiOutcome,
    ): ApiOutcome {
        val token = appCheckToken()
        val timestamp = nowSeconds().toString()
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments(path).build())
            .header("X-OG-Key-Id", config.keyId)
            .header("X-OG-Timestamp", timestamp)
            .header("X-OG-Signature", ContributionSigner.signature(config.secret, config.keyId, timestamp, payloadSha))
            .apply { if (token != null) header("X-Firebase-AppCheck", token) }
            .post(body)
            .build()
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.code == 200 || response.code == 201) {
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
        private val JPEG = "image/jpeg".toMediaType()
        private val JSON = "application/json".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        private fun parseObject(text: String): JsonObject? =
            runCatching { JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()

        /** Maps a non-success answer to the contract's categories (section 4). */
        internal fun classifyFailure(code: Int, body: String, retryAfter: String?): ApiOutcome {
            val error = parseObject(body)?.get("error")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            return when (code) {
                413, 415, 422 -> ApiOutcome.Rejected(code)
                401 -> ApiOutcome.Failed(
                    if (error == "attestation") ContributionSyncStatus.ATTESTATION else ContributionSyncStatus.AUTH,
                    code,
                )
                429 -> ApiOutcome.Failed(ContributionSyncStatus.RATE_LIMITED, code, retryAfter?.trim()?.toLongOrNull())
                else -> ApiOutcome.Failed(ContributionSyncStatus.SERVER, code)
            }
        }
    }
}
