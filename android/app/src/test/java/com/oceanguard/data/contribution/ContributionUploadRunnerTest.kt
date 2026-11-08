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
    private lateinit var db: OceanGuardDatabase
    private val dao get() = db.contributionQueueDao()
    private val prefs = FakeContributionPrefs(consent = true)

    @Before
    fun setUp() {
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun client() = ContributionApiClient(
        ContributionConfig("http://127.0.0.1:${server.port}/", "og-test-2026", "super-secret-test-value"),
        appCheckToken = { null },
    )

    private fun runner(
        client: ContributionApiClient? = client(),
        prepare: (File, Boolean) -> ByteArray = { f, _ -> f.readBytes() },
    ) = ContributionUploadRunner(dao, client, prefs, "1.2.0", prepare, pauseBetweenMs = 0)

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
        repeat(2) { server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"x","duplicate":false}""")) }
        val report = runner().run()
        assertEquals(RunReport(uploaded = 2, failed = 0, outcome = RunOutcome.DONE), report)
        ids.forEach { assertEquals("DONE", statusOf(it).first) }
        assertEquals(ContributionSyncStatus.OK, prefs.statuses.last())
    }

    @Test
    fun refusedPhotoFailsAndTheQueueGoesOn() = runBlocking {
        val ids = queue("a.jpg", "b.jpg")
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":"bad_annotations"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        val report = runner().run()
        assertEquals(RunReport(1, 1, RunOutcome.DONE), report)
        assertEquals("FAILED", statusOf(ids[0]).first)
        assertEquals("DONE", statusOf(ids[1]).first)
    }

    @Test
    fun serverErrorKeepsPendingSpendsOneAttemptAndAsksForRetry() = runBlocking {
        val ids = queue("a.jpg", "b.jpg")
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"server"}"""))
        val report = runner().run()
        assertEquals(RunOutcome.RETRY, report.outcome)
        assertEquals("PENDING" to 1, statusOf(ids[0]))
        assertEquals("PENDING" to 0, statusOf(ids[1]))
        assertEquals(ContributionSyncStatus.SERVER, prefs.statuses.last())
    }

    @Test
    fun rateLimitDoesNotSpendAttempts() = runBlocking {
        val ids = queue("a.jpg")
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60").setBody("{}"))
        assertEquals(RunOutcome.RETRY, runner().run().outcome)
        assertEquals("PENDING" to 0, statusOf(ids[0]))
        assertEquals(ContributionSyncStatus.RATE_LIMITED, prefs.statuses.last())
    }

    @Test
    fun revokedKeyEndsInFailedAfterTheBudget() = runBlocking {
        val ids = queue("a.jpg")
        repeat(ContributionUploadRunner.MAX_RETRIES) {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"auth"}"""))
            assertEquals(RunOutcome.RETRY, runner().run().outcome)
        }
        val last = runner().run()
        assertEquals(RunOutcome.DONE, last.outcome)
        assertEquals("FAILED", statusOf(ids[0]).first)
        assertEquals(ContributionSyncStatus.AUTH, prefs.statuses.last())
        assertEquals(ContributionUploadRunner.MAX_RETRIES, server.requestCount)
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
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        runner(prepare = { f, include -> seen += include; f.readBytes() }).run()
        assertEquals(listOf(false), seen)
        assertTrue(server.takeRequest().getHeader("X-OG-Signature")!!.isNotEmpty())
    }
}
