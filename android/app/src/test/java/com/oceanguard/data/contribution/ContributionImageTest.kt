package com.oceanguard.ai.data.contribution

import android.app.Application
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
        val bytes = ContributionImage.prepare(photo, includeLocation = true, workDir = tmp.newFolder("work"))
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
        val bytes = ContributionImage.prepare(photo, includeLocation = false, workDir = work)

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
        val bytes = ContributionImage.prepare(photo, includeLocation = false, workDir = tmp.newFolder("work"))
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
}
