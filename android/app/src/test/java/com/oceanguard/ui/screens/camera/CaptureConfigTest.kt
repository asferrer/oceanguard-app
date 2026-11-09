package com.oceanguard.ai.ui.screens.camera

import android.app.Application
import androidx.camera.core.ImageCapture
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CaptureConfigTest {

    @Test
    fun resolutionSelectorAsksForTheHighestResolutionIn43() {
        val selector = CaptureConfig.resolutionSelector()
        assertEquals(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY, selector.aspectRatioStrategy)
        assertEquals(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY, selector.resolutionStrategy)
        assertEquals(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE, selector.allowedResolutionMode)
    }

    @Test
    fun stillCaptureMaximisesQualityAndUsesThatSelector() {
        val capture = CaptureConfig.newImageCapture()
        assertEquals(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY, capture.captureMode)
        assertNotNull(capture.resolutionSelector)
        assertEquals(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY, capture.resolutionSelector!!.resolutionStrategy)
        assertEquals(100, CaptureConfig.JPEG_QUALITY)
    }
}
