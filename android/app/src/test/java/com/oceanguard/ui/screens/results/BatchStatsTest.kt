package com.oceanguard.ai.ui.screens.results

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BatchStatsTest {

    private fun photo(health: Int, types: List<String>, model: String? = "RT-DETRv2") =
        PhotoOutcome(health, types.size, types, model)

    @Test
    fun photoWithoutDetectionsShowsNoHealth() {
        assertNull(displayedHealth(healthScore = 100, totalCount = 0))
        assertEquals(72, displayedHealth(healthScore = 72, totalCount = 3))
    }

    @Test
    fun averageIgnoresPhotosWithoutDetections() {
        val stats = summarizeBatch(
            listOf(photo(40, listOf("BOTTLE")), photo(60, listOf("CAN")), photo(100, emptyList())),
            durationMs = 9_000,
        )
        assertEquals(50, stats.averageHealth)
        assertEquals(2, stats.withDetections)
        assertEquals(1, stats.withoutDetections)
    }

    @Test
    fun averageIsNullWhenNothingWasDetected() {
        val stats = summarizeBatch(listOf(photo(100, emptyList()), photo(100, emptyList())), 1_000)
        assertNull(stats.averageHealth)
        assertEquals(0, stats.totalObjects)
    }

    @Test
    fun failedPhotosAreCountedAndExcluded() {
        val stats = summarizeBatch(listOf(photo(30, listOf("BOTTLE", "CAN")), null), 2_000)
        assertEquals(2, stats.photos)
        assertEquals(1, stats.failed)
        assertEquals(2, stats.totalObjects)
    }

    @Test
    fun emptyBatchHasNoAverageAndNoModel() {
        val stats = summarizeBatch(emptyList(), 0)
        assertNull(stats.averageHealth)
        assertNull(stats.modelLabel)
        assertNull(stats.repeated)
    }

    @Test
    fun repeatedObjectsNeedCountAndShare() {
        val bottles = List(8) { "BOTTLE" }
        assertEquals(RepeatedObjects("BOTTLE", 8), findRepeatedObjects(bottles))
        // Many objects but spread over types: nothing dominates.
        assertNull(findRepeatedObjects(List(6) { "BOTTLE" } + List(6) { "CAN" } + List(6) { "BAG" }))
        // Dominant but too few to matter.
        assertNull(findRepeatedObjects(List(5) { "BOTTLE" }))
        assertNull(findRepeatedObjects(emptyList()))
    }

    @Test
    fun batchWarnsWithTheWorstPhoto() {
        val stats = summarizeBatch(
            listOf(photo(30, List(7) { "CAN" }), photo(20, List(9) { "BOTTLE" })),
            1_000,
        )
        assertNotNull(stats.repeated)
        assertEquals("BOTTLE", stats.repeated?.typeName)
        assertEquals(9, stats.repeated?.count)
    }

    @Test
    fun modelLabelJoinsDistinctNamesAndSkipsMissing() {
        assertEquals("RT-DETRv2", modelLabel(listOf("RT-DETRv2", null, "RT-DETRv2")))
        assertEquals("RT-DETRv2 · Gemma 4 E2B", modelLabel(listOf("RT-DETRv2", "Gemma 4 E2B")))
        assertNull(modelLabel(listOf(null, " ")))
    }

    @Test
    fun durationIsFormattedInSecondsAndMinutes() {
        assertEquals("0 s", formatDuration(-5))
        assertEquals("45 s", formatDuration(45_900))
        assertEquals("2 min 5 s", formatDuration(125_000))
    }

    @Test
    fun secondsPerPhotoNeedsTimeAndPhotos() {
        assertEquals("4.5", secondsPerPhoto(9_000, 2))
        assertNull(secondsPerPhoto(0, 2))
        assertNull(secondsPerPhoto(9_000, 0))
    }
}
