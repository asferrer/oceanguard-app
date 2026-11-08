package com.oceanguard.ai.data.contribution

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Same vectors as contribution-api/tests/hmac_vectors.json (checked there against the
 * gateway's own `_compute_signature`). If either side changes, both tests break.
 */
class ContributionSignerTest {

    private data class Vector(
        val keyId: String,
        val secret: String,
        val timestamp: String,
        val payloadHex: String,
        val payloadSha256: String,
        val signature: String,
    )

    private val vectors = listOf(
        Vector(
            "og-test-2026", "super-secret-test-value", "1767225600", "ffd8ffe068656c6c6f",
            "3b74e141e7a96b5f5b910aed9455d8b3eb59b69a1b0f76a6b916a28494737878",
            "b9d7d39b00ef94a61bced362300ead3f8b5556ebb586edf5eb6e455a23ff1d3d",
        ),
        Vector(
            "og-android-2026a", "dGVzdC1vbmx5LXNlY3JldC0wMDAwMDAwMDAwMDAwMDA", "1800000000", "",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "0cbd06a41f5fbe87eb8ebb823fed03b52096d7ae308a7582c22977253d30356a",
        ),
        Vector(
            "og-android-2026a", "test-only-secret_-AZaz09", "1700000000",
            "7b22636c69656e745f6964223a2230623866376331652d313131312d343232322d383333332d393434343535353536363636227d",
            "e5c98c2c08d8552d211fe579d4d4c060d157ebcd3c5e71579000bd540034b705",
            "6da17e77c1e783688392a45d4204cc563dcd01fa35e8a22562379664ed375a06",
        ),
    )

    @Test
    fun payloadDigestMatchesGatewayVectors() {
        vectors.forEach { v ->
            assertEquals(v.payloadSha256, ContributionSigner.sha256Hex(v.payloadHex.hexToBytes()))
        }
    }

    @Test
    fun signatureMatchesGatewayVectors() {
        vectors.forEach { v ->
            assertEquals(v.signature, ContributionSigner.signature(v.secret, v.keyId, v.timestamp, v.payloadSha256))
        }
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
