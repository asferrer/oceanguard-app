package com.oceanguard.ai.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportPeriodTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun allTimeHasNoBounds() {
        assertEquals(null to null, ReportPeriodSelection(ReportPeriod.ALL).range(now))
    }

    @Test
    fun presetWindowsLookBackFromNowWithoutAnUpperBound() {
        assertEquals((now - 7 * day) to null, ReportPeriodSelection(ReportPeriod.LAST_7_DAYS).range(now))
        assertEquals((now - 30 * day) to null, ReportPeriodSelection(ReportPeriod.LAST_30_DAYS).range(now))
    }

    @Test
    fun customUsesTheStoredDates() {
        val selection = ReportPeriodSelection(ReportPeriod.CUSTOM, customStartMs = 10L, customEndMs = 20L)
        assertEquals(10L to 20L, selection.range(now))
    }

    @Test
    fun customWithOneOpenEndKeepsItOpen() {
        val selection = ReportPeriodSelection(ReportPeriod.CUSTOM, customStartMs = 10L)
        assertNull(selection.range(now).second)
    }

    @Test
    fun unknownKeyFallsBackToAllTime() {
        assertEquals(ReportPeriod.ALL, ReportPeriod.fromKey("garbage"))
        assertEquals(ReportPeriod.ALL, ReportPeriod.fromKey(null))
        assertEquals(ReportPeriod.LAST_30_DAYS, ReportPeriod.fromKey("30d"))
    }
}
