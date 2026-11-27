// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.content.SharedPreferences
import java.io.File

internal object PhotoFavorites {
    private const val FAVORITE_PATHS = "favorite_photo_paths"

    fun load(preferences: SharedPreferences): Set<String> =
        preferences.getStringSet(FAVORITE_PATHS, emptySet())?.toSet().orEmpty()

    fun save(preferences: SharedPreferences, paths: Set<String>) {
        val editor = preferences.edit().putStringSet(FAVORITE_PATHS, paths.toSet())
        if (paths.isEmpty()) {
            editor.putBoolean(SettingsActivity.PLAY_FAVORITES_ONLY, false)
        }
        editor.apply()
    }

    fun toggle(preferences: SharedPreferences, photo: File): Boolean {
        val path = photo.absolutePath
        val paths = load(preferences).toMutableSet()
        val isFavorite = if (paths.remove(path)) {
            false
        } else {
            paths.add(path)
            true
        }
        save(preferences, paths)
        return isFavorite
    }

    fun togglePath(paths: Set<String>, path: String): Set<String> =
        paths.toMutableSet().apply {
            if (!add(path)) remove(path)
        }

    fun filter(photos: List<File>, paths: Set<String>): List<File> =
        photos.filter { it.absolutePath in paths }

    fun hasAnyInFolder(paths: Set<String>, folderPath: String?): Boolean {
        if (folderPath.isNullOrEmpty() || paths.isEmpty()) return false
        val normalizedFolder = File(folderPath).absolutePath
        val prefix = if (normalizedFolder.endsWith(File.separator)) {
            normalizedFolder
        } else {
            normalizedFolder + File.separator
        }
        return paths.any { it.startsWith(prefix) }
    }
}
