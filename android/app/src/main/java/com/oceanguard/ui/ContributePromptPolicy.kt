package com.oceanguard.ai.ui

/** When the "contribute your photos" sheet may be offered after an analysis. */
internal object ContributePromptPolicy {

    /** Soft cap: the sheet stops appearing after this many dismissals. */
    const val MAX_DECLINES = 3

    /** Two independent silencers: explicit opt-out, or reaching [MAX_DECLINES]. */
    fun shouldPrompt(neverPrompt: Boolean, declineCount: Int): Boolean =
        !neverPrompt && declineCount < MAX_DECLINES
}
