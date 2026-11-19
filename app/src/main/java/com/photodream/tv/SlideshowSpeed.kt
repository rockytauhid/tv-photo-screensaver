// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.content.SharedPreferences

internal enum class SlideshowSpeed(
    val intervalSeconds: Int,
    val labelRes: Int
) {
    SLOW(15, R.string.speed_slow),
    MEDIUM(8, R.string.speed_medium),
    FAST(3, R.string.speed_fast);

    fun next(): SlideshowSpeed = when (this) {
        SLOW -> MEDIUM
        MEDIUM -> FAST
        FAST -> SLOW
    }

    companion object {
        fun fromIntervalSeconds(seconds: Int): SlideshowSpeed = when {
            seconds < 6 -> FAST
            seconds < 12 -> MEDIUM
            else -> SLOW
        }

        fun normalize(preferences: SharedPreferences): SlideshowSpeed {
            val seconds = preferences.getInt(
                SettingsActivity.INTERVAL_SECONDS,
                MEDIUM.intervalSeconds
            )
            val speed = fromIntervalSeconds(seconds)
            if (seconds != speed.intervalSeconds) {
                preferences.edit()
                    .putInt(SettingsActivity.INTERVAL_SECONDS, speed.intervalSeconds)
                    .apply()
            }
            return speed
        }
    }
}
