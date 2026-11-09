package com.oceanguard.ai.data.contribution

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ContributionStatus { PENDING, DONE, FAILED }

@Entity(tableName = "contribution_queue")
data class ContributionQueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val imageUri: String,
    val annotationsJson: String,
    /** What the app answered for the photo (see [ContributionOutputs]); null on items queued before v16. */
    val outputsJson: String? = null,
    val status: ContributionStatus,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
