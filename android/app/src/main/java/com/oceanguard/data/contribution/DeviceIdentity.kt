package com.oceanguard.ai.data.contribution

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
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

    /** Attestation certificate chain of the key, leaf first (DER), for POST /v1/devices. */
    fun attestationChain(): List<ByteArray>

    /** Forgets the key pair: the next upload starts a new anonymous identity. */
    fun reset()
}

val DeviceIdentity.clientId: String get() = ContributionSigner.clientIdOf(publicKeySpki())

/**
 * Key pair in the Android Keystore: generated inside the secure hardware (TEE or StrongBox),
 * not exportable, never written anywhere. It is created with an attestation challenge, so
 * the hardware signs a certificate chain, up to a Google attestation root, stating where the
 * key lives, the boot state of the device and which app (package and signing certificate)
 * owns it. The Worker verifies that chain (Android key attestation) instead of relying on a
 * Google service: no account, no quota, and it works for an APK installed from GitHub.
 */
class KeystoreDeviceIdentity(private val alias: String = ALIAS) : DeviceIdentity {

    private val lock = Any()

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun entry(): KeyStore.PrivateKeyEntry = synchronized(lock) {
        (keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry) ?: run {
            generate(withAttestation = true)
            keyStore().getEntry(alias, null) as KeyStore.PrivateKeyEntry
        }
    }

    private fun generate(withAttestation: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (withAttestation) setAttestationChallenge(ATTESTATION_CHALLENGE) }
            .build()
        try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
        } catch (e: ProviderException) {
            // A few devices cannot attest; the key still works, the Worker will refuse to register it.
            if (!withAttestation) throw e
            Log.w("DeviceIdentity", "Key attestation unavailable on this device: ${e.javaClass.simpleName}")
            generate(withAttestation = false)
        }
    }

    override fun publicKeySpki(): ByteArray = entry().certificate.publicKey.encoded

    override fun sign(message: ByteArray): ByteArray = signRaw(entry().privateKey, message)

    override fun attestationChain(): List<ByteArray> {
        entry()
        return keyStore().getCertificateChain(alias).orEmpty().map { it.encoded }
    }

    override fun reset() {
        synchronized(lock) { keyStore().deleteEntry(alias) }
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "oceanguard_contribution_device"

        /** Same value as ATTESTATION_CHALLENGE in the Worker (worker/src/index.js). */
        val ATTESTATION_CHALLENGE: ByteArray = "oceanguard-contribution-v1".toByteArray(Charsets.US_ASCII)

        fun signRaw(key: PrivateKey, message: ByteArray): ByteArray {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(key)
            signature.update(message)
            return ContributionSigner.derToRaw(signature.sign())
        }
    }
}
