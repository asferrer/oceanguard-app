package com.oceanguard.ai.data.contribution

/**
 * Last result of talking to the gateway, shown in Settings so a stuck queue is never silent.
 * Stored by name; unknown names read as [NEVER].
 */
enum class ContributionSyncStatus {
    /** No upload attempted yet. */
    NEVER,

    /** Last upload accepted. */
    OK,

    /** This build has no destination (no URL or key). */
    NOT_CONFIGURED,

    /** Device signature rejected (usually the phone clock is far off). */
    AUTH,

    /** This phone failed Android key attestation (unlocked, modified or no secure hardware). */
    ATTESTATION,

    /** Too many uploads; the server asked to wait. */
    RATE_LIMITED,

    /** The free buffer is full for today; retried later (nothing is ever billed). */
    QUOTA,

    /** Server error; retried later. */
    SERVER,

    /** No connection or timeout; retried later. */
    NETWORK,

    /** A photo was refused for its content (size, type, annotations). */
    REJECTED,
    ;

    companion object {
        fun fromName(name: String?): ContributionSyncStatus =
            entries.firstOrNull { it.name == name } ?: NEVER
    }
}
