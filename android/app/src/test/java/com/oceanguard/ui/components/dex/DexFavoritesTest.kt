package com.oceanguard.ai.ui.components.dex

import org.junit.Assert.assertEquals
import org.junit.Test

class DexFavoritesTest {

    private val items = listOf("a" to true, "b" to false, "c" to true)

    @Test
    fun withTheFilterOffEveryCardStays() {
        assertEquals(items, DexFavorites.keep(items, onlyFavorites = false) { it.second })
    }

    @Test
    fun withTheFilterOnOnlyFavouritesStayInOrder() {
        assertEquals(listOf("a", "c"), DexFavorites.keep(items, onlyFavorites = true) { it.second }.map { it.first })
        assertEquals(emptyList<Pair<String, Boolean>>(), DexFavorites.keep(listOf("x" to false), true) { it.second })
    }
}
