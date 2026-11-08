package com.oceanguard.ai.data.contribution

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.Signature

/** Canonical string and signature format shared with worker/src/crypto.js and app/signing.py. */
class ContributionSignerTest {

    @Test
    fun canonicalStringIsExactlyTheSharedOne() {
        val expected = "OGC1\nPOST\n/v1/contributions\n1700000000\nn0nce\n" + "ab".repeat(32) + "\nid-1"
        assertEquals(expected, String(ContributionSigner.canonical("post", "/v1/contributions", 1_700_000_000, "n0nce", "ab".repeat(32), "id-1")))
        assertTrue(String(ContributionSigner.canonical("POST", "/v1/withdrawals", 1, "n", "00", null)).endsWith("\n-"))
    }

    @Test
    fun rawSignatureVerifiesAsStandardEcdsa() {
        val identity = SoftwareDeviceIdentity()
        val message = "OGC1\nPOST\n/v1/withdrawals".toByteArray()
        repeat(20) { // DER integers of 31, 32 and 33 bytes all come out as 64 raw bytes
            val raw = identity.sign(message)
            assertEquals(64, raw.size)
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(identity.publicKeySpki())))
            verifier.update(message)
            assertTrue(verifier.verify(rawToDer(raw)))
        }
    }

    @Test
    fun derToRawPadsShortIntegers() {
        val der = byteArrayOf(0x30, 0x08, 0x02, 0x02, 0x01, 0x02, 0x02, 0x02, 0x00, 0xFF.toByte())
        val raw = ContributionSigner.derToRaw(der)
        assertArrayEquals(ByteArray(30) + byteArrayOf(1, 2) + ByteArray(31) + byteArrayOf(0xFF.toByte()), raw)
    }

    @Test
    fun clientIdIsTheHashOfTheKey() {
        val identity = SoftwareDeviceIdentity()
        assertEquals(ContributionSigner.sha256Hex(identity.publicKeySpki()).take(32), identity.clientId)
    }

    private fun rawToDer(raw: ByteArray): ByteArray {
        fun integer(bytes: ByteArray): ByteArray {
            val value = BigInteger(1, bytes).toByteArray()
            return byteArrayOf(0x02, value.size.toByte()) + value
        }
        val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
