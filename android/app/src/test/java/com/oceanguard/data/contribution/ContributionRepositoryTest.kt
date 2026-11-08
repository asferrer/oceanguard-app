package com.oceanguard.ai.data.contribution

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.OceanGuardDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ContributionRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val worker = WorkerLikeDispatcher()
    private lateinit var db: OceanGuardDatabase
    private val dao get() = db.contributionQueueDao()
    private val prefs = FakeContributionPrefs()
    private val identity = SoftwareDeviceIdentity()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        server.dispatcher = worker
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        db = inMemoryDatabase(context)
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun repository(available: Boolean = true) = ContributionRepository(
        dao = dao,
        prefs = prefs,
        context = context,
        client = if (available) {
            ContributionApiClient(testConfig("http://127.0.0.1:${server.port}/"), identity)
        } else {
            null
        },
        serializer = { _, session -> """{"images":[{"file_name":"${session.imageUri}"}],"annotations":[],"categories":[]}""" },
    )

    private fun session(uri: String = "file:///photos/a.jpg") = DetectionSession(
        id = 7, imageUri = uri, debrisList = emptyList(), totalCount = 0, healthScore = 90,
        imageQuality = ImageQuality.GOOD, processingTimeMs = 10,
    )

    private fun uniqueWork(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(ContributionRepository.WORK_NAME).get()

    @Test
    fun withoutDestinationNothingIsEverQueued() = runBlocking {
        prefs.consent = true
        val repo = repository(available = false)
        assertFalse(repo.isAvailable)
        assertFalse(repo.maybeEnqueue(session()))
        assertFalse(repo.enqueueSession(session()))
        assertEquals(0, dao.getPendingCount())
        assertTrue(uniqueWork().isEmpty())
    }

    @Test
    fun reconcileWithoutDestinationEmptiesTheQueueAndTheConsent() = runBlocking {
        prefs.consent = true
        dao.insert(ContributionQueueItem(sessionId = 1, imageUri = "u", annotationsJson = "{}", status = ContributionStatus.PENDING))
        repository(available = false).reconcile()
        assertEquals(0, dao.getPendingCount())
        assertFalse(prefs.consent)
        assertEquals(ContributionSyncStatus.NOT_CONFIGURED, prefs.statuses.last())
    }

    @Test
    fun reconcileDropsWhatWasQueuedUnderTheOldConsent() = runBlocking {
        prefs.stale = true
        dao.insert(ContributionQueueItem(sessionId = 1, imageUri = "u", annotationsJson = "{}", status = ContributionStatus.PENDING))
        repository().reconcile()
        assertEquals(0, dao.getPendingCount())
        assertFalse(prefs.consent)
    }

    @Test
    fun withoutConsentAnalysesAreNotQueued() = runBlocking {
        assertFalse(repository().maybeEnqueue(session()))
        assertEquals(0, dao.getPendingCount())
    }

    @Test
    fun withConsentTheAnalysisIsQueuedOnceAndScheduled() = runBlocking {
        prefs.consent = true
        val repo = repository()
        assertTrue(repo.maybeEnqueue(session()))
        assertTrue(repo.maybeEnqueue(session()))
        assertEquals(1, dao.getPendingCount())
        assertEquals("PENDING", repo.getStatusFlowForUri(session().imageUri).first())
        val work = uniqueWork()
        assertEquals(1, work.size)
        assertTrue(work[0].tags.contains(ContributionRepository.WORK_TAG))
    }

    @Test
    fun retryFailedGivesAFreshBudget() = runBlocking {
        dao.insert(ContributionQueueItem(sessionId = 1, imageUri = "u", annotationsJson = "{}", status = ContributionStatus.FAILED, retryCount = 5))
        assertEquals(1, repository().retryFailed())
        val item = dao.getNextPending()!!
        assertEquals(0, item.retryCount)
    }

    @Test
    fun withdrawAllDeletesEverywhereAndStartsANewId() = runBlocking {
        prefs.consent = true
        prefs.id = identity.clientId
        dao.insert(ContributionQueueItem(sessionId = 1, imageUri = "u", annotationsJson = "{}", status = ContributionStatus.DONE))
        worker.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"accepted","pending_deleted":1}"""))

        assertEquals(WithdrawResult.Done(1), repository().withdrawAll())
        assertEquals("/v1/withdrawals", server.uploadRequests().single().path)
        assertEquals(0, dao.getDoneCount())
        assertFalse(prefs.consent)
        assertNull(prefs.id)
        assertEquals("a new anonymous identity next time", 1, identity.resets)
    }

    @Test
    fun failedWithdrawalKeepsEverything() = runBlocking {
        prefs.consent = true
        dao.insert(ContributionQueueItem(sessionId = 1, imageUri = "u", annotationsJson = "{}", status = ContributionStatus.DONE))
        worker.enqueue(MockResponse().setResponseCode(503))
        assertEquals(WithdrawResult.Failed(ContributionSyncStatus.SERVER), repository().withdrawAll())
        assertEquals(1, dao.getDoneCount())
        assertTrue(prefs.consent)
    }

    @Test
    fun withdrawWithoutDestinationIsUnavailable() = runBlocking {
        assertEquals(WithdrawResult.Unavailable, repository(available = false).withdrawAll())
    }
}
