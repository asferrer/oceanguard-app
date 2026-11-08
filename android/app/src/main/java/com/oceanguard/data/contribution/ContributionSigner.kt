package com.oceanguard.ai.data.contribution

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Request signature shared with the Worker (`worker/src/crypto.js`) and the NAS
 * (`app/signing.py`): ECDSA P-256 / SHA-256 by the install's own key ([DeviceIdentity]).
 * No shared secret exists anywhere; the Worker only knows public keys.
 *
 * Canonical string, `\n`-joined, no trailing newline:
 * `OGC1`, method, path, unix seconds, nonce, payload SHA-256 hex, contribution id or `-`.
 */
object ContributionSigner {

    fun canonical(
        method: String,
        path: String,
        timestamp: Long,
        nonce: String,
        payloadSha256: String,
        contributionId: String?,
    ): ByteArray = listOf("OGC1", method.uppercase(), path, timestamp.toString(), nonce, payloadSha256, contributionId ?: "-")
        .joinToString("\n")
        .toByteArray(Charsets.UTF_8)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** First 32 hex characters of SHA-256 of the public key (SPKI DER). */
    fun clientIdOf(spki: ByteArray): String = sha256Hex(spki).take(32)

    /** DER `SEQUENCE { INTEGER r, INTEGER s }` (what Java returns) to raw 64-byte `r || s`. */
    fun derToRaw(der: ByteArray): ByteArray {
        val input = ByteArrayInputStream(der)
        require(input.read() == 0x30) { "not a DER sequence" }
        readLength(input)
        val r = readInteger(input)
        val s = readInteger(input)
        return toFixed(r) + toFixed(s)
    }

    private fun readLength(input: ByteArrayInputStream): Int {
        val first = input.read()
        if (first < 0x80) return first
        var length = 0
        repeat(first and 0x7F) { length = (length shl 8) or input.read() }
        return length
    }

    private fun readInteger(input: ByteArrayInputStream): BigInteger {
        require(input.read() == 0x02) { "not a DER integer" }
        val bytes = ByteArray(readLength(input))
        require(input.read(bytes) == bytes.size) { "truncated DER integer" }
        return BigInteger(1, bytes)
    }

    private fun toFixed(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(bytes.size <= 32) { "integer too large for P-256" }
        return ByteArray(32 - bytes.size) + bytes
    }
}
