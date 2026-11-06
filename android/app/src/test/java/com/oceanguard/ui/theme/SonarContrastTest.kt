package com.oceanguard.ai.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2.2 contrast of the token pairs that carry text or identify a control. */
class SonarContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(a.luminance(), b.luminance()).toDouble()
        val lo = minOf(a.luminance(), b.luminance()).toDouble()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertContrast(name: String, fg: Color, bg: Color, min: Double) {
        val ratio = contrast(fg, bg)
        assertTrue("$name = %.2f < $min".format(ratio), ratio >= min)
    }

    private fun checkTextPairs(c: SonarColors) {
        for ((name, bg) in listOf("deep" to c.deep, "hull" to c.hull, "hull2" to c.hull2)) {
            assertContrast("ink on $name", c.ink, bg, AA_TEXT)
            assertContrast("ink2 on $name", c.ink2, bg, AA_TEXT)
            assertContrast("trace on $name", c.trace, bg, AA_TEXT)
            assertContrast("echo on $name", c.echo, bg, AA_TEXT)
            assertContrast("alert on $name", c.alert, bg, AA_TEXT)
        }
        assertContrast("onTrace on trace", c.onTrace, c.trace, AA_TEXT)
        assertContrast("onEcho on echo", c.onEcho, c.echo, AA_TEXT)
        assertContrast("onAlert on alert", c.onAlert, c.alert, AA_TEXT)
        assertContrast("onTraceContainer", c.onTraceContainer, c.traceContainer, AA_TEXT)
        assertContrast("onEchoContainer", c.onEchoContainer, c.echoContainer, AA_TEXT)
        assertContrast("onAlertContainer", c.onAlertContainer, c.alertContainer, AA_TEXT)
        assertContrast("lineStrong on hull", c.lineStrong, c.hull, AA_COMPONENT)
    }

    @Test fun darkPairsMeetAa() = checkTextPairs(SonarDarkColors)

    @Test fun lightPairsMeetAa() = checkTextPairs(SonarLightColors)

    @Test fun darkTokensKeepTheirExactValues() {
        val c = SonarDarkColors
        val table = mapOf(
            c.abyss to 0xFF04121D, c.deep to 0xFF071B2B, c.hull to 0xFF0C2639,
            c.hull2 to 0xFF123349, c.line to 0xFF1E4660, c.ink to 0xFFE9F3F8,
            c.ink2 to 0xFFA9C4D2, c.trace to 0xFF3CE8C4, c.onTrace to 0xFF03261F,
            c.echo to 0xFFFFC24D, c.alert to 0xFFFF9C8A,
        )
        table.forEach { (token, hex) -> assertEquals(Color(hex), token) }
    }

    @Test fun materialRolesFollowTheTable() {
        val scheme = sonarColorScheme(SonarDarkColors, dark = true)
        assertEquals(SonarDarkColors.deep, scheme.background)
        assertEquals(SonarDarkColors.hull, scheme.surface)
        assertEquals(SonarDarkColors.hull2, scheme.surfaceVariant)
        assertEquals(SonarDarkColors.line, scheme.outline)
        assertEquals(SonarDarkColors.ink, scheme.onSurface)
        assertEquals(SonarDarkColors.ink2, scheme.onSurfaceVariant)
        assertEquals(SonarDarkColors.trace, scheme.primary)
        assertEquals(SonarDarkColors.onTrace, scheme.onPrimary)
        assertEquals(SonarDarkColors.alert, scheme.error)
    }

    private companion object {
        const val AA_TEXT = 4.5
        const val AA_COMPONENT = 3.0
    }
}
