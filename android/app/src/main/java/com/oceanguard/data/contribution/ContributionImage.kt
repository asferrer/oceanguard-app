package com.oceanguard.ai.data.contribution

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

/**
 * Bytes that leave the phone for one contribution.
 *
 * With "Include location" on (default) the photo goes exactly as stored, full EXIF included:
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

    /** Photo bytes to upload; [workDir] holds the temporary copy when the location is removed. */
    fun prepare(photo: File, includeLocation: Boolean, workDir: File): ByteArray {
        if (includeLocation) return photo.readBytes()
        workDir.mkdirs()
        val copy = File.createTempFile("contrib_", ".jpg", workDir)
        try {
            photo.copyTo(copy, overwrite = true)
            removeLocation(copy)
            return copy.readBytes()
        } finally {
            copy.delete()
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
