package com.oceanguard.ai.data.contribution

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.oceanguard.ai.data.OceanGuardDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ContributionUploadRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val worker = WorkerLikeDispatcher()
    private lateinit var db: OceanGuardDatabase
    private val dao get() = db.contributionQueueDao()
    private val prefs = FakeContributionPrefs(consent = true)

    @Before
    fun setUp() {
        server.dispatcher = worker
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private val identity = SoftwareDeviceIdentity()

    private fun client() = ContributionApiClient(
        testConfig("http://127.0.0.1:${server.port}/"),
        identity,
    )

    private fun runner(
        client: ContributionApiClient? = client(),
        prepare: (File, Boolean) -> PreparedPhoto = { f, _ -> PreparedPhoto(f.readBytes(), 16, 12) },
        seal: ((String, String, ByteArray) -> ByteArray)? = null,
    ) = ContributionUploadRunner(
        dao, client, prefs, "1.2.0", prepare,
        seal = seal ?: { id, meta, image -> ContributionEnvelope.seal(testRecipient.public.encoded, id, meta, image) },
        pauseBetweenMs = 0,
    )

    private fun queue(vararg names: String, retries: Int = 0): List<Long> = runBlocking {
        names.map { name ->
            val photo = File(tmp.root, name).apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9)) }
            dao.insert(
                ContributionQueueItem(
                    sessionId = 1, imageUri = "file://${photo.path}", annotationsJson = "{}",
                    status = ContributionStatus.PENDING, retryCount = retries,
                ),
            )
        }
    }

    private fun statusOf(id: Long): Pair<String, Int> = db.query(
        "SELECT status, retryCount FROM contribution_queue WHERE id = ?", arrayOf(id),
    ).use { c -> c.moveToFirst(); c.getString(0) to c.getInt(1) }

    @Test
    fun withoutDestinationNothingRunsAndSettingsKnowsWhy() = runBlocking {
        val ids = queue("a.jpg")
        val report = runner(client = null).run()
        assertEquals(RunOutcome.NOT_CONFIGURED, report.outcome)
        assertEquals(listOf(ContributionSyncStatus.NOT_CONFIGURED), prefs.statuses)
        assertEquals("PENDING", statusOf(ids[0]).first)
    }

    @Test
    fun acceptedPhotosAreDone() = runBlocking {
        val ids = queue("a.jpg", "b.jpg")
        repeat(2) { worker.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"x","duplicate":false}""")) }
        val report = runner().run()
        assertEquals(RunReport(uploaded = 2, failed = 0, outcome = RunOutcome.DONE), report)
        ids.forEach { assertEquals("DONE", statusOf(it).first) }
        assertEquals(ContributionSyncStatus.OK, prefs.statuses.last())
        assertEquals(identity.clientId, prefs.id)
        val first = server.uploadRequests().first()
        assertTrue(contributionIdOf(first) != null)
        assertTrue(first.path!!.contains("/parts/0?parts=1&bytes="))
        assertTrue("envelope, not the photo", first.body.readByteArray().copyOfRange(0, 4).contentEquals("OGC1".toByteArray()))
    }

    @Test
    fun refusedPhotoFailsAndTheQueueGoesOn() = runBlocking {
        val ids = queue("a.jpg", "b.jpg")
        worker.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":"bad_annotations"}"""))
        worker.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        val report = runner().run()
        assertEquals(RunReport(1, 1, RunOutcome.DONE), report)
        assertEquals("FAILED", statusOf(ids[0]).first)
        assertEquals("DONE", statusOf(ids[1]).first)
    }

    @Test
    fun serverErrorKeepsPendingSpendsOneAttemptAndAsksForRetry() = runBlocking {
        val ids = queue("a.jpg", "b.jpg")
        worker.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"server"}"""))
        val report = runner().run()
        assertEquals(RunOutcome.RETRY, report.outcome)
        assertEquals("PENDING" to 1, statusOf(ids[0]))
        assertEquals("PENDING" to 0, statusOf(ids[1]))
        assertEquals(ContributionSyncStatus.SERVER, prefs.statuses.last())
    }

    @Test
    fun rateLimitDoesNotSpendAttempts() = runBlocking {
        val ids = queue("a.jpg")
        worker.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60").setBody("{}"))
        assertEquals(RunOutcome.RETRY, runner().run().outcome)
        assertEquals("PENDING" to 0, statusOf(ids[0]))
        assertEquals(ContributionSyncStatus.RATE_LIMITED, prefs.statuses.last())
    }

    @Test
    fun revokedKeyEndsInFailedAfterTheBudget() = runBlocking {
        val ids = queue("a.jpg")
        repeat(ContributionUploadRunner.MAX_RETRIES) {
            worker.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"auth"}"""))
            assertEquals(RunOutcome.RETRY, runner().run().outcome)
        }
        val last = runner().run()
        assertEquals(RunOutcome.DONE, last.outcome)
        assertEquals("FAILED", statusOf(ids[0]).first)
        assertEquals(ContributionSyncStatus.AUTH, prefs.statuses.last())
        assertEquals(ContributionUploadRunner.MAX_RETRIES, server.uploadRequests().size)
    }

    @Test
    fun quotaDoesNotSpendAttempts() = runBlocking {
        val ids = queue("a.jpg")
        worker.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"quota"}"""))
        assertEquals(RunOutcome.RETRY, runner().run().outcome)
        assertEquals("PENDING" to 0, statusOf(ids[0]))
        assertEquals(ContributionSyncStatus.QUOTA, prefs.statuses.last())
    }

    @Test
    fun retriesKeepTheSameContributionId() = runBlocking {
        queue("a.jpg")
        worker.enqueue(MockResponse().setResponseCode(500))
        worker.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        runner().run()
        runner().run()
        val ids = server.uploadRequests().map { contributionIdOf(it) }.toSet()
        assertEquals("every request of both attempts names the same contribution", 1, ids.size)
    }

    @Test
    fun tenMegabytePhotoWithoutLocationTravelsInParts() = runBlocking {
        // 10 MB synthetic JPEG: real EXIF with fake GPS plus 160 filler APP15 segments.
        val photo = largeSyntheticJpeg(tmp.root)
        assertTrue(photo.length() >= 10L * 1024 * 1024)
        val id = dao.insert(
            ContributionQueueItem(sessionId = 1, imageUri = "file://${photo.path}", annotationsJson = "{}", status = ContributionStatus.PENDING),
        )
        prefs.include = false
        val work = tmp.newFolder("work")
        val report = runner(prepare = { f, include -> ContributionImage.prepare(f, include, work) }).run()
        assertEquals(RunReport(1, 0, RunOutcome.DONE), report)
        assertEquals("DONE", statusOf(id).first)
        val requests = server.uploadRequests()
        val parts = requests.filter { "/parts/" in it.path!! }
        assertEquals(6, parts.size)
        assertTrue("one D1 row per part", parts.all { it.bodySize <= 2_000_000 })
        assertTrue(requests.last().path!!.contains("/commit?parts=6&bytes="))
        assertTrue("the temporary copy without GPS is gone", work.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun deviceThatFailsAttestationSpendsAttemptsAndSaysWhy() = runBlocking {
        val ids = queue("a.jpg")
        worker.registration = MockResponse().setResponseCode(401).setBody("""{"error":"attestation","reason":"bootloader unlocked"}""")
        assertEquals(RunOutcome.RETRY, runner().run().outcome)
        assertEquals("PENDING" to 1, statusOf(ids[0]))
        assertEquals(ContributionSyncStatus.ATTESTATION, prefs.statuses.last())
        assertTrue("nothing uploaded", server.uploadRequests().isEmpty())
    }

    @Test
    fun photoThatCannotBePreparedIsNeverSent() = runBlocking {
        val ids = queue("a.jpg")
        val report = runner(prepare = { _, _ -> throw IOException("GPS still there") }).run()
        assertEquals(RunReport(0, 1, RunOutcome.DONE), report)
        assertEquals("FAILED", statusOf(ids[0]).first)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun locationPreferenceReachesThePreparation() = runBlocking {
        queue("a.jpg")
        prefs.include = false
        val seen = mutableListOf<Boolean>()
        worker.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        runner(prepare = { f, include -> seen += include; PreparedPhoto(f.readBytes(), 16, 12) }).run()
        assertEquals(listOf(false), seen)
        assertTrue(server.uploadRequests().first().getHeader("X-OG-Signature")!!.isNotEmpty())
    }

    private fun captureMeta(item: ContributionQueueItem, prepared: PreparedPhoto): com.google.gson.JsonObject = runBlocking {
        dao.insert(item)
        worker.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        var meta = ""
        runner(
            prepare = { _, _ -> prepared },
            seal = { _, m, _ -> meta = m; byteArrayOf(1) },
        ).run()
        com.google.gson.JsonParser.parseString(meta).asJsonObject
    }

    private fun item(annotations: String, outputs: String?): ContributionQueueItem {
        val photo = File(tmp.root, "m.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9)) }
        return ContributionQueueItem(
            sessionId = 1, imageUri = "file://${photo.path}", annotationsJson = annotations,
            outputsJson = outputs, status = ContributionStatus.PENDING,
        )
    }

    @Test
    fun metaKeepsV1AndCocoAndAddsOutputsWithTheSentImage() {
        val outputs = """{"schema":1,"detection":{"model":"RT-DETRv2","items":[{"class":"BOTTLE","score":0.9,"bbox_norm":[0.25,0.5,0.5,0.25]}]}}"""
        val prepared = PreparedPhoto(byteArrayOf(1, 2, 3), 400, 200, transcoded = true)
        val meta = captureMeta(item("""{"images":[],"annotations":[]}""", outputs), prepared)

        assertEquals(1, meta.get("v").asInt)
        assertTrue(meta.get("annotations").isJsonObject)
        val out = meta.getAsJsonObject("outputs")
        val image = out.getAsJsonObject("image")
        assertEquals(400, image.get("width").asInt)
        assertEquals(true, image.get("transcoded").asBoolean)
        assertEquals(ContributionUploadRunner.sha256Hex(byteArrayOf(1, 2, 3)), image.get("sha256").asString)
        val box = out.getAsJsonObject("detection").getAsJsonArray("items")[0].asJsonObject.getAsJsonArray("bbox_px")
        assertEquals(listOf(100f, 100f, 200f, 50f), box.map { it.asFloat })
    }

    @Test
    fun itemsQueuedWithoutOutputsStillGoWithTheImageBlock() {
        val meta = captureMeta(item("""{"images":[],"annotations":[]}""", null), PreparedPhoto(byteArrayOf(1), 16, 12))
        val out = meta.getAsJsonObject("outputs")
        assertEquals(1, out.get("schema").asInt)
        assertEquals(16, out.getAsJsonObject("image").get("width").asInt)
    }

    @Test
    fun shrunkPhotoRescalesTheCocoBoxesAndRecordsIt() {
        val coco = """{"images":[{"id":1,"width":1000,"height":500}],"annotations":[{"id":1,"bbox":[100,50,200,100],"area":20000}]}"""
        val prepared = PreparedPhoto(byteArrayOf(1), 500, 250, transcoded = true, sourceWidth = 1000, sourceHeight = 500)
        val meta = captureMeta(item(coco, null), prepared)
        val ann = meta.getAsJsonObject("annotations")
        assertEquals(500, ann.getAsJsonArray("images")[0].asJsonObject.get("width").asInt)
        val a = ann.getAsJsonArray("annotations")[0].asJsonObject
        assertEquals(listOf(50.0, 25.0, 100.0, 50.0), a.getAsJsonArray("bbox").map { it.asDouble })
        assertEquals(5000.0, a.get("area").asDouble, 1e-6)
        val image = meta.getAsJsonObject("outputs").getAsJsonObject("image")
        assertEquals(true, image.get("downscaled").asBoolean)
        assertEquals(1000, image.get("source_width").asInt)
    }
}
