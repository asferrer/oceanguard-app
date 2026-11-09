package com.oceanguard.ai.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.oceanguard.ai.R
import com.oceanguard.ai.data.contribution.RunOutcome
import com.oceanguard.ai.data.contribution.RunReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContributionNoticeTest {

    @Test fun `nothing uploaded and nothing waiting gives no notification`() {
        assertNull(ContributionNotice.summaryOf(RunReport(0, 0, RunOutcome.DONE)))
    }

    @Test fun `unconfigured destination gives no notification`() {
        assertNull(ContributionNotice.summaryOf(RunReport(0, 0, RunOutcome.NOT_CONFIGURED)))
    }

    @Test fun `all photos sent gives the done summary`() {
        assertEquals(ContributionSummary.Done(5), ContributionNotice.summaryOf(RunReport(5, 0, RunOutcome.DONE)))
    }

    @Test fun `photos left for a retry give the pending summary`() {
        assertEquals(
            ContributionSummary.Pending(3, 2),
            ContributionNotice.summaryOf(RunReport(3, 0, RunOutcome.RETRY, pending = 2)),
        )
    }

    @Test fun `connection lost before any photo still reports what waits`() {
        assertEquals(
            ContributionSummary.Pending(0, 5),
            ContributionNotice.summaryOf(RunReport(0, 0, RunOutcome.RETRY, pending = 5)),
        )
    }

    @Test fun `refused photos are not announced as retried`() {
        assertEquals(ContributionSummary.Rejected(3, 2), ContributionNotice.summaryOf(RunReport(3, 2, RunOutcome.DONE)))
    }

    @Test fun `progress position counts the photo being sent and never passes the total`() {
        assertEquals(1, ContributionNotice.progressPosition(0, 5))
        assertEquals(2, ContributionNotice.progressPosition(1, 5))
        assertEquals(5, ContributionNotice.progressPosition(7, 5))
        assertEquals(1, ContributionNotice.progressPosition(0, 0))
    }

    @Test fun `texts use the plural and the two numbers`() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals("2 of 5 photos", res.getQuantityString(R.plurals.contribute_upload_progress, 5, 2, 5))
        assertEquals("1 of 1 photo", res.getQuantityString(R.plurals.contribute_upload_progress, 1, 1, 1))
        assertEquals("5 photos contributed", res.getQuantityString(R.plurals.contribute_summary_done, 5, 5))
        assertEquals("1 photo contributed", res.getQuantityString(R.plurals.contribute_summary_done, 1, 1))
        val done = res.getQuantityString(R.plurals.contribute_count_done, 3, 3)
        val pending = res.getQuantityString(R.plurals.contribute_count_pending, 2, 2)
        assertEquals("3 contributed, 2 pending (will retry)", res.getString(R.string.contribute_summary_pending, done, pending))
    }
}
