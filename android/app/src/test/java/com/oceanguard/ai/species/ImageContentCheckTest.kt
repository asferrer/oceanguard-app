package com.oceanguard.ai.species

import com.oceanguard.ai.inference.species.ImageContentCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageContentCheckTest {

    private fun solid(argb: Int, n: Int = 1024) = IntArray(n) { argb }

    private fun gradient(n: Int = 1024) = IntArray(n) { i ->
        val v = (i * 255 / (n - 1))
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    @Test
    fun `pure black frame is blank`() = assertTrue(ImageContentCheck.isBlank(solid(0xFF000000.toInt())))

    @Test
    fun `near black sensor noise is blank`() {
        val noisy = IntArray(1024) { i -> val v = i % 3; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        assertTrue(ImageContentCheck.isBlank(noisy))
    }

    @Test
    fun `white and flat grey frames are blank`() {
        assertTrue(ImageContentCheck.isBlank(solid(0xFFFFFFFF.toInt())))
        assertTrue(ImageContentCheck.isBlank(solid(0xFF808080.toInt())))
    }

    @Test
    fun `empty pixel array is blank`() = assertTrue(ImageContentCheck.isBlank(IntArray(0)))

    @Test
    fun `textured frame is not blank`() = assertFalse(ImageContentCheck.isBlank(gradient()))

    @Test
    fun `dim but textured underwater frame is not blank`() {
        val dim = IntArray(1024) { i ->
            val v = 30 + (i % 2) * 40
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        assertFalse(ImageContentCheck.isBlank(dim))
    }
}
