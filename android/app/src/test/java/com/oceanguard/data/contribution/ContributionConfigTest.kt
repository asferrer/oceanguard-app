package com.oceanguard.ai.data.contribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributionConfigTest {

    private fun config(url: String, key: String = "og-android-2026a", secret: String = "s3cret") =
        ContributionConfig(url, key, secret)

    @Test
    fun httpsWithKeyIsUsable() {
        assertTrue(config("https://contrib.example.org").isUsable)
        assertEquals("https://contrib.example.org/", config("https://contrib.example.org/").url.toString())
    }

    @Test
    fun plainHttpOnlyToLoopback() {
        assertTrue(config("http://127.0.0.1:8000").isUsable)
        assertTrue(config("http://localhost:8000").isUsable)
        assertFalse(config("http://192.168.1.10:8000").isUsable)
        assertFalse(config("http://10.example.org").isUsable)
        assertFalse(config("http://contrib.example.org").isUsable)
    }

    @Test
    fun missingValuesMeanNoDestination() {
        assertFalse(config("").isUsable)
        assertFalse(config("not a url").isUsable)
        assertFalse(config("https://contrib.example.org", key = "").isUsable)
        assertFalse(config("https://contrib.example.org", secret = " ").isUsable)
    }

    @Test
    fun secretNeverPrinted() {
        assertFalse(config("https://contrib.example.org").toString().contains("s3cret"))
    }
}
