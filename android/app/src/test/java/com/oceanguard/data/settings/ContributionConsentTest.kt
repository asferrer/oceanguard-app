package com.oceanguard.ai.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A consent given in v1.1.1 ("no GPS coordinates") must not cover uploads that carry GPS. */
class ContributionConsentTest {

    @Test
    fun consentOfV111IsStaleAndDoesNotCount() {
        assertFalse(isConsentCurrent(given = true, version = null))
        assertTrue(isConsentStale(given = true, version = null))
        assertTrue(isConsentStale(given = true, version = 1))
    }

    @Test
    fun consentToTheCurrentWordingCounts() {
        assertTrue(isConsentCurrent(given = true, version = CONSENT_VERSION))
        assertFalse(isConsentStale(given = true, version = CONSENT_VERSION))
    }

    @Test
    fun noConsentIsNeitherCurrentNorStale() {
        listOf(null, false).forEach { given ->
            assertFalse(isConsentCurrent(given, CONSENT_VERSION))
            assertFalse(isConsentStale(given, null))
        }
    }
}
