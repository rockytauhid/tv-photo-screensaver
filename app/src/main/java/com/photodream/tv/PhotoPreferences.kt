// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Typed wrapper around Photo Dream's shared preferences so preference keys, defaults, and
 * per-folder favorite rules live in one place.
 */
internal class PhotoPreferences(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(
            SettingsActivity.PREFERENCES,
            Context.MODE_PRIVATE
        )
    )

    val raw: SharedPreferences
        get() = preferences

    var photoFolder: String?
        get() = preferences.getString(SettingsActivity.PHOTO_FOLDER, null)
        set(value) {
            preferences.edit().putString(SettingsActivity.PHOTO_FOLDER, value).apply()
        }

    var showPhotoDate: Boolean
        get() = preferences.getBoolean(SettingsActivity.SHOW_PHOTO_DATE, true)
        set(value) {
            preferences.edit().putBoolean(SettingsActivity.SHOW_PHOTO_DATE, value).apply()
        }

    var showPhotoLocation: Boolean
        get() = preferences.getBoolean(SettingsActivity.SHOW_PHOTO_LOCATION, true)
        set(value) {
            preferences.edit().putBoolean(SettingsActivity.SHOW_PHOTO_LOCATION, value).apply()
        }

    var transition: String
        get() = preferences.getString(SettingsActivity.TRANSITION, SettingsActivity.FADE)
            ?: SettingsActivity.FADE
        set(value) {
            preferences.edit().putString(SettingsActivity.TRANSITION, value).apply()
        }

    var shuffle: Boolean
        get() = preferences.getString(
            SettingsActivity.PHOTO_ORDER,
            SettingsActivity.ORDER_DATE_TIME
        ) == SettingsActivity.ORDER_RANDOM
        set(value) {
            preferences.edit()
                .putString(
                    SettingsActivity.PHOTO_ORDER,
                    if (value) SettingsActivity.ORDER_RANDOM else SettingsActivity.ORDER_DATE_TIME
                )
                .apply()
        }

    val sortOrder: PhotoSortOrder
        get() = if (shuffle) PhotoSortOrder.RANDOM else PhotoSortOrder.DATE_TIME

    val speed: SlideshowSpeed
        get() = SlideshowSpeed.normalize(preferences)

    var playFavoritesOnly: Boolean
        get() = preferences.getBoolean(SettingsActivity.PLAY_FAVORITES_ONLY, false)
        set(value) {
            preferences.edit().putBoolean(SettingsActivity.PLAY_FAVORITES_ONLY, value).apply()
        }

    val hasSavedFavoritesFilter: Boolean
        get() = preferences.contains(SettingsActivity.PLAY_FAVORITES_ONLY)

    var dreamResumePath: String?
        get() = preferences.getString(SettingsActivity.DREAM_RESUME_PATH, null)
        set(value) {
            preferences.edit().putString(SettingsActivity.DREAM_RESUME_PATH, value).apply()
        }

    fun cycleSpeed(): SlideshowSpeed {
        val next = speed.next()
        preferences.edit().putInt(SettingsActivity.INTERVAL_SECONDS, next.intervalSeconds).apply()
        return next
    }

    fun cycleTransition(): String {
        val next = when (transition) {
            SettingsActivity.FADE -> SettingsActivity.SLIDE
            SettingsActivity.SLIDE -> SettingsActivity.SLOW_ZOOM
            else -> SettingsActivity.FADE
        }
        transition = next
        return next
    }

    fun favoritePaths(): Set<String> = PhotoFavorites.load(preferences)

    fun hasFavoritesInSelectedFolder(): Boolean =
        PhotoFavorites.hasAnyInFolder(favoritePaths(), photoFolder)

    fun toggleFavorite(photo: File): Boolean = PhotoFavorites.toggle(preferences, photo)
}
