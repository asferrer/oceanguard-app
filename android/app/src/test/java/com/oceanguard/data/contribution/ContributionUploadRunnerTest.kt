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
        prepare: (File, Boolean) -> ByteArray = { f, _ -> f.readBytes() },
    ) = ContributionUploadRunner(
        dao, client, prefs, "1.2.0", prepare,
        seal = { id, meta, image -> ContributionEnvelope.seal(testRecipient.public.encoded, id, meta, image) },
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
        runner(prepare = { f, include -> seen += include; f.readBytes() }).run()
        assertEquals(listOf(false), seen)
        assertTrue(server.uploadRequests().first().getHeader("X-OG-Signature")!!.isNotEmpty())
    }
}
