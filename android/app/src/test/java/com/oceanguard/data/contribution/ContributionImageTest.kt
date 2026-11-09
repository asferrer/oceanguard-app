package com.oceanguard.ai.data.contribution

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ContributionImageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun withLocationTheBytesGoAsStored() {
        val photo = syntheticJpeg(tmp.root)
        val bytes = ContributionImage.prepare(photo, includeLocation = true, workDir = tmp.newFolder("work")).bytes
        assertArrayEquals(photo.readBytes(), bytes)
        val sent = ExifInterface(bytes.inputStream())
        assertNotNull(sent.latLong)
        assertEquals("SynthPhone 1", sent.getAttribute(ExifInterface.TAG_MODEL))
    }

    @Test
    fun withoutLocationTheCopyLosesGpsButKeepsTheRest() {
        val photo = syntheticJpeg(tmp.root)
        val original = photo.readBytes()
        val work = tmp.newFolder("work")
        val bytes = ContributionImage.prepare(photo, includeLocation = false, workDir = work).bytes

        val sent = ExifInterface(bytes.inputStream())
        assertNull(sent.latLong)
        ContributionImage.GPS_TAGS.forEach { assertNull(it, sent.getAttribute(it)) }
        assertEquals("SynthMake", sent.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("SynthPhone 1", sent.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("2026:05:17 10:20:30", sent.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))

        assertArrayEquals("the photo on the phone is untouched", original, photo.readBytes())
        assertNotNull(ExifInterface(photo).latLong)
        assertTrue("temporary copy deleted", work.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun tenMegabytePhotoLosesGpsAndKeepsTheRest() {
        val photo = largeSyntheticJpeg(tmp.root)
        val bytes = ContributionImage.prepare(photo, includeLocation = false, workDir = tmp.newFolder("work")).bytes
        assertTrue(bytes.size >= 10 * 1024 * 1024)
        val sent = ExifInterface(bytes.inputStream())
        assertNull(sent.latLong)
        assertEquals("SynthPhone 1", sent.getAttribute(ExifInterface.TAG_MODEL))
        assertNotNull(ExifInterface(photo).latLong)
    }

    @Test(expected = java.io.IOException::class)
    fun missingPhotoIsNotSendable() {
        ContributionImage.prepare(File(tmp.root, "gone.jpg"), includeLocation = false, workDir = tmp.newFolder("work"))
    }

    @Test
    fun jpegWithinLimitsIsNotTranscoded() {
        val photo = syntheticJpeg(tmp.root)
        val prepared = ContributionImage.prepare(photo, includeLocation = true, workDir = tmp.newFolder("work"))
        assertTrue(!prepared.transcoded && !prepared.downscaled)
        assertEquals(16, prepared.width)
        assertEquals(12, prepared.height)
    }

    @Test
    fun decisionToTranscode() {
        val mib = 1024L * 1024
        assertTrue("not a JPEG", ContributionImage.needsTranscode(false, mib, 4000, 3000))
        assertTrue("too many bytes", ContributionImage.needsTranscode(true, 41 * mib, 4000, 3000))
        assertTrue("too many pixels", ContributionImage.needsTranscode(true, 20 * mib, 16000, 12000))
        assertTrue("108 MP is fine", !ContributionImage.needsTranscode(true, 30 * mib, 12000, 9000))
        assertTrue("a normal photo goes as it is", !ContributionImage.needsTranscode(true, 6 * mib, 4000, 3000))
    }

    @Test
    fun pngFromTheGalleryBecomesAFullResolutionJpeg() {
        val png = File(tmp.root, "shot.png")
        val source = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        png.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val prepared = ContributionImage.prepare(png, includeLocation = false, workDir = tmp.newFolder("work"))

        assertTrue(prepared.transcoded)
        assertTrue(!prepared.downscaled)
        assertEquals(320, prepared.width)
        assertEquals(240, prepared.height)
        assertEquals(listOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), prepared.bytes.take(3))
        val decoded = BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size)
        assertEquals(320, decoded.width)
        assertEquals(240, decoded.height)
    }

    @Test
    fun transcodedPhotoKeepsItsExifExceptLocationWhenOff() {
        val png = File(tmp.root, "geo.png")
        val source = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        png.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ExifInterface(png).apply {
            setAttribute(ExifInterface.TAG_MODEL, "SynthPhone 2")
            setLatLong(10.0, 20.0)
            saveAttributes()
        }
        val withGps = ContributionImage.prepare(png, true, tmp.newFolder("a")).bytes
        val without = ContributionImage.prepare(png, false, tmp.newFolder("b")).bytes
        assertEquals("SynthPhone 2", ExifInterface(withGps.inputStream()).getAttribute(ExifInterface.TAG_MODEL))
        assertNotNull(ExifInterface(withGps.inputStream()).latLong)
        assertEquals("SynthPhone 2", ExifInterface(without.inputStream()).getAttribute(ExifInterface.TAG_MODEL))
        assertNull(ExifInterface(without.inputStream()).latLong)
    }
}
