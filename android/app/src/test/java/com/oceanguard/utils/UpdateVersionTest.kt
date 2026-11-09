package com.oceanguard.ai.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test
    fun `a later release is offered`() {
        assertTrue(isNewerVersion("1.2.0", "1.1.1"))
        assertTrue(isNewerVersion("1.10.0", "1.9.3"))
        assertTrue(isNewerVersion("v2.0", "1.9.9"))
    }

    @Test
    fun `the same or an older release is not offered`() {
        assertFalse(isNewerVersion("1.1.1", "1.1.1"))
        assertFalse(isNewerVersion("1.1.1", "1.2.0"))
        assertFalse(isNewerVersion("1.1.1", "1.1.1-30-g6014fca"))
        assertFalse(isNewerVersion("1.1.1", "1.2.0-rc1"))
    }

    @Test
    fun `the final release replaces its own release candidate`() {
        assertTrue(isNewerVersion("1.2.0", "1.2.0-rc1"))
    }
}
