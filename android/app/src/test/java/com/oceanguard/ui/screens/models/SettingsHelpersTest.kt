package com.oceanguard.ai.ui.screens.models

import com.oceanguard.ai.inference.DownloadFailureKind
import com.oceanguard.ai.inference.TextModelTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHelpersTest {

    @Test fun logoDoesNothingUntilTheLastFiveTaps() {
        assertEquals(LogoTapResult.NONE, logoTapResult(1, alreadyEnabled = false))
        assertEquals(LogoTapResult.NONE, logoTapResult(15, alreadyEnabled = false))
        assertEquals(LogoTapResult.COUNTDOWN, logoTapResult(16, alreadyEnabled = false))
        assertEquals(LogoTapResult.COUNTDOWN, logoTapResult(20, alreadyEnabled = false))
    }

    @Test fun the21stTapTurnsAdvancedOptionsOn() {
        assertEquals(ADVANCED_TAP_TARGET, 21)
        assertEquals(LogoTapResult.ACTIVATE, logoTapResult(21, alreadyEnabled = false))
    }

    @Test fun tappingWhenAlreadyEnabledDoesNotActivateAgain() {
        assertEquals(LogoTapResult.NONE, logoTapResult(21, alreadyEnabled = true))
        assertEquals(LogoTapResult.NONE, logoTapResult(30, alreadyEnabled = true))
    }

    @Test fun finetunedSuffixCoversEveryState() {
        val missing = "not downloaded"
        assertEquals(" (v1.2 - not downloaded)", finetunedSuffix(false, "", "1.2", missing))
        assertEquals(" (not downloaded)", finetunedSuffix(false, "", null, missing))
        assertEquals(" (v1.0 -> v1.2)", finetunedSuffix(true, "1.0", "1.2", missing))
        assertEquals(" (v1.2)", finetunedSuffix(true, "1.2", "1.2", missing))
        assertEquals(" (v1.2)", finetunedSuffix(true, "", "1.2", missing))
        assertEquals("", finetunedSuffix(true, "", null, missing))
    }

    @Test fun tierLabelNamesTheSizeAndFlagsAMissingFile() {
        val label = tierLabel(TextModelTier.FAST, available = true, notDownloaded = "missing")
        assertEquals("Fast (0.8B) - ~533 MB", label)
        assertTrue(tierLabel(TextModelTier.FAST, available = false, notDownloaded = "missing").endsWith("(missing)"))
    }

    @Test fun everyFailureKindHasItsOwnPlainSentence() {
        val messages = DownloadFailureKind.entries.map { failureMessage(it) }
        assertEquals(DownloadFailureKind.entries.size, messages.toSet().size)
    }
}
