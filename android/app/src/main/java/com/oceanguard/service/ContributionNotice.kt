package com.oceanguard.ai.service

import com.oceanguard.ai.data.contribution.RunOutcome
import com.oceanguard.ai.data.contribution.RunReport

/**
 * What the closing notification of an upload cycle says. Pure logic, so it is unit-testable
 * without Android; [DataContributionWorker] turns it into text and posts it.
 */
sealed interface ContributionSummary {
    /** Everything sent: "5 photos contributed". */
    data class Done(val uploaded: Int) : ContributionSummary

    /** Some photos wait for a retry (no connection, server busy): "3 contributed, 2 pending". */
    data class Pending(val uploaded: Int, val pending: Int) : ContributionSummary

    /** Some photos were refused or unreadable and will not be retried: "3 contributed, 2 not sent". */
    data class Rejected(val uploaded: Int, val rejected: Int) : ContributionSummary
}

object ContributionNotice {
    /** Progress counter shown while uploading: the photo being sent, never beyond the total. */
    fun progressPosition(done: Int, total: Int): Int = (done + 1).coerceIn(1, maxOf(total, 1))

    /**
     * Summary of a finished cycle, or null when there is nothing to tell (no photo sent or
     * waiting, or the destination is not configured).
     */
    fun summaryOf(report: RunReport): ContributionSummary? {
        if (report.outcome == RunOutcome.NOT_CONFIGURED) return null
        return when {
            report.pending > 0 -> ContributionSummary.Pending(report.uploaded, report.pending)
            report.failed > 0 -> ContributionSummary.Rejected(report.uploaded, report.failed)
            report.uploaded > 0 -> ContributionSummary.Done(report.uploaded)
            else -> null
        }
    }
}
