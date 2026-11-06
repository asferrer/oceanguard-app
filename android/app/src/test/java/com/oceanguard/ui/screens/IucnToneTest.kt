package com.oceanguard.ai.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class IucnToneTest {

    @Test fun threatenedStatuses() {
        listOf("EX", "ew", "CR", "En").forEach { assertEquals(it, IucnTone.THREATENED, iucnTone(it)) }
    }

    @Test fun watchAndSafeStatuses() {
        assertEquals(IucnTone.WATCH, iucnTone("VU"))
        assertEquals(IucnTone.WATCH, iucnTone("nt"))
        assertEquals(IucnTone.SAFE, iucnTone("LC"))
    }

    @Test fun unknownStatusesAreNeutral() {
        assertEquals(IucnTone.UNKNOWN, iucnTone("DD"))
        assertEquals(IucnTone.UNKNOWN, iucnTone("NE"))
        assertEquals(IucnTone.UNKNOWN, iucnTone(""))
    }
}
