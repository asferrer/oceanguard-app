package com.oceanguard.ai.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.oceanguard.ai.inference.DebrisClasses
import com.oceanguard.ai.ui.theme.MaterialOther
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

class ClassColorsTest {

    @Test
    fun everyDetectorClassHasItsOwnColourNotTheFallback() {
        DebrisClasses.NAMES.forEach { assertNotEquals(it, MaterialOther, colorForClass(it)) }
    }

    @Test
    fun theEightColoursAreDistinctFromEachOther() {
        val lab = DebrisClasses.NAMES.map { toLab(colorForClass(it)) }
        assertEquals(8, DebrisClasses.NAMES.map { colorForClass(it) }.toSet().size)
        assertTrue("min dE ${minDistance(lab)}", minDistance(lab) >= MIN_DELTA_E)
    }

    @Test
    fun theEightColoursStayApartForColourBlindViewers() {
        CVD_MATRICES.forEach { (name, m) ->
            val lab = DebrisClasses.NAMES.map { toLab(colorForClass(it), m) }
            assertTrue("$name min dE ${minDistance(lab)}", minDistance(lab) >= MIN_DELTA_E_CVD)
        }
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

    private fun minDistance(lab: List<DoubleArray>): Double {
        var min = Double.MAX_VALUE
        for (i in lab.indices) for (j in i + 1 until lab.size) {
            min = minOf(min, sqrt((0..2).sumOf { (lab[i][it] - lab[j][it]).pow(2) }))
        }
        return min
    }

    private fun linear(v: Float): Double =
        if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    /** CIE L*a*b* of [c]; with [cvd] the linear RGB goes first through a colour-blindness matrix. */
    private fun toLab(c: Color, cvd: Array<DoubleArray>? = null): DoubleArray {
        var rgb = doubleArrayOf(linear(c.red), linear(c.green), linear(c.blue))
        if (cvd != null) rgb = DoubleArray(3) { r -> (0..2).sumOf { cvd[r][it] * rgb[it] }.coerceIn(0.0, 1.0) }
        val xyz = doubleArrayOf(
            (0.4124 * rgb[0] + 0.3576 * rgb[1] + 0.1805 * rgb[2]) / 0.9505,
            0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2],
            (0.0193 * rgb[0] + 0.1192 * rgb[1] + 0.9505 * rgb[2]) / 1.089,
        ).map { if (it > 0.008856) it.pow(1.0 / 3) else 7.787 * it + 16.0 / 116 }
        return doubleArrayOf(116 * xyz[1] - 16, 500 * (xyz[0] - xyz[1]), 200 * (xyz[1] - xyz[2]))
    }

    private companion object {
        const val MIN_DELTA_E = 25.0
        const val MIN_DELTA_E_CVD = 10.0
        val CVD_MATRICES = mapOf(
            "protanopia" to arrayOf(
                doubleArrayOf(0.152286, 1.052583, -0.204868),
                doubleArrayOf(0.114503, 0.786281, 0.099216),
                doubleArrayOf(-0.003882, -0.048116, 1.051998),
            ),
            "deuteranopia" to arrayOf(
                doubleArrayOf(0.367322, 0.860646, -0.227968),
                doubleArrayOf(0.280085, 0.672501, 0.047413),
                doubleArrayOf(-0.011820, 0.042940, 0.968881),
            ),
            "tritanopia" to arrayOf(
                doubleArrayOf(1.255528, -0.076749, -0.178779),
                doubleArrayOf(-0.078411, 0.930809, 0.147602),
                doubleArrayOf(0.004733, 0.691367, 0.303900),
            ),
        )
    }
}
