// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoPreviewIndicatorsTest {
    @Test
    fun hidesBothIndicatorsWhenThereIsNoOtherPhoto() {
        assertEquals(
            PhotoPreviewActivity.DirectionIndicators(showLeft = false, showRight = false),
            PhotoPreviewActivity.directionIndicators(0, 0)
        )
        assertEquals(
            PhotoPreviewActivity.DirectionIndicators(showLeft = false, showRight = false),
            PhotoPreviewActivity.directionIndicators(0, 1)
        )
        assertEquals(
            PhotoPreviewActivity.DirectionIndicators(showLeft = false, showRight = false),
            PhotoPreviewActivity.directionIndicators(-1, 5)
        )
    }

    @Test
    fun showsOnlyRightAtFirstOnlyLeftAtLastAndBothInTheMiddle() {
        assertEquals(
            PhotoPreviewActivity.DirectionIndicators(showLeft = false, showRight = true),
            PhotoPreviewActivity.directionIndicators(0, 3)
        )
        assertEquals(
            PhotoPreviewActivity.DirectionIndicators(showLeft = true, showRight = true),
            PhotoPreviewActivity.directionIndicators(1, 3)
        )
        assertEquals(
            PhotoPreviewActivity.DirectionIndicators(showLeft = true, showRight = false),
            PhotoPreviewActivity.directionIndicators(2, 3)
        )
    }
}
