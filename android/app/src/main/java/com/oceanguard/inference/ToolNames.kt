package com.oceanguard.ai.inference

/**
 * One canonical form for a tool name, so what the model called and what a report requires are
 * compared like for like. The SDK reports names as `per-session-details`, the Kotlin methods are
 * `getPerSessionDetails` and the required sets are written `get_per_session_details`: all of
 * them reduce to `per_session_details`.
 */
internal object ToolNames {

    fun canonical(name: String): String {
        val words = name
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase()
            .split('-', '_', ' ')
            .filter { it.isNotEmpty() }
        return words.dropWhile { it == "get" }.joinToString("_")
    }

    fun canonical(names: Collection<String>): Set<String> = names.map(::canonical).toSet()
}
