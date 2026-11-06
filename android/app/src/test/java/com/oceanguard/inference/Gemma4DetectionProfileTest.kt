package com.oceanguard.ai.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Gemma4DetectionProfileTest {

    private val dir = "/storage/emulated/0/Android/data/com.oceanguard.ai.debug/files/models"

    @Test
    fun `base model keeps the open vocabulary prompt`() {
        val profile = Gemma4VisionDetector.profileFor("$dir/gemma-4-E2B-it.litertlm")
        assertTrue(profile.prompt.startsWith("Detect every man-made debris object."))
        assertEquals(512, profile.maxTokens)
        assertEquals(10, profile.topK)
    }

    @Test
    fun `fine-tuned model uses the training prompt and greedy decoding`() {
        val profile = Gemma4VisionDetector.profileFor("$dir/gemma-4-E2B-it-oceanguard.litertlm")
        assertTrue(profile.prompt.contains("ONE of these 8 labels"))
        assertTrue(profile.prompt.endsWith("If no debris is found, output: []"))
        assertEquals(256, profile.maxTokens)
        assertEquals(1, profile.topK)
        assertEquals(0.0, profile.temperature, 0.0)
    }

    @Test
    fun `no model loaded falls back to the base profile`() {
        assertSame(
            Gemma4VisionDetector.profileFor("$dir/gemma-4-E2B-it.litertlm"),
            Gemma4VisionDetector.profileFor(null),
        )
    }
}
