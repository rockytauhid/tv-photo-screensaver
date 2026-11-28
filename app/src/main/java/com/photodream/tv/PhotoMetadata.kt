// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.text.format.DateFormat
import java.io.File
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PhotoDetails(
    val fileName: String,
    val dateTimeText: String?,
    val camera: String?,
    val shutterSpeed: String?,
    val aperture: String?,
    val iso: String?,
    val focalLength: String?,
    val resolution: String?,
    val fileSizeText: String?,
    val coordinates: DoubleArray?,
    val coordinatesText: String?
)

internal object PhotoMetadata {
    private val dateTags = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_DATETIME
    )

    fun captureTimeMillis(file: File): Long {
        val exif = readExif(file) ?: return file.lastModified()
        return captureTimeMillis(exif, file.lastModified())
    }

    /** The capture date as shown under a photo, for example "Mar 05, 2025". */
    fun dateText(file: File): String =
        dateText(captureTimeMillis(file))

    /** Formats a known capture timestamp without re-reading EXIF from disk. */
    fun dateText(captureTimeMillis: Long): String =
        DateFormat.format("MMM dd, yyyy", captureTimeMillis).toString()

    /** Latitude and longitude from the photo's EXIF, or null when it has no GPS position. */
    fun coordinates(file: File): DoubleArray? {
        val exif = readExif(file) ?: return null
        return coordinates(exif)
    }

    /** Reads full EXIF and file metadata for the Preview Settings details panel. Call off the main thread. */
    @Suppress("DEPRECATION")
    fun readDetails(file: File): PhotoDetails {
        val exif = readExif(file)
        val captureTime = if (exif != null) {
            captureTimeMillis(exif, file.lastModified())
        } else {
            file.lastModified()
        }
        val dateTimeText = if (captureTime > 0L) formatDateTime(captureTime) else null
        val camera = exif?.let {
            formatCamera(
                it.getAttribute(ExifInterface.TAG_MAKE),
                it.getAttribute(ExifInterface.TAG_MODEL)
            )
        }
        val shutterSpeed = exif?.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let(::formatShutterSpeed)
        val aperture = exif?.getAttribute(ExifInterface.TAG_F_NUMBER)?.let(::formatAperture)
        val iso = exif?.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let(::formatIso)
        val focalLength = exif?.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let(::formatFocalLength)
        val dims = readDimensions(file, exif)
        val resolution = dims?.let { formatResolution(it.first, it.second) }
        val fileSizeText = formatFileSize(file.length())
        val coords = exif?.let(::coordinates)
        val coordsText = coords?.let { formatCoordinates(it[0], it[1]) }

        return PhotoDetails(
            fileName = file.name,
            dateTimeText = dateTimeText,
            camera = camera,
            shutterSpeed = shutterSpeed,
            aperture = aperture,
            iso = iso,
            focalLength = focalLength,
            resolution = resolution,
            fileSizeText = fileSizeText,
            coordinates = coords,
            coordinatesText = coordsText
        )
    }

    internal fun formatDateTime(captureTimeMillis: Long, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat("MMM dd, yyyy · h:mm a", locale).format(Date(captureTimeMillis))

    internal fun formatCamera(make: String?, model: String?): String? {
        val cleanMake = make?.trim().orEmpty()
        val cleanModel = model?.trim().orEmpty()
        return when {
            cleanMake.isEmpty() && cleanModel.isEmpty() -> null
            cleanMake.isEmpty() -> cleanModel
            cleanModel.isEmpty() -> cleanMake
            cleanModel.startsWith(cleanMake, ignoreCase = true) -> cleanModel
            else -> "$cleanMake $cleanModel"
        }
    }

    internal fun formatShutterSpeed(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val seconds = parseExifNumber(trimmed) ?: return null
        if (seconds <= 0.0) return null
        return if (seconds < 1.0) {
            val denom = (1.0 / seconds).roundToInt().coerceAtLeast(1)
            "1/$denom s"
        } else {
            val formatted = trimTrailingZeros(seconds)
            "$formatted s"
        }
    }

    internal fun formatAperture(raw: String?): String? {
        val value = parseExifNumber(raw) ?: return null
        if (value <= 0.0) return null
        return "f/${trimTrailingZeros(value)}"
    }

    internal fun formatIso(raw: String?): String? {
        val first = raw?.trim()?.split(',', ' ', ';')?.firstOrNull { it.isNotBlank() } ?: return null
        val value = first.toIntOrNull() ?: return null
        if (value <= 0) return null
        return "ISO $value"
    }

    internal fun formatFocalLength(raw: String?): String? {
        val value = parseExifNumber(raw) ?: return null
        if (value <= 0.0) return null
        return "${trimTrailingZeros(value)} mm"
    }

    internal fun formatResolution(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        val megapixels = (width.toLong() * height.toLong()) / 1_000_000.0
        return if (megapixels >= 1.0) {
            String.format(Locale.US, "%d × %d (%.1f MP)", width, height, megapixels)
        } else {
            "$width × $height"
        }
    }

    internal fun formatFileSize(bytes: Long): String? {
        if (bytes <= 0L) return null
        return when {
            bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000L -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    internal fun formatCoordinates(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.5f, %.5f", latitude, longitude)

    private fun parseExifNumber(raw: String?): Double? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if ('/' in trimmed) {
            val parts = trimmed.split('/')
            if (parts.size != 2) return null
            val num = parts[0].trim().toDoubleOrNull() ?: return null
            val den = parts[1].trim().toDoubleOrNull() ?: return null
            if (den == 0.0) return null
            return num / den
        }
        return trimmed.toDoubleOrNull()
    }

    private fun trimTrailingZeros(value: Double): String {
        val rounded = (value * 10.0).roundToInt() / 10.0
        return if (abs(rounded - rounded.roundToInt()) < 1e-6) {
            rounded.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
    }

    private fun captureTimeMillis(exif: ExifInterface, fallbackMillis: Long): Long =
        dateTags.asSequence()
            .mapNotNull { exif.getAttribute(it)?.let(::parseExifDate) }
            .firstOrNull()
            ?: fallbackMillis

    @Suppress("DEPRECATION")
    private fun coordinates(exif: ExifInterface): DoubleArray? {
        val result = FloatArray(2)
        if (!exif.getLatLong(result)) return null
        return doubleArrayOf(result[0].toDouble(), result[1].toDouble())
    }

    private fun readDimensions(file: File, exif: ExifInterface?): Pair<Int, Int>? {
        var width = exif?.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) ?: 0
        var height = exif?.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0) ?: 0
        if (width <= 0 || height <= 0) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            width = options.outWidth
            height = options.outHeight
        }
        if (width <= 0 || height <= 0) return null
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        ) ?: ExifInterface.ORIENTATION_NORMAL
        val swapped = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
        return if (swapped) height to width else width to height
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
