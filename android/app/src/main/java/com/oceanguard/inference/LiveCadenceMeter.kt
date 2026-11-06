package com.oceanguard.ai.inference

/**
 * Real cadence of the continuous scan: mean time between the last completed passes. Each pass
 * takes seconds on a phone, so a frames-per-second window of one second says nothing useful.
 */
class LiveCadenceMeter(private val window: Int = DEFAULT_WINDOW) {

    private val intervals = ArrayDeque<Long>(window)
    private var lastPassMs: Long? = null

    /** Registers a completed pass. Returns the cadence, or null until two passes have finished. */
    fun record(nowMs: Long): Long? {
        lastPassMs?.let { intervals.addLast(nowMs - it) }
        if (intervals.size > window) intervals.removeFirst()
        lastPassMs = nowMs
        return if (intervals.isEmpty()) null else intervals.sum() / intervals.size
    }

    /** Forgets everything: time spent paused is not cadence. */
    fun reset() {
        intervals.clear()
        lastPassMs = null
    }

    private companion object {
        const val DEFAULT_WINDOW = 5
    }
}
