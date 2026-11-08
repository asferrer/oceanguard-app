package com.oceanguard.ai.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.oceanguard.ai.inference.DebrisClasses
import com.oceanguard.ai.ui.theme.MaterialOther
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ClassColorsTest {

    @Test
    fun everyDetectorClassHasItsOwnColourNotTheFallback() {
        DebrisClasses.NAMES.forEach { assertNotEquals(it, MaterialOther, colorForClass(it)) }
    }

    @Test
    fun lookupIsCaseInsensitive() {
        assertEquals(colorForClass("fishing_net"), colorForClass("Fishing_Net"))
    }

    @Test
    fun unknownClassFallsBack() {
        assertEquals(MaterialOther, colorForClass("something_else"))
    }

    @Test
    fun labelTextIsReadableOnEveryDetectorColour() {
        DebrisClasses.NAMES.forEach {
            val bg = colorForClass(it)
            val fg = onClassColor(bg)
            assertEquals(it, true, contrast(bg, fg) >= 3f)
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }
}
