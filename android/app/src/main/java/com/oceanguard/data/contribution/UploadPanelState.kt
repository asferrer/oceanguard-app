package com.oceanguard.ai.data.contribution

/** Progress of the upload pass that is running now: [done] of [total] photos handled. */
data class UploadProgress(val done: Int, val total: Int)

/**
 * What the contribution block in Settings says first, so the user sees at a glance whether photos are
 * going up, waiting (and for what) or stuck. Android-free: decided from the queue counts, the running
 * upload pass and the Wi-Fi preference.
 */
sealed interface UploadPanelState {
    /** A pass is running: photo [current] of [total] (both 0 while it starts). */
    data class Uploading(val current: Int, val total: Int) : UploadPanelState

    /** [count] photos wait; with [wifiOnly] they go up by themselves only on Wi-Fi. */
    data class Waiting(val count: Int, val wifiOnly: Boolean) : UploadPanelState

    /** Nothing waits, [count] photos could not be sent. */
    data class Failed(val count: Int) : UploadPanelState

    /** Everything that was queued has been sent ([sent] in total, 0 = nothing sent yet). */
    data class AllSent(val sent: Int) : UploadPanelState

    companion object {
        fun of(pending: Int, done: Int, failed: Int, running: UploadProgress?, wifiOnly: Boolean): UploadPanelState =
            when {
                running != null -> Uploading(
                    current = if (running.total > 0) (running.done + 1).coerceAtMost(running.total) else 0,
                    total = running.total,
                )
                pending > 0 -> Waiting(pending, wifiOnly)
                failed > 0 -> Failed(failed)
                else -> AllSent(done)
            }
    }
}
