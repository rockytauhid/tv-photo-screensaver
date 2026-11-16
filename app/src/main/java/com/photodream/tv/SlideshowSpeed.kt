package com.photodream.tv

import android.content.SharedPreferences

internal enum class SlideshowSpeed(
    val intervalSeconds: Int,
    val labelRes: Int
) {
    SLOW(3, R.string.speed_slow),
    MEDIUM(8, R.string.speed_medium),
    FAST(15, R.string.speed_fast);

    fun next(): SlideshowSpeed = when (this) {
        SLOW -> MEDIUM
        MEDIUM -> FAST
        FAST -> SLOW
    }

    companion object {
        fun fromIntervalSeconds(seconds: Int): SlideshowSpeed = when {
            seconds < 6 -> SLOW
            seconds < 12 -> MEDIUM
            else -> FAST
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
