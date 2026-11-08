package com.oceanguard.ai.data.contribution

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Same vector as contribution-api/tests/envelope_vectors.json, produced by the NAS code
 * (app/envelope.py). The phone must produce exactly these bytes for the NAS to open them.
 */
class ContributionEnvelopeTest {

    private val recipientSpki = Base64.getDecoder().decode(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEvXxzuIsum0ztpiAistqL4TGTpbVu3Cbn33hC4kzQtesGBa2nvag6xqK4DX4xQED6R/8WuDushc7bAURRu3znGg==",
    )
    private val ephemeralPrivate = "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9"
    private val contributionId = "3f2b8c1e-5d4a-4b6f-9e8d-7c6b5a493827"
    private val meta = """{"v":1,"contribution_id":"3f2b8c1e-5d4a-4b6f-9e8d-7c6b5a493827","app_version":"1.2.0",""" +
        """"annotations":{"images":[],"annotations":[],"categories":[]}}"""
    private val image = hex("ffd8ffe000104a464946000101") + "synthetic".toByteArray() + hex("ffd9")
    private val nonce = ByteArray(12) { it.toByte() }

    private fun ephemeral(x: String, y: String): KeyPair {
        val params = (ContributionEnvelope.recipientKey(recipientSpki) as ECPublicKey).params
        val kf = KeyFactory.getInstance("EC")
        val public = kf.generatePublic(ECPublicKeySpec(ECPoint(BigInteger(x, 16), BigInteger(y, 16)), params))
        val private = kf.generatePrivate(ECPrivateKeySpec(BigInteger(ephemeralPrivate, 16), params))
        return KeyPair(public, private)
    }

    @Test
    fun matchesTheNasVectorByteForByte() {
        val vectors = javaClass.classLoader!!.getResource("contribution/envelope_vectors.json")!!.readText()
        val v = com.google.gson.JsonParser.parseString(vectors).asJsonArray[0].asJsonObject
        assertEquals(meta, v["meta"].asString)
        val envelope = ContributionEnvelope.seal(
            recipientSpki, contributionId, meta, image,
            ephemeral(v["ephemeral_public_x_hex"].asString, v["ephemeral_public_y_hex"].asString), nonce,
        )
        assertEquals(v["envelope_hex"].asString, envelope.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun freshEphemeralKeyEveryTime() {
        val a = ContributionEnvelope.seal(testRecipient.public.encoded, contributionId, meta, image)
        val b = ContributionEnvelope.seal(testRecipient.public.encoded, contributionId, meta, image)
        assertNotEquals(a.toList(), b.toList())
        assertEquals(4 + 1 + 65 + 12 + 4 + meta.length + image.size + 16, a.size)
    }

    @Test
    fun recipientPrivateKeyOpensIt() {
        val envelope = ContributionEnvelope.seal(testRecipient.public.encoded, contributionId, meta, image)
        val ephPoint = envelope.copyOfRange(5, 70)
        val params = (testRecipient.public as ECPublicKey).params
        val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
            ECPoint(BigInteger(1, ephPoint.copyOfRange(1, 33)), BigInteger(1, ephPoint.copyOfRange(33, 65))), params))
        val shared = KeyAgreement.getInstance("ECDH").run { init(testRecipient.private); doPhase(peer, true); generateSecret() }
        val key = ContributionEnvelope.hkdf(
            shared,
            java.security.MessageDigest.getInstance("SHA-256").digest(testRecipient.public.encoded),
            "oceanguard-contribution-v1".toByteArray() + ephPoint, 32,
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, envelope.copyOfRange(70, 82)))
        cipher.updateAAD("OGC1".toByteArray() + byteArrayOf(1) + ephPoint + contributionId.toByteArray())
        val plain = cipher.doFinal(envelope.copyOfRange(82, envelope.size))
        assertArrayEquals(image, plain.copyOfRange(4 + meta.length, plain.size))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rsaRecipientIsRefused() {
        val rsa = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        ContributionEnvelope.recipientKey(rsa.public.encoded)
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
