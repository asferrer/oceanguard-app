package com.oceanguard.ai.data.contribution

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.sqrt

/**
 * The bytes of one contribution and what is known about them. [width] and [height] are the pixels
 * of the stored image (not rotated by EXIF: the same frame as the COCO boxes). [sourceWidth] and
 * [sourceHeight] differ from them only when the photo had to be shrunk to be accepted.
 */
class PreparedPhoto(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val transcoded: Boolean = false,
    val sourceWidth: Int = width,
    val sourceHeight: Int = height,
) {
    val downscaled: Boolean get() = sourceWidth != width || sourceHeight != height
}

/**
 * Bytes that leave the phone for one contribution.
 *
 * Photos go at the resolution they have. The NAS accepts only JPEG (up to [MAX_BYTES] and
 * [MAX_PIXELS]), so a photo in another format (HEIC, PNG, WebP from the gallery) is transcoded
 * to JPEG at quality 95 with its EXIF; if that is too big the quality drops (never below 80) and
 * only then is the photo shrunk, as little as possible. Nothing is shrunk otherwise.
 *
 * With "Include location" on (default) a JPEG goes exactly as stored, full EXIF included:
 * GPS, capture date and time, device make and model. With it off, a temporary copy loses
 * every GPS tag and the XMP packet (which can repeat the position) and that copy is sent;
 * the photo on the phone is never modified. If the tags cannot be removed the photo is not
 * sent at all ([IOException]), never sent with its location by mistake.
 */
object ContributionImage {

    /** Every GPS tag of EXIF 2.32 known to ExifInterface. */
    internal val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_TRACK_REF, ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF, ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_BEARING_REF, ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF, ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DIFFERENTIAL, ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
    )

    /** Descriptive EXIF kept when a photo is converted (the GPS ones are [GPS_TAGS]). */
    internal val EXIF_TAGS = listOf(
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_COLOR_SPACE, ExifInterface.TAG_X_RESOLUTION, ExifInterface.TAG_Y_RESOLUTION,
        ExifInterface.TAG_RESOLUTION_UNIT,
    )

    /** Largest image the NAS takes: under the 40 MiB envelope limit, with room for EXIF and meta. */
    const val MAX_BYTES = 40L * 1024 * 1024 - 256 * 1024
    const val MAX_PIXELS = 120_000_000L
    internal val QUALITIES = listOf(95, 90, 85, 80)
    private const val SHRINK_MARGIN = 0.95

    /** True when the stored file cannot be sent as it is: not a JPEG, or beyond the NAS limits. */
    internal fun needsTranscode(isJpeg: Boolean, bytes: Long, width: Int, height: Int): Boolean =
        !isJpeg || bytes > MAX_BYTES || width.toLong() * height > MAX_PIXELS

    /** Photo to upload; [workDir] holds the temporary copy when the location is removed or re-encoded. */
    fun prepare(photo: File, includeLocation: Boolean, workDir: File): PreparedPhoto {
        if (!photo.isFile) throw IOException("Photo missing: ${photo.name}")
        val (w, h) = bounds(photo)
        if (needsTranscode(isJpeg(photo), photo.length(), w, h)) return transcode(photo, includeLocation, workDir)
        if (includeLocation) return PreparedPhoto(photo.readBytes(), w, h)
        workDir.mkdirs()
        val copy = File.createTempFile("contrib_", ".jpg", workDir)
        try {
            photo.copyTo(copy, overwrite = true)
            removeLocation(copy)
            return PreparedPhoto(copy.readBytes(), w, h)
        } finally {
            copy.delete()
        }
    }

    private fun bounds(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth.coerceAtLeast(0) to o.outHeight.coerceAtLeast(0)
    }

    private fun isJpeg(file: File): Boolean {
        val head = ByteArray(3)
        val n = file.inputStream().use { it.read(head) }
        return n == 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
    }

    /** Full-resolution JPEG of [photo] with its EXIF (without GPS when [includeLocation] is false). */
    private fun transcode(photo: File, includeLocation: Boolean, workDir: File): PreparedPhoto {
        val (srcW, srcH) = bounds(photo)
        if (srcW <= 0 || srcH <= 0) throw IOException("Photo cannot be decoded: ${photo.name}")
        val sample = if (srcW.toLong() * srcH > MAX_PIXELS) 2 else 1
        val decoded = try {
            BitmapFactory.decodeFile(photo.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: OutOfMemoryError) {
            throw IOException("Not enough memory to convert the photo", e)
        } ?: throw IOException("Photo cannot be decoded: ${photo.name}")
        var bitmap = decoded
        try {
            var jpeg = encode(bitmap, QUALITIES.first())
            for (q in QUALITIES.drop(1)) if (jpeg.size > MAX_BYTES) jpeg = encode(bitmap, q)
            while (jpeg.size > MAX_BYTES) {
                val shrunk = shrink(bitmap, jpeg.size)
                if (bitmap !== decoded) bitmap.recycle()
                bitmap = shrunk
                jpeg = encode(bitmap, QUALITIES.last())
            }
            val out = withExif(photo, jpeg, bitmap, includeLocation, workDir)
            return PreparedPhoto(out, bitmap.width, bitmap.height, transcoded = true, srcW, srcH)
        } finally {
            decoded.recycle()
            if (bitmap !== decoded) bitmap.recycle()
        }
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream(1 shl 20).use {
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)
        it.toByteArray()
    }

    /** Smallest reduction that should bring a JPEG of [size] bytes under the limit (size ~ pixels). */
    private fun shrink(bitmap: Bitmap, size: Int): Bitmap {
        val factor = sqrt(MAX_BYTES.toDouble() / size) * SHRINK_MARGIN
        val w = (bitmap.width * factor).toInt().coerceAtLeast(1)
        val h = (bitmap.height * factor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /** Writes the EXIF of [source] into the new JPEG; GPS only when the location is allowed. */
    private fun withExif(source: File, jpeg: ByteArray, bitmap: Bitmap, includeLocation: Boolean, workDir: File): ByteArray {
        workDir.mkdirs()
        val temp = File.createTempFile("contrib_", ".jpg", workDir)
        try {
            temp.writeBytes(jpeg)
            val from = runCatching { ExifInterface(source) }.getOrNull()
            val to = ExifInterface(temp)
            val tags = if (includeLocation) EXIF_TAGS + GPS_TAGS else EXIF_TAGS
            tags.forEach { tag -> from?.getAttribute(tag)?.let { to.setAttribute(tag, it) } }
            to.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, bitmap.width.toString())
            to.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, bitmap.height.toString())
            to.saveAttributes()
            return temp.readBytes()
        } finally {
            temp.delete()
        }
    }

    /** Removes the GPS tags and the XMP packet from [file] in place, then checks they are gone. */
    internal fun removeLocation(file: File) {
        val exif = ExifInterface(file)
        GPS_TAGS.forEach { exif.setAttribute(it, null) }
        exif.setAttribute(ExifInterface.TAG_XMP, null)
        exif.saveAttributes()
        val check = ExifInterface(file)
        if (check.latLong != null || GPS_TAGS.any { check.getAttribute(it) != null }) {
            throw IOException("GPS tags still present after removal")
        }
    }
}
