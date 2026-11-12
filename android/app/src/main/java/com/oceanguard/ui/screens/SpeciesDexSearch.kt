package com.oceanguard.ai.ui.screens

import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.ui.components.dex.DexItem
import java.text.Normalizer

/**
 * Pure filtering logic of the BioDex search field.
 *
 * Matching is case- and accent-insensitive. A discovered species matches when every word of the query
 * appears in at least one of: its common name in the UI language, its common names in any other
 * language, its scientific name or its species key.
 *
 * Discovery rule: the grid shows an undiscovered species as a silhouette with a "?" instead of its
 * name, and no scientific name. To keep that part of the game, undiscovered species are never matched
 * (nor revealed) by a non-blank query; they only appear again when the query is cleared.
 */
object SpeciesDexSearch {

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    /** Lower-cases [text] and strips its accents ("Tortuga Cáscara" becomes "tortuga cascara"). */
    fun normalize(text: String): String =
        DIACRITICS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase().trim()

    /** Words of a normalized query; empty for a blank one. */
    internal fun tokens(query: String): List<String> =
        normalize(query).split(WHITESPACE).filter { it.isNotEmpty() }

    /**
     * Returns the cards of [items] that match [query]. A blank query returns [items] unchanged.
     *
     * @param entryOf catalogue lookup by species key; null while the catalogue is not loaded, in which
     *                case only the card's own name, scientific name and key are searched.
     */
    fun filter(
        items: List<DexItem>,
        query: String,
        entryOf: (String) -> SpeciesCatalogEntry?,
    ): List<DexItem> {
        val words = tokens(query)
        if (words.isEmpty()) return items
        return items.filter { it.discovered && matches(words, searchableTexts(it, entryOf(it.key))) }
    }

    private fun matches(words: List<String>, texts: List<String>): Boolean =
        words.all { word -> texts.any { it.contains(word) } }

    private fun searchableTexts(item: DexItem, entry: SpeciesCatalogEntry?): List<String> {
        val raw = buildList {
            add(item.displayName)
            add(item.key)
            item.subtitle?.let(::add)
            if (entry != null) {
                add(entry.scientificName)
                addAll(entry.commonNames.values)
            }
        }
        return raw.map(::normalize)
    }
}
