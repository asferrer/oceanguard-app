package com.oceanguard.ai.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveCadenceMeterTest {

    @Test fun oneFinishedPassMeasuresNothing() {
        assertNull(LiveCadenceMeter().record(1_000))
    }

    @Test fun cadenceIsTheMeanTimeBetweenPasses() {
        val meter = LiveCadenceMeter()
        meter.record(0)
        assertEquals(4_000L, meter.record(4_000))
        assertEquals(3_000L, meter.record(6_000))
    }

    @Test fun onlyTheLastPassesCount() {
        val meter = LiveCadenceMeter(window = 2)
        meter.record(0)
        meter.record(10_000)
        meter.record(14_000)
        assertEquals(4_000L, meter.record(18_000))
    }

    @Test fun timeSpentPausedIsNotCadence() {
        val meter = LiveCadenceMeter()
        meter.record(0)
        meter.record(4_000)
        meter.reset()
        assertNull(meter.record(600_000))
        assertEquals(4_000L, meter.record(604_000))
    }
}
