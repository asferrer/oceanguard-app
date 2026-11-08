package com.oceanguard.ai.data.contribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ContributionConfigTest {

    private fun config(url: String, key: String = testRecipientB64) = ContributionConfig(url, key)

    @Test
    fun workerUrlWithNasKeyIsUsable() {
        val c = config("https://oceanguard-contrib.example.workers.dev/")
        assertTrue(c.isUsable)
        assertFalse(c.isLoopback)
        assertEquals("https://oceanguard-contrib.example.workers.dev/", c.url.toString())
    }

    @Test
    fun plainHttpOnlyToLoopback() {
        assertTrue(config("http://127.0.0.1:8787").isUsable)
        assertTrue(config("http://localhost:8787").isLoopback)
        assertFalse(config("http://192.168.1.10:8787").isUsable)
        assertFalse(config("http://oceanguard-contrib.example.workers.dev").isUsable)
    }

    @Test
    fun missingOrWrongKeyMeansNoDestination() {
        val rsa = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        assertFalse(config("").isUsable)
        assertFalse(config("https://x.workers.dev", key = "").isUsable)
        assertFalse(config("https://x.workers.dev", key = "not base64 !").isUsable)
        assertFalse(config("https://x.workers.dev", key = Base64.getEncoder().encodeToString(rsa.public.encoded)).isUsable)
    }
}
