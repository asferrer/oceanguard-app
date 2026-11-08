package com.oceanguard.ai.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.data.contribution.ContributionPrefs
import com.oceanguard.ai.data.contribution.ContributionSyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Wording of the consent the user accepted. 1 was v1.1.1 ("no GPS coordinates"); 2 says the
 * photo goes with its EXIF (location, date and time, device model) and the detections. A
 * consent given to an older wording does not count: the user is asked again and whatever
 * was queued under it is dropped.
 */
const val CONSENT_VERSION = 2

private object ContributionKeys {
    // Same names as the keys SettingsRepository used up to v1.1.1.
    val CONSENT_GIVEN = booleanPreferencesKey("contribute_consent_given")
    val DECLINE_COUNT = intPreferencesKey("contribute_decline_count")
    val NEVER_PROMPT = booleanPreferencesKey("contribute_never_prompt")

    val CONSENT_VERSION = intPreferencesKey("contribute_consent_version")
    val INCLUDE_LOCATION = booleanPreferencesKey("contribute_include_location")
    val CLIENT_ID = stringPreferencesKey("contribute_client_id")
    val LAST_STATUS = stringPreferencesKey("contribute_last_status")
    val LAST_STATUS_AT = longPreferencesKey("contribute_last_status_at")
}

/** Last contact with the gateway, for the status line in Settings. */
data class ContributionSyncState(val status: ContributionSyncStatus, val atMs: Long?)

/** Consent counts only when given to the current wording. */
internal fun isConsentCurrent(given: Boolean?, version: Int?): Boolean = given == true && version == CONSENT_VERSION

/** Given, but to an older wording: ask again and drop what was queued under it. */
internal fun isConsentStale(given: Boolean?, version: Int?): Boolean = given == true && version != CONSENT_VERSION

private fun Preferences.consentIsCurrent(): Boolean =
    isConsentCurrent(this[ContributionKeys.CONSENT_GIVEN], this[ContributionKeys.CONSENT_VERSION])

internal fun SettingsRepository.contributeConsentFlow(): Flow<Boolean> =
    store.data.map { it.consentIsCurrent() }

internal suspend fun SettingsRepository.writeContributeConsent(given: Boolean) {
    store.edit { prefs ->
        prefs[ContributionKeys.CONSENT_GIVEN] = given
        if (given) prefs[ContributionKeys.CONSENT_VERSION] = CONSENT_VERSION else prefs.remove(ContributionKeys.CONSENT_VERSION)
    }
}

/** Whether the photo travels with its GPS tags. Default true (author's decision, 2026-11-08). */
val SettingsRepository.contributeIncludeLocation: Flow<Boolean>
    get() = store.data.map { it[ContributionKeys.INCLUDE_LOCATION] ?: true }

suspend fun SettingsRepository.setContributeIncludeLocation(include: Boolean) {
    store.edit { it[ContributionKeys.INCLUDE_LOCATION] = include }
}

/** Shows the post-analysis prompt again: clears "Don't ask again" and the dismissal count. */
suspend fun SettingsRepository.resetContributePrompts() {
    store.edit { prefs ->
        prefs.remove(ContributionKeys.NEVER_PROMPT)
        prefs.remove(ContributionKeys.DECLINE_COUNT)
    }
}

/** True when the prompt is silenced, so Settings can offer to bring it back. */
val SettingsRepository.contributePromptsSilenced: Flow<Boolean>
    get() = store.data.map { prefs ->
        prefs[ContributionKeys.NEVER_PROMPT] == true || (prefs[ContributionKeys.DECLINE_COUNT] ?: 0) > 0
    }

val SettingsRepository.contributionSyncState: Flow<ContributionSyncState>
    get() = store.data.map { prefs ->
        ContributionSyncState(
            ContributionSyncStatus.fromName(prefs[ContributionKeys.LAST_STATUS]),
            prefs[ContributionKeys.LAST_STATUS_AT],
        )
    }

/** Anonymous contributor id (from the device key) shown in Settings, so an e-mail withdrawal request can name it. */
val SettingsRepository.contributionClientIdFlow: Flow<String?>
    get() = store.data.map { it[ContributionKeys.CLIENT_ID] }

/** DataStore-backed [ContributionPrefs] used by the queue and the worker. */
class SettingsContributionPrefs(private val settings: SettingsRepository) : ContributionPrefs {
    private val store get() = settings.store

    override suspend fun consentGiven(): Boolean = store.data.first().consentIsCurrent()

    override suspend fun setConsent(given: Boolean) = settings.writeContributeConsent(given)

    override suspend fun hasStaleConsent(): Boolean {
        val prefs = store.data.first()
        return isConsentStale(prefs[ContributionKeys.CONSENT_GIVEN], prefs[ContributionKeys.CONSENT_VERSION])
    }

    override suspend fun includeLocation(): Boolean = settings.contributeIncludeLocation.first()

    override suspend fun wifiOnly(): Boolean = settings.contributeWifiOnly.first()

    override suspend fun rememberClientId(id: String?) {
        store.edit { prefs ->
            if (id == null) prefs.remove(ContributionKeys.CLIENT_ID) else prefs[ContributionKeys.CLIENT_ID] = id
        }
    }

    override suspend fun recordSync(status: ContributionSyncStatus) {
        store.edit { prefs ->
            prefs[ContributionKeys.LAST_STATUS] = status.name
            prefs[ContributionKeys.LAST_STATUS_AT] = System.currentTimeMillis()
        }
    }
}
