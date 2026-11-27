// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoImageDecoderTest {
    @Test
    fun keepsAPhotoAtLeastAsLargeAsItIsDrawn() {
        // 4000x3000 on a 1920x1080 screen is drawn at 1440x1080, so half size (2000x1500) is kept.
        assertEquals(2, PhotoImageDecoder.chooseSampleSize(4000, 3000, 1920, 1080))
    }

    @Test
    fun neverSamplesBelowTheDrawnSize() {
        val sizes = listOf(
            intArrayOf(4000, 3000),
            intArrayOf(6000, 4000),
            intArrayOf(3000, 4000),
            intArrayOf(8000, 2000),
            intArrayOf(1920, 1080),
            intArrayOf(1000, 700)
        )
        for ((width, height) in sizes.map { it[0] to it[1] }) {
            val scale = minOf(1920.0 / width, 1080.0 / height).coerceAtMost(1.0)
            val sample = PhotoImageDecoder.chooseSampleSize(width, height, 1920, 1080)
            assertTrue("$width x $height", width / sample >= Math.ceil(width * scale).toInt())
            assertTrue("$width x $height", height / sample >= Math.ceil(height * scale).toInt())
        }
    }

    @Test
    fun doesNotSampleSmallPhotos() {
        assertEquals(1, PhotoImageDecoder.chooseSampleSize(1000, 700, 1920, 1080))
        assertEquals(1, PhotoImageDecoder.chooseSampleSize(1920, 1080, 1920, 1080))
    }

    @Test
    fun usesALargerSampleSizeWhenTheMemoryBudgetRequiresIt() {
        // 4000x3000 at half size is 3,000,000 pixels; a 1,000,000 pixel budget needs quarter size.
        assertEquals(4, PhotoImageDecoder.chooseSampleSize(4000, 3000, 1920, 1080, 1_000_000))
    }

    @Test
    fun terminatesWhenMemoryBudgetIsZero() {
        assertTrue(PhotoImageDecoder.chooseSampleSize(100, 50, 1920, 1080, 0) > 0)
    }

    @Test
    fun mapsAllExifOrientationsIncludingMirroredOnes() {
        assertFalse(PhotoImageDecoder.swapsDimensions(ExifInterface.ORIENTATION_NORMAL))
        assertFalse(PhotoImageDecoder.swapsDimensions(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
        assertFalse(PhotoImageDecoder.swapsDimensions(ExifInterface.ORIENTATION_ROTATE_180))
        assertFalse(PhotoImageDecoder.swapsDimensions(ExifInterface.ORIENTATION_FLIP_VERTICAL))
        assertTrue(PhotoImageDecoder.swapsDimensions(ExifInterface.ORIENTATION_ROTATE_90))
        assertTrue(PhotoImageDecoder.swapsDimensions(ExifInterface.ORIENTATION_ROTATE_270))
        assertTrue(PhotoImageDecoder.swapsDimensions(ExifInterface.ORIENTATION_TRANSPOSE))
        assertTrue(PhotoImageDecoder.swapsDimensions(ExifInterface.ORIENTATION_TRANSVERSE))

        assertNull(PhotoImageDecoder.orientationTransform(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(
            PhotoImageDecoder.OrientationTransform(flipHorizontal = true),
            PhotoImageDecoder.orientationTransform(ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        )
        assertEquals(
            PhotoImageDecoder.OrientationTransform(flipVertical = true),
            PhotoImageDecoder.orientationTransform(ExifInterface.ORIENTATION_FLIP_VERTICAL)
        )
        assertEquals(
            PhotoImageDecoder.OrientationTransform(rotationDegrees = 90f, flipHorizontal = true),
            PhotoImageDecoder.orientationTransform(ExifInterface.ORIENTATION_TRANSPOSE)
        )
        assertEquals(
            PhotoImageDecoder.OrientationTransform(rotationDegrees = 270f, flipHorizontal = true),
            PhotoImageDecoder.orientationTransform(ExifInterface.ORIENTATION_TRANSVERSE)
        )
    }
}
