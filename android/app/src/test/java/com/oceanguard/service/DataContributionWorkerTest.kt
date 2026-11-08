package com.oceanguard.ai.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.oceanguard.ai.data.contribution.ContributionApiClient
import com.oceanguard.ai.data.contribution.ContributionConfig
import com.oceanguard.ai.data.contribution.ContributionQueueItem
import com.oceanguard.ai.data.contribution.ContributionStatus
import com.oceanguard.ai.data.contribution.ContributionUploadRunner
import com.oceanguard.ai.data.contribution.FakeContributionPrefs
import com.oceanguard.ai.data.contribution.RunOutcome
import com.oceanguard.ai.data.contribution.RunReport
import com.oceanguard.ai.data.contribution.inMemoryDatabase
import com.oceanguard.ai.data.OceanGuardDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Test Application that hosts the worker the way OceanGuardApp does. */
class TestContributionApp : Application(), ContributionHost {
    var runnerFactory: () -> ContributionUploadRunner = { error("set runnerFactory") }
    val reports = mutableListOf<RunReport>()

    override fun contributionUploadRunner() = runnerFactory()
    override suspend fun onContributionCycleFinished(report: RunReport) {
        reports += report
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestContributionApp::class)
class DataContributionWorkerTest {

    private val app: TestContributionApp = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private lateinit var db: OceanGuardDatabase
    private val prefs = FakeContributionPrefs(consent = true)

    @Before
    fun setUp() {
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        db = inMemoryDatabase(app)
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun useRunner(configured: Boolean) {
        val client = if (configured) {
            ContributionApiClient(ContributionConfig("http://127.0.0.1:${server.port}/", "og-test-2026", "s"), appCheckToken = { null })
        } else {
            null
        }
        app.runnerFactory = {
            ContributionUploadRunner(db.contributionQueueDao(), client, prefs, "1.2.0", { f, _ -> f.readBytes() }, pauseBetweenMs = 0)
        }
    }

    private fun queuePhoto() = runBlocking {
        val photo = File(app.cacheDir, "w.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) }
        db.contributionQueueDao().insert(
            ContributionQueueItem(sessionId = 1, imageUri = "file://${photo.path}", annotationsJson = "{}", status = ContributionStatus.PENDING),
        )
    }

    private fun runWorker(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<DataContributionWorker>(app).build().doWork()
    }

    @Test
    fun successWhenTheQueueIsSent() {
        useRunner(configured = true)
        queuePhoto()
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"a","duplicate":false}"""))
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(RunReport(1, 0, RunOutcome.DONE), app.reports.single())
    }

    @Test
    fun retryWhenTheServerFails() {
        useRunner(configured = true)
        queuePhoto()
        server.enqueue(MockResponse().setResponseCode(502))
        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals(RunOutcome.RETRY, app.reports.single().outcome)
    }

    @Test
    fun failureWithoutRetryWhenThereIsNoDestination() {
        useRunner(configured = false)
        queuePhoto()
        assertEquals(ListenableWorker.Result.failure(), runWorker())
        assertEquals(RunOutcome.NOT_CONFIGURED, app.reports.single().outcome)
    }

    @Test
    fun failureWhenTheApplicationIsNotAHost() {
        val plain = ApplicationProvider.getApplicationContext<Context>()
        // The worker resolves its host through applicationContext; a plain context wrapper is not one.
        val context = object : android.content.ContextWrapper(plain) {
            override fun getApplicationContext(): Context = this
        }
        val result = runBlocking { TestListenableWorkerBuilder<DataContributionWorker>(context).build().doWork() }
        assertEquals(ListenableWorker.Result.failure(), result)
    }
}
