// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.content.Context
import android.location.Geocoder
import android.os.Handler
import android.util.LruCache
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Turns photo GPS coordinates into "Suburb, CC" with Android's Geocoder.
 *
 * The app opens no network connection itself: the Geocoder asks the system's location backend, which
 * may be missing on some TVs. Results are cached (memory and a small preferences file) so a name that
 * was found once still shows when the network is down. When no name is available the caller hides the
 * location.
 */
internal object PhotoLocationNames {
    private const val PREFERENCES = "photo_dream_locations"
    private const val MAX_STORED = 5000
    private const val RETRY_DELAY_MILLIS = 2 * 60_000L

    // Lazy so the pure helpers below can be unit-tested on the JVM without Android classes.
    private val memory by lazy { LruCache<String, String>(512) }

    @Volatile
    private var retryAfter = 0L

    fun format(suburb: String?, countryCode: String?): String? {
        val place = suburb?.trim().orEmpty()
        if (place.isEmpty()) return null
        val country = countryCode?.trim().orEmpty()
        return if (country.isEmpty()) place else "$place, $country"
    }

    /** About 100 m: close enough to share a name, fine enough not to cross suburb borders often. */
    fun cacheKey(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.3f,%.3f", latitude, longitude)

    /** Blocking: call from a worker thread. Returns null when no name is available. */
    @Suppress("DEPRECATION")
    fun lookup(context: Context, latitude: Double, longitude: Double): String? {
        val key = cacheKey(latitude, longitude)
        memory.get(key)?.let { return it.ifEmpty { null } }
        val preferences = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(key, null)?.let {
            memory.put(key, it)
            return it.ifEmpty { null }
        }

        if (System.currentTimeMillis() < retryAfter) return null
        if (!Geocoder.isPresent()) return null
        val name = try {
            val address = Geocoder(context.applicationContext, Locale.getDefault())
                .getFromLocation(latitude, longitude, 1)
                ?.firstOrNull()
            if (address == null) {
                ""
            } else {
                format(
                    address.subLocality ?: address.locality ?: address.subAdminArea,
                    address.countryCode
                ).orEmpty()
            }
        } catch (_: IOException) {
            // Offline or backend unavailable: try again later, and store nothing.
            retryAfter = System.currentTimeMillis() + RETRY_DELAY_MILLIS
            return null
        } catch (_: RuntimeException) {
            return null
        }

        memory.put(key, name)
        if (preferences.all.size < MAX_STORED) {
            preferences.edit().putString(key, name).apply()
        }
        return name.ifEmpty { null }
    }
}

/**
 * Loads the location text of one photo at a time on its own thread and reports on the main thread.
 * A newer [load] or [cancel] makes older results get dropped.
 */
internal class PhotoLocationLoader(
    private val context: Context,
    private val handler: Handler
) {
    private var executor: ExecutorService? = null

    @Volatile
    private var request = 0

    fun load(file: File, onResult: (String?) -> Unit) {
        val id = ++request
        val worker = executor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "photo-location").apply { isDaemon = true }
        }.also { executor = it }
        worker.execute {
            if (id != request) return@execute
            val name = PhotoMetadata.coordinates(file)?.let {
                PhotoLocationNames.lookup(context, it[0], it[1])
            }
            handler.post {
                if (id == request) onResult(name)
            }
        }
    }

    fun cancel() {
        request++
    }

    fun shutdown() {
        request++
        executor?.shutdownNow()
        executor = null
    }
}
