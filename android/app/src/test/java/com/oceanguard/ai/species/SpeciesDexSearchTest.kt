package com.oceanguard.ai.species

import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.data.species.SpeciesDexEntry
import com.oceanguard.ai.ui.components.dex.DexItem
import com.oceanguard.ai.ui.screens.SpeciesDexSearch
import com.oceanguard.ai.ui.screens.home.recentSpeciesSprites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Tests of the BioDex search filter and of the Home card's recent-sprites selection. */
class SpeciesDexSearchTest {

    private fun entry(key: String, scientific: String, names: Map<String, String>, sprite: String? = null) =
        SpeciesCatalogEntry(key, null, scientific, names, sprite, emptyList())

    private val turtle = entry("137205", "Caretta caretta", mapOf("en" to "Loggerhead turtle", "es" to "Tortuga boba"))
    private val octopus = entry("11", "Octopus vulgaris", mapOf("en" to "Common octopus", "es" to "Pulpo común"))
    private val hidden = entry("99", "Mola mola", mapOf("en" to "Ocean sunfish", "es" to "Pez luna"))
    private val catalog = listOf(turtle, octopus, hidden).associateBy { it.speciesKey }

    private fun item(e: SpeciesCatalogEntry, discovered: Boolean, lang: String = "es") = DexItem(
        key = e.speciesKey,
        displayName = if (discovered) e.commonNames.getValue(lang) else "?",
        spritePath = null,
        discovered = discovered,
        count = if (discovered) 1 else 0,
        isFavorite = false,
        subtitle = if (discovered) e.scientificName else null,
    )

    

    private val items = listOf(item(turtle, true), item(octopus, true), item(hidden, false))

    private fun search(q: String) = SpeciesDexSearch.filter(items, q, catalog::get).map { it.key }

    @Test fun `blank query returns every card including locked ones`() {
        assertSame(items, SpeciesDexSearch.filter(items, "  ", catalog::get))
    }

    @Test fun `matches common name in the UI language ignoring case and accents`() {
        assertEquals(listOf("11"), search("PULPO"))
        assertEquals(listOf("11"), search("comun"))
    }

    @Test fun `matches common names in other languages`() {
        assertEquals(listOf("137205"), search("loggerhead"))
    }

    @Test fun `matches scientific name and species key`() {
        assertEquals(listOf("137205"), search("caretta"))
        assertEquals(listOf("11"), search("octopus vulg"))
        assertEquals(listOf("137205"), search("1372"))
    }

    @Test fun `every word of the query must match`() {
        assertEquals(listOf("11"), search("pulpo octopus"))
        assertEquals(emptyList<String>(), search("pulpo caretta"))
    }

    @Test fun `undiscovered species never match`() {
        assertEquals(emptyList<String>(), search("mola"))
        assertEquals(emptyList<String>(), search("sunfish"))
        assertEquals(emptyList<String>(), search("99"))
    }

    @Test fun `works without a loaded catalogue using the card texts`() {
        val found = SpeciesDexSearch.filter(items, "tortuga") { null }.map { it.key }
        assertEquals(listOf("137205"), found)
    }

    @Test fun `normalize strips accents and case`() {
        assertEquals("pez luna", SpeciesDexSearch.normalize("  Pez LÚNA "))
    }

    private fun dex(key: String, firstSeen: Long) = SpeciesDexEntry(
        speciesKey = key, scientificName = key, firstSeenAt = firstSeen,
        firstSeenObservationId = 0, lastSeenAt = 0,
    )

    @Test fun `recent sprites are the newest discoveries first and capped`() {
        val withSprites = mapOf(
            "a" to entry("a", "A", emptyMap(), "sprites/a.webp"),
            "b" to entry("b", "B", emptyMap(), null),
            "c" to entry("c", "C", emptyMap(), "sprites/c.webp"),
        )
        val dexEntries = listOf(dex("a", 1), dex("b", 3), dex("c", 2))
        assertEquals(
            listOf("sprites/b.webp", "sprites/c.webp"),
            recentSpeciesSprites(dexEntries, withSprites::get, limit = 2),
        )
        assertEquals(listOf<String?>(null), recentSpeciesSprites(listOf(dex("z", 1)), { null }))
    }
}
