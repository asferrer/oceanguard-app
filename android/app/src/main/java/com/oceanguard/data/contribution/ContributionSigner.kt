package com.oceanguard.ai.data.contribution

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 request signature of the contribution gateway (contract section 3).
 *
 * Signing string, `\n`-joined with no trailing newline:
 * `<key_id>\n<timestamp>\n<payload_sha256_hex>`, where the payload is the image bytes for an
 * upload and the raw JSON body for a withdrawal. Checked against the gateway's own vectors
 * (`contribution-api/tests/hmac_vectors.json`) in ContributionSignerTest.
 */
object ContributionSigner {

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun signature(secret: String, keyId: String, timestamp: String, payloadSha256: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("$keyId\n$timestamp\n$payloadSha256".toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
