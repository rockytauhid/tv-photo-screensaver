// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryLayoutTest {
    @Test
    fun sizesGallerySquaresToFitSevenAndThreeQuarterColumnsOn1080p() {
        // 1920x1080 at 320dpi (density 2.0) is 960x540 dp.
        val sizeDp = GalleryActivity.computeThumbnailSizeDp(960, 540)
        assertEquals(110, sizeDp)
        val totalWidthFor7Full = 30 + 7 * sizeDp + 6 * 10
        val totalWidthFor8Full = 30 + 8 * sizeDp + 7 * 10
        assertTrue(totalWidthFor7Full < 960)
        assertTrue(totalWidthFor8Full > 960)
    }

    @Test
    fun clampsThumbnailSizeForCompactScreens() {
        assertTrue(GalleryActivity.computeThumbnailSizeDp(640, 360) >= 56)
    }
}
