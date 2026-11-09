package com.oceanguard.ai.ui.screens.camera

import androidx.camera.core.ImageCapture
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

/**
 * Still capture at the most the sensor gives: the photo is what research training receives, so
 * the camera favours resolution and JPEG quality over shutter speed (the analysis itself works
 * on a smaller copy). 4:3 is the sensor's native ratio; the highest resolution comes first and,
 * where the device offers a high-resolution mode (e.g. 108 MP), it is allowed.
 */
internal object CaptureConfig {
    /** JPEG quality CameraX writes (its maximum). */
    const val JPEG_QUALITY = 100

    fun resolutionSelector(): ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
        .build()

    fun newImageCapture(): ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setResolutionSelector(resolutionSelector())
        .setJpegQuality(JPEG_QUALITY)
        .build()
}
