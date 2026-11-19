// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PhotoFavoritesTest {
    @Test
    fun togglesFavoriteMembershipAndFiltersPhotos() {
        val first = File("first.jpg").absolutePath
        val second = File("second.jpg").absolutePath
        val photos = listOf(File(first), File(second))

        val favorites = PhotoFavorites.togglePath(emptySet(), first)
        val removed = PhotoFavorites.togglePath(favorites, first)

        assertEquals(setOf(first), favorites)
        assertEquals(emptySet<String>(), removed)
        assertEquals(listOf(photos.first()), PhotoFavorites.filter(photos, favorites))
    }
}
