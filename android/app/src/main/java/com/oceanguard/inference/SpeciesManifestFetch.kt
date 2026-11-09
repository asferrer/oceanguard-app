package com.oceanguard.ai.inference

/** Remote names of the species pack manifest, newest first (the app stores it as `species_pack.json`). */
internal val REMOTE_SPECIES_MANIFESTS = listOf("species_pack_v4.json", "species_pack_v3.json", "species_pack.json")

/**
 * Fetch the first manifest of [names] the server has. A name that is not there
 * ([DownloadFailureKind.NOT_FOUND]) falls through to the next one, so an app that
 * knows the v3 pack still works against a repository that only has the v2 one;
 * any other failure (no connection, server error) is rethrown instead of silently
 * downgrading the pack.
 *
 * @return the name that was fetched.
 */
internal suspend fun fetchFirstAvailable(
    names: List<String> = REMOTE_SPECIES_MANIFESTS,
    fetch: suspend (String) -> Unit,
): String {
    var last: VlmDownloadException? = null
    for (name in names) {
        try {
            fetch(name)
            return name
        } catch (e: VlmDownloadException) {
            if (e.kind != DownloadFailureKind.NOT_FOUND) throw e
            last = e
        }
    }
    throw last ?: IllegalArgumentException("No manifest names")
}
