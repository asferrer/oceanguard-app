package com.oceanguard.ai.inference.species

import com.oceanguard.ai.data.species.SpeciesCatalogEntry

/** Best catalogue group of an unidentified photo and its summed probability. */
data class GroupGuess(val key: String, val confidence: Float)

/**
 * Group fallback (pack manifest `groupFallback`): when no species is confident, the probability
 * of a whole taxonomic group (e.g. `sharks_rays`) can still be high, because the photo shows a
 * relative of a catalogued species. Pure functions, testable on the JVM.
 */
object GroupFallback {

    /**
     * Sum the species probabilities [probabilities] (key, probability) per group.
     * Species without a group (null) are ignored; the result is capped at 1.
     */
    fun groupSums(
        probabilities: List<Pair<String, Float>>,
        groupOf: (String) -> String?,
    ): Map<String, Float> {
        val sums = mutableMapOf<String, Double>()
        for ((key, p) in probabilities) {
            val group = groupOf(key)?.takeIf { it.isNotBlank() } ?: continue
            sums[group] = (sums[group] ?: 0.0) + p
        }
        return sums.mapValues { it.value.coerceIn(0.0, 1.0).toFloat() }
    }

    /**
     * The group with the highest summed probability when it reaches [threshold]; null when the
     * pack has no `groupFallback` ([threshold] null), there are no groups, or none is high enough.
     */
    fun best(groupProbabilities: Map<String, Float>, threshold: Float?): GroupGuess? {
        if (threshold == null) return null
        val top = groupProbabilities.maxByOrNull { it.value } ?: return null
        return if (top.value >= threshold) GroupGuess(top.key, top.value) else null
    }

    /**
     * Display names of the first [limit] [candidates]: localised common name, English common
     * name, then the scientific name. Duplicates are dropped, keeping the order.
     */
    fun candidateNames(
        candidates: List<SpeciesMatch>,
        entryOf: (String) -> SpeciesCatalogEntry?,
        language: String,
        limit: Int = 3,
    ): List<String> = candidates.take(limit).map { m ->
        val names = entryOf(m.speciesKey)?.commonNames
        names?.get(language)?.takeIf { it.isNotBlank() }
            ?: names?.get("en")?.takeIf { it.isNotBlank() }
            ?: m.scientificName
    }.distinct()
}
