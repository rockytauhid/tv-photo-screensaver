// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

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

    /** The capture date as shown under a photo, for example "Mar 05, 2025". */
    fun dateText(file: File): String =
        dateText(captureTimeMillis(file))

    /** Formats a known capture timestamp without re-reading EXIF from disk. */
    fun dateText(captureTimeMillis: Long): String =
        DateFormat.format("MMM dd, yyyy", captureTimeMillis).toString()

    /** Latitude and longitude from the photo's EXIF, or null when it has no GPS position. */
    fun coordinates(file: File): DoubleArray? {
        val exif = readExif(file) ?: return null
        val result = FloatArray(2)
        if (!exif.getLatLong(result)) return null
        return doubleArrayOf(result[0].toDouble(), result[1].toDouble())
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
