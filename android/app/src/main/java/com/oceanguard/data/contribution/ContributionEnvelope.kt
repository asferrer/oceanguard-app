package com.oceanguard.ai.data.contribution

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts a contribution to the NAS public key before it leaves the phone, so the
 * Cloudflare buffer (and anyone with access to it) only ever holds ciphertext. Format v1,
 * byte for byte the same as `contribution-api/app/envelope.py` (checked with its vectors):
 *
 * `"OGC1" | 0x01 | ephemeral P-256 point (65) | nonce (12) | AES-256-GCM(plain) + tag`,
 * key = HKDF-SHA256(ECDH x, salt = SHA-256(recipient SPKI), info = label | ephemeral point),
 * AAD = magic | version | ephemeral point | contribution id,
 * plain = u32(len(meta)) | meta JSON | JPEG.
 */
object ContributionEnvelope {

    private val MAGIC = "OGC1".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private val INFO = "oceanguard-contribution-v1".toByteArray(Charsets.US_ASCII)
    private const val TAG_BITS = 128

    fun recipientKey(spki: ByteArray): PublicKey {
        val key = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        } catch (e: java.security.GeneralSecurityException) {
            throw IllegalArgumentException("recipient is not an EC public key", e)
        }
        require(key is ECPublicKey && key.params.curve.field.fieldSize == 256) { "recipient must be an EC P-256 key" }
        return key
    }

    fun seal(
        recipientSpki: ByteArray,
        contributionId: String,
        metaJson: String,
        image: ByteArray,
        ephemeral: KeyPair = newEphemeral(),
        nonce: ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) },
    ): ByteArray {
        val recipient = recipientKey(recipientSpki)
        val ephPoint = uncompressedPoint(ephemeral.public as ECPublicKey)
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(ephemeral.private)
            doPhase(recipient, true)
            generateSecret()
        }
        val key = hkdf(shared, MessageDigest.getInstance("SHA-256").digest(recipientSpki), INFO + ephPoint, 32)
        val meta = metaJson.toByteArray(Charsets.UTF_8)
        val plain = ByteBuffer.allocate(4 + meta.size + image.size).putInt(meta.size).put(meta).put(image).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(MAGIC + VERSION + ephPoint + contributionId.toByteArray(Charsets.UTF_8))
        return MAGIC + VERSION + ephPoint + nonce + cipher.doFinal(plain)
    }

    fun newEphemeral(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun uncompressedPoint(key: ECPublicKey): ByteArray =
        byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)

    private fun fixed(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - bytes.size) + bytes
    }

    /** RFC 5869 HKDF with HMAC-SHA256. */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        var block = ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        var counter = 1
        while (out.size() < length) {
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }
}
