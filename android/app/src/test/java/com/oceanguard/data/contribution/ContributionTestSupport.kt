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
    override fun attestationChain(): List<ByteArray> = listOf(keys().public.encoded, byteArrayOf(0x30, 0x00))
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

/**
 * A synthetic JPEG of at least 10 MiB, like a 50 MP phone photo: the small image with fake
 * GPS, make and model, plus 160 filler APP15 segments (never a real photo or place).
 */
fun largeSyntheticJpeg(dir: File, name: String = "large.jpg"): File {
    val small = syntheticJpeg(dir, "small_$name").readBytes()
    val out = java.io.ByteArrayOutputStream()
    out.write(small, 0, 2) // SOI
    val filler = ByteArray(65_531) { (it % 199).toByte() }
    repeat(160) {
        out.write(byteArrayOf(0xFF.toByte(), 0xEF.toByte(), 0xFF.toByte(), 0xFD.toByte()))
        out.write(filler)
    }
    out.write(small, 2, small.size - 2)
    return File(dir, name).apply { writeBytes(out.toByteArray()) }
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

/**
 * MockWebServer dispatcher that behaves like the Worker: registration is accepted, part requests get
 * 202 unless the next queued answer is a refusal (then the part gets it); the commit, a
 * withdrawal or any other request gets the next queued answer (201 by default).
 */
class WorkerLikeDispatcher : okhttp3.mockwebserver.Dispatcher() {
    private val queue = java.util.concurrent.LinkedBlockingQueue<okhttp3.mockwebserver.MockResponse>()

    fun enqueue(response: okhttp3.mockwebserver.MockResponse) {
        queue.add(response)
    }

    private fun code(response: okhttp3.mockwebserver.MockResponse) = response.status.split(" ")[1].toInt()

    /** Answer to POST /v1/devices (registration); accepted unless a test changes it. */
    var registration: okhttp3.mockwebserver.MockResponse =
        okhttp3.mockwebserver.MockResponse().setResponseCode(201).setBody("""{"registered":true,"security_level":"tee"}""")

    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
        if (request.path == "/v1/devices") return registration
        if ("/parts/" in request.path.orEmpty()) {
            val head = queue.peek()
            return if (head != null && code(head) !in 200..201) queue.poll()!!
            else okhttp3.mockwebserver.MockResponse().setResponseCode(202).setBody("""{"received":0}""")
        }
        return queue.poll() ?: okhttp3.mockwebserver.MockResponse().setResponseCode(201).setBody("{}")
    }
}

/** Contribution id of an upload request (it travels in the path). */
fun contributionIdOf(request: okhttp3.mockwebserver.RecordedRequest): String? =
    Regex("/v1/contributions/([0-9a-f-]{36})/").find(request.path.orEmpty())?.groupValues?.get(1)

/** Requests other than the device registration, in arrival order. */
fun okhttp3.mockwebserver.MockWebServer.uploadRequests(): List<okhttp3.mockwebserver.RecordedRequest> =
    (1..requestCount).map { takeRequest() }.filter { it.path != "/v1/devices" }
