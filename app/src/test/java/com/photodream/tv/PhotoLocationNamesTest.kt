// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoLocationNamesTest {
    @Test
    fun formatsSuburbWithAbbreviatedCountry() {
        assertEquals("Indooroopilly, AU", PhotoLocationNames.format("Indooroopilly", "AU"))
        assertEquals("Indooroopilly, AU", PhotoLocationNames.format("  Indooroopilly ", " AU "))
    }

    @Test
    fun keepsJustTheSuburbWhenThereIsNoCountryCode() {
        assertEquals("Indooroopilly", PhotoLocationNames.format("Indooroopilly", null))
        assertEquals("Indooroopilly", PhotoLocationNames.format("Indooroopilly", ""))
    }

    @Test
    fun hidesTheLocationWhenThereIsNoPlaceName() {
        assertNull(PhotoLocationNames.format(null, "AU"))
        assertNull(PhotoLocationNames.format("  ", "AU"))
    }

    @Test
    fun nearbyCoordinatesShareACacheKey() {
        assertEquals(
            PhotoLocationNames.cacheKey(-27.49801, 152.97302),
            PhotoLocationNames.cacheKey(-27.49804, 152.97298)
        )
    }
}
