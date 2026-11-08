package com.oceanguard.ai.data.contribution

/**
 * The preferences the contribution queue and worker need. Backed by DataStore in the app
 * (`data/settings/ContributionSettings.kt`); a plain fake in tests.
 */
interface ContributionPrefs {
    /** Consent given for the current wording of the consent (see CONSENT_VERSION). */
    suspend fun consentGiven(): Boolean

    suspend fun setConsent(given: Boolean)

    /** Consent given under an older wording, which no longer counts. */
    suspend fun hasStaleConsent(): Boolean

    /** Send the GPS tags of the photo (default true, author's choice). */
    suspend fun includeLocation(): Boolean

    suspend fun wifiOnly(): Boolean

    /** Anonymous install id, created on first use; sent as `client_id`. */
    suspend fun clientId(): String

    /** Starts a new anonymous id (after withdrawing everything). */
    suspend fun resetClientId()

    suspend fun recordSync(status: ContributionSyncStatus)
}
