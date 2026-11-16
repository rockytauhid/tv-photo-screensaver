package com.photodream.tv

import android.content.Context
import android.media.ExifInterface
import android.text.format.DateFormat
import java.io.File
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

internal object PhotoMetadata {
    private val dateTags = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_DATETIME
    )

    fun captureTimeMillis(file: File): Long {
        val exif = readExif(file) ?: return file.lastModified()
        return dateTags.asSequence()
            .mapNotNull { exif.getAttribute(it)?.let(::parseExifDate) }
            .firstOrNull()
            ?: file.lastModified()
    }

    fun location(file: File): String? =
        readExif(file)?.let(::location)

    fun details(context: Context, file: File, includeName: Boolean = false): String {
        val lines = ArrayList<String>()
        if (includeName) lines.add(context.getString(R.string.photo_name, file.name))

        val exif = readExif(file)
        val date = exif?.let {
            dateTags.asSequence()
                .mapNotNull { tag -> it.getAttribute(tag) }
                .firstOrNull()
        }
        val dateText = date
            ?.replaceFirst(":", "-")
            ?.replaceFirst(":", "-")
            ?: DateFormat.format("yyyy-MM-dd HH:mm", file.lastModified()).toString()
        lines.add(context.getString(R.string.photo_date, dateText))

        if (exif == null) return lines.joinToString("\n")

        location(exif)?.let { lines.add(context.getString(R.string.photo_location, it)) }
        val camera = listOfNotNull(
            exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf(String::isNotEmpty),
            exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf(String::isNotEmpty)
        ).distinct().joinToString(" ")
        if (camera.isNotEmpty()) lines.add(context.getString(R.string.photo_camera, camera))

        val cameraSettings = ArrayList<String>()
        exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { cameraSettings.add("${it}s") }
        exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { cameraSettings.add("f/$it") }
        exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { cameraSettings.add("ISO $it") }
        exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { cameraSettings.add("${it}mm") }
        if (cameraSettings.isNotEmpty()) lines.add(cameraSettings.joinToString("  "))
        return lines.joinToString("\n")
    }

    private fun location(exif: ExifInterface): String? {
        val coordinates = FloatArray(2)
        if (!exif.getLatLong(coordinates)) return null
        return String.format(Locale.US, "%.4f, %.4f", coordinates[0], coordinates[1])
    }

    private fun readExif(file: File): ExifInterface? =
        try {
            ExifInterface(file.absolutePath)
        } catch (_: IOException) {
            null
        }

    private fun parseExifDate(value: String): Long? =
        try {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                .apply { isLenient = false }
                .parse(value)
                ?.time
        } catch (_: ParseException) {
            null
        }
}
