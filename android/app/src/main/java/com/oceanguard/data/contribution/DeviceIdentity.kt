package com.oceanguard.ai.data.contribution

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Identity of this install towards the contribution Worker: an EC P-256 key pair.
 * The anonymous contributor id is derived from the public key, so it cannot be spoofed,
 * and only this phone can withdraw its photos.
 */
interface DeviceIdentity {
    /** Public key as SubjectPublicKeyInfo DER. Creates the key pair on first use. */
    fun publicKeySpki(): ByteArray

    /** Raw 64-byte `r || s` ECDSA P-256 / SHA-256 signature. */
    fun sign(message: ByteArray): ByteArray

    /** Forgets the key pair: the next upload starts a new anonymous identity. */
    fun reset()
}

val DeviceIdentity.clientId: String get() = ContributionSigner.clientIdOf(publicKeySpki())

/**
 * Key pair in the Android Keystore: the private key is generated inside it, cannot be
 * exported and is never written anywhere. Nothing secret is embedded in the APK.
 */
class KeystoreDeviceIdentity(private val alias: String = ALIAS) : DeviceIdentity {

    private val lock = Any()

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun entry(): KeyStore.PrivateKeyEntry = synchronized(lock) {
        (keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry) ?: run {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generator.generateKeyPair()
            keyStore().getEntry(alias, null) as KeyStore.PrivateKeyEntry
        }
    }

    override fun publicKeySpki(): ByteArray = entry().certificate.publicKey.encoded

    override fun sign(message: ByteArray): ByteArray = signRaw(entry().privateKey, message)

    override fun reset() {
        synchronized(lock) { keyStore().deleteEntry(alias) }
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "oceanguard_contribution_device"

        fun signRaw(key: PrivateKey, message: ByteArray): ByteArray {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(key)
            signature.update(message)
            return ContributionSigner.derToRaw(signature.sign())
        }
    }
}
