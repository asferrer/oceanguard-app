package com.oceanguard.ai.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributePromptPolicyTest {

    @Test
    fun `prompts while under the cap and not opted out`() {
        assertTrue(ContributePromptPolicy.shouldPrompt(neverPrompt = false, declineCount = 0))
        assertTrue(ContributePromptPolicy.shouldPrompt(neverPrompt = false, declineCount = 2))
    }

    @Test
    fun `stops prompting after three dismissals`() {
        assertFalse(ContributePromptPolicy.shouldPrompt(neverPrompt = false, declineCount = 3))
        assertFalse(ContributePromptPolicy.shouldPrompt(neverPrompt = false, declineCount = 7))
    }

    @Test
    fun `never prompt overrides the counter`() {
        assertFalse(ContributePromptPolicy.shouldPrompt(neverPrompt = true, declineCount = 0))
    }
}
