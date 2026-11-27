// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.View
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PhotoDream : DreamService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null
    private var isRunning = false
    private var photos: List<File> = emptyList()
    private var nextPhoto = 0
    private var lastShownPath: String? = null
    private lateinit var stage: SlideshowStage
    private lateinit var locationLoader: PhotoLocationLoader

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false

        stage = SlideshowStage(this, mainHandler)
        locationLoader = PhotoLocationLoader(this, mainHandler)
        setContentView(stage.root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        isRunning = true
        stage.clock.start()
        worker = Executors.newSingleThreadExecutor()

        if (!hasPhotoPermission()) {
            stage.status.setText(R.string.permission_required)
            return
        }

        val folder = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
            .getString(SettingsActivity.PHOTO_FOLDER, null)
            ?.let(::File)
        if (folder == null) {
            stage.status.setText(R.string.choose_photo_folder)
            return
        }

        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        val sortOrder = if (
            preferences.getString(SettingsActivity.PHOTO_ORDER, SettingsActivity.ORDER_DATE_TIME) ==
            SettingsActivity.ORDER_RANDOM
        ) {
            PhotoSortOrder.RANDOM
        } else {
            PhotoSortOrder.DATE_TIME
        }
        worker?.execute {
            val found = PhotoFiles.listImages(folder)
            val favoritesOnly = preferences.getBoolean(SettingsActivity.PLAY_FAVORITES_ONLY, false)
            val favorites = PhotoFavorites.load(preferences)
            val candidates = if (favoritesOnly) PhotoFavorites.filter(found, favorites) else found
            val ordered = PhotoFiles.sortImages(
                candidates,
                sortOrder,
                newestFirst = true,
                captureTime = PhotoMetadata::captureTimeMillis
            )
            mainHandler.post {
                if (!isRunning) return@post
                photos = ordered
                if (photos.isEmpty()) {
                    stage.status.setText(
                        if (favoritesOnly) R.string.no_favorite_photos else R.string.no_photos_found
                    )
                } else {
                    // Newest first continues where the last session stopped, so a long run does not
                    // keep showing the same newest photos. Shuffle picks a fresh random order anyway.
                    nextPhoto = if (sortOrder == PhotoSortOrder.DATE_TIME) {
                        PhotoFiles.resumeIndex(
                            photos,
                            preferences.getString(SettingsActivity.DREAM_RESUME_PATH, null)
                        )
                    } else {
                        0
                    }
                    showNextPhoto()
                }
            }
        }
    }

    override fun onDreamingStopped() {
        if (::stage.isInitialized) stage.clock.stop()
        stopSlideshow()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        if (::stage.isInitialized) stage.clock.stop()
        stopSlideshow()
        super.onDetachedFromWindow()
    }

    private fun hasPhotoPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            "android.permission.READ_MEDIA_IMAGES"
        } else {
            "android.permission.READ_EXTERNAL_STORAGE"
        }
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun showNextPhoto() {
        val currentWorker = worker ?: return
        if (!isRunning || photos.isEmpty()) return

        val photo = photos[nextPhoto]
        nextPhoto = (nextPhoto + 1) % photos.size
        currentWorker.execute {
            val frame = decode(photo)
            mainHandler.post {
                if (!isRunning) {
                    frame?.bitmap?.recycle()
                    return@post
                }
                if (frame == null) {
                    stage.status.setText(R.string.photo_unavailable)
                    stage.status.visibility = View.VISIBLE
                    scheduleNext()
                    return@post
                }
                present(frame)
                lastShownPath = frame.file.absolutePath
                scheduleNext()
            }
        }
    }

    private fun present(frame: PhotoFrame) {
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        val transition = preferences.getString(SettingsActivity.TRANSITION, SettingsActivity.FADE)
        val distance = window?.decorView?.width?.toFloat() ?: 1920f
        stage.present(frame.bitmap, frame.file.name, transition, distance)

        val showDate = preferences.getBoolean(SettingsActivity.SHOW_PHOTO_DATE, true)
        val showLocation = preferences.getBoolean(SettingsActivity.SHOW_PHOTO_LOCATION, true)
        stage.dateLabel.text = if (showDate) frame.date else ""
        stage.locationLabel.text = ""
        if (showLocation) {
            locationLoader.load(frame.file) { stage.locationLabel.text = it.orEmpty() }
        } else {
            locationLoader.cancel()
        }
    }

    private fun scheduleNext() {
        val seconds = SlideshowSpeed.normalize(
            getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        ).intervalSeconds
        mainHandler.postDelayed({ showNextPhoto() }, seconds * 1000L)
    }

    private fun stopSlideshow() {
        lastShownPath?.let {
            getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
                .edit()
                .putString(SettingsActivity.DREAM_RESUME_PATH, it)
                .apply()
        }
        lastShownPath = null
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        if (::locationLoader.isInitialized) locationLoader.shutdown()
        worker?.shutdownNow()
        worker = null
        photos = emptyList()
        if (::stage.isInitialized) stage.clearImages()
    }

    private fun decode(file: File): PhotoFrame? {
        val bitmap = PhotoImageDecoder.decodeForDisplay(this, file) ?: return null
        return PhotoFrame(bitmap, PhotoMetadata.dateText(file), file)
    }

    private data class PhotoFrame(val bitmap: Bitmap, val date: String, val file: File)
}
