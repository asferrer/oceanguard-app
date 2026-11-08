package com.oceanguard.ai.data.contribution

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import com.oceanguard.ai.data.OceanGuardDatabase
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** In-memory [ContributionPrefs] for the queue and worker tests. */
class FakeContributionPrefs(
    var consent: Boolean = false,
    var stale: Boolean = false,
    var include: Boolean = true,
    var wifi: Boolean = true,
) : ContributionPrefs {
    val statuses = mutableListOf<ContributionSyncStatus>()
    var id: String? = null

    override suspend fun consentGiven() = consent
    override suspend fun setConsent(given: Boolean) {
        consent = given
        stale = false
    }
    override suspend fun hasStaleConsent() = stale
    override suspend fun includeLocation() = include
    override suspend fun wifiOnly() = wifi
    override suspend fun rememberClientId(id: String?) {
        this.id = id
    }
    override suspend fun recordSync(status: ContributionSyncStatus) {
        statuses += status
    }
}

/** Software key pair standing in for the Android Keystore key. */
class SoftwareDeviceIdentity : DeviceIdentity {
    private var pair: KeyPair? = null
    var resets = 0

    private fun keys(): KeyPair = pair ?: KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().also { pair = it }

    override fun publicKeySpki(): ByteArray = keys().public.encoded
    override fun sign(message: ByteArray): ByteArray = KeystoreDeviceIdentity.signRaw(keys().private, message)
    override fun reset() {
        pair = null
        resets++
    }
}

/** A NAS key pair for tests (the real one is generated on the NAS by generate_keys.py). */
val testRecipient: KeyPair = KeyPairGenerator.getInstance("EC")
    .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

val testRecipientB64: String = java.util.Base64.getEncoder().encodeToString(testRecipient.public.encoded)

fun testConfig(url: String) = ContributionConfig(url, testRecipientB64)

fun inMemoryDatabase(context: Context): OceanGuardDatabase =
    Room.inMemoryDatabaseBuilder(context, OceanGuardDatabase::class.java).allowMainThreadQueries().build()

/** A small synthetic JPEG with made-up GPS, make and model (never a real photo or place). */
fun syntheticJpeg(dir: File, name: String = "synthetic.jpg", withGps: Boolean = true): File {
    val file = File(dir, name)
    file.writeBytes(Base64.getDecoder().decode(PLAIN_JPEG_B64))
    val exif = ExifInterface(file)
    exif.setAttribute(ExifInterface.TAG_MAKE, "SynthMake")
    exif.setAttribute(ExifInterface.TAG_MODEL, "SynthPhone 1")
    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:05:17 10:20:30")
    if (withGps) {
        exif.setLatLong(12.5, -45.25)
        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "3/1")
        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, "0")
    }
    exif.saveAttributes()
    return file
}

/** 16x12 plain-colour JPEG without metadata, generated for these tests. */
private const val PLAIN_JPEG_B64 =
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDABALDA4MChAODQ4SERATGCgaGBYWGDEjJR0oOjM9PDkzODdASFxOQERXRT" +
    "c4UG1RV19iZ2hnPk1xeXBkeFxlZ2P/2wBDARESEhgVGC8aGi9jQjhCY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2Nj" +
    "Y2NjY2NjY2NjY2NjY2NjY2NjY2NjY2P/wAARCAAMABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAw" +
    "QFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkK" +
    "FhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmq" +
    "KjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEB" +
    "AQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRob" +
    "HBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOE" +
    "hYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+P" +
    "n6/9oADAMBAAIRAxEAPwDFooor1jzD/9k="
