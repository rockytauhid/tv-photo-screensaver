// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class PhotoMetadataTest {
    @Test
    fun formatCameraCombinesAndDeduplicatesMakeAndModel() {
        assertEquals("Sony ILCE-7M4", PhotoMetadata.formatCamera("Sony", "ILCE-7M4"))
        assertEquals("Google Pixel 8a", PhotoMetadata.formatCamera("Google", "Google Pixel 8a"))
        assertEquals("Pixel 8a", PhotoMetadata.formatCamera(null, "Pixel 8a"))
        assertEquals("Canon", PhotoMetadata.formatCamera("Canon", "  "))
        assertNull(PhotoMetadata.formatCamera(" ", null))
    }

    @Test
    fun formatShutterSpeedFormatsFractionsAndWholeSeconds() {
        assertEquals("1/250 s", PhotoMetadata.formatShutterSpeed("0.004"))
        assertEquals("1/2000 s", PhotoMetadata.formatShutterSpeed("0.0005"))
        assertEquals("1/2 s", PhotoMetadata.formatShutterSpeed("1/2"))
        assertEquals("2 s", PhotoMetadata.formatShutterSpeed("2.0"))
        assertEquals("1.5 s", PhotoMetadata.formatShutterSpeed("1.5"))
        assertNull(PhotoMetadata.formatShutterSpeed("0"))
        assertNull(PhotoMetadata.formatShutterSpeed("invalid"))
    }

    @Test
    fun formatApertureFormatsDecimalsAndRationals() {
        assertEquals("f/1.8", PhotoMetadata.formatAperture("1.8"))
        assertEquals("f/2.8", PhotoMetadata.formatAperture("28/10"))
        assertEquals("f/2", PhotoMetadata.formatAperture("2.0"))
        assertNull(PhotoMetadata.formatAperture(null))
        assertNull(PhotoMetadata.formatAperture("0"))
    }

    @Test
    fun formatIsoParsesSingleOrListValue() {
        assertEquals("ISO 100", PhotoMetadata.formatIso("100"))
        assertEquals("ISO 400", PhotoMetadata.formatIso("400, 400"))
        assertNull(PhotoMetadata.formatIso("0"))
        assertNull(PhotoMetadata.formatIso(""))
    }

    @Test
    fun formatFocalLengthFormatsRationalsAndDecimals() {
        assertEquals("35 mm", PhotoMetadata.formatFocalLength("35/1"))
        assertEquals("50 mm", PhotoMetadata.formatFocalLength("500/10"))
        assertEquals("24.5 mm", PhotoMetadata.formatFocalLength("24.5"))
        assertNull(PhotoMetadata.formatFocalLength("10/0"))
    }

    @Test
    fun formatResolutionIncludesMegapixelsWhenAtLeastOneMp() {
        assertEquals("4032 × 3024 (12.2 MP)", PhotoMetadata.formatResolution(4032, 3024))
        assertEquals("800 × 600", PhotoMetadata.formatResolution(800, 600))
        assertNull(PhotoMetadata.formatResolution(0, 1080))
    }

    @Test
    fun formatFileSizeFormatsBytesKbAndMb() {
        assertEquals("3.4 MB", PhotoMetadata.formatFileSize(3_400_000L))
        assertEquals("820 KB", PhotoMetadata.formatFileSize(820_000L))
        assertEquals("512 B", PhotoMetadata.formatFileSize(512L))
        assertNull(PhotoMetadata.formatFileSize(0L))
    }

    @Test
    fun formatCoordinatesUsesFiveDecimalPlaces() {
        assertEquals("-27.46980, 153.02510", PhotoMetadata.formatCoordinates(-27.4698, 153.0251))
    }

    @Test
    fun formatDateTimeRespects12HourAnd24HourFormats() {
        val text12 = PhotoMetadata.formatDateTime(1_700_000_000_000L, is24Hour = false, locale = Locale.US)
        val text24 = PhotoMetadata.formatDateTime(1_700_000_000_000L, is24Hour = true, locale = Locale.US)
        assertEquals(true, text12.contains("2023") && (text12.endsWith("AM") || text12.endsWith("PM")))
        assertEquals(true, text24.contains("2023") && !text24.endsWith("AM") && !text24.endsWith("PM"))
    }
}
