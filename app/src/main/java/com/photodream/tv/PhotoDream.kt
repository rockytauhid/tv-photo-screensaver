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
    private var consecutiveFailures = 0
    private var prefetched: PrefetchedFrame? = null
    private lateinit var preferences: PhotoPreferences
    private lateinit var repository: PhotoRepository
    private lateinit var stage: SlideshowStage
    private lateinit var locationLoader: PhotoLocationLoader

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false

        preferences = PhotoPreferences(this)
        repository = PhotoRepository(this)
        stage = SlideshowStage(this, mainHandler)
        locationLoader = PhotoLocationLoader(this, mainHandler)
        setContentView(stage.root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        isRunning = true
        consecutiveFailures = 0
        stage.clock.start()
        worker = Executors.newSingleThreadExecutor()

        if (!hasPhotoPermission()) {
            stage.status.setText(R.string.permission_required)
            stage.status.visibility = View.VISIBLE
            return
        }

        val folderPath = preferences.photoFolder
        if (folderPath == null) {
            stage.status.setText(R.string.choose_photo_folder)
            stage.status.visibility = View.VISIBLE
            return
        }

        val sortOrder = preferences.sortOrder
        val favoritesOnly = preferences.playFavoritesOnly
        worker?.execute {
            val ordered = repository.loadPlaybackPhotos(folderPath, preferences)
            mainHandler.post {
                if (!isRunning) return@post
                photos = ordered
                if (photos.isEmpty()) {
                    stage.status.setText(
                        if (favoritesOnly) R.string.no_favorite_photos else R.string.no_photos_found
                    )
                    stage.status.visibility = View.VISIBLE
                } else {
                    // Newest first continues where the last session stopped, so a long run does not
                    // keep showing the same newest photos. Shuffle picks a fresh random order anyway.
                    nextPhoto = if (sortOrder == PhotoSortOrder.DATE_TIME) {
                        PhotoFiles.resumeIndex(photos, preferences.dreamResumePath)
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

        val index = nextPhoto
        nextPhoto = (index + 1) % photos.size
        val cached = takePrefetched(index)
        if (cached != null) {
            onFrameReady(cached)
            return
        }

        val photo = photos[index]
        currentWorker.execute {
            val frame = decode(photo)
            mainHandler.post {
                if (!isRunning) {
                    frame?.bitmap?.recycle()
                    return@post
                }
                if (frame == null) {
                    onDecodeFailure()
                    return@post
                }
                onFrameReady(frame)
            }
        }
    }

    private fun onFrameReady(frame: PhotoFrame) {
        consecutiveFailures = 0
        present(frame)
        lastShownPath = frame.file.absolutePath
        scheduleNext(preferences.speed.intervalSeconds * 1000L)
        prefetchNext()
    }

    private fun onDecodeFailure() {
        consecutiveFailures++
        stage.status.setText(R.string.photo_unavailable)
        stage.status.visibility = View.VISIBLE
        if (!SlideshowStage.shouldStopAfterFailure(consecutiveFailures, photos.size)) {
            scheduleNext(SlideshowStage.FAILURE_SKIP_DELAY_MS)
        }
    }

    private fun prefetchNext() {
        val currentWorker = worker ?: return
        if (!isRunning || photos.size < 2) return
        val targetIndex = nextPhoto
        if (prefetched?.index == targetIndex) return
        clearPrefetched()
        val photo = photos[targetIndex]
        currentWorker.execute {
            val frame = decode(photo) ?: return@execute
            mainHandler.post {
                if (!isRunning || nextPhoto != targetIndex) {
                    frame.bitmap.recycle()
                    return@post
                }
                clearPrefetched()
                prefetched = PrefetchedFrame(targetIndex, frame)
            }
        }
    }

    private fun takePrefetched(index: Int): PhotoFrame? {
        val ready = prefetched ?: return null
        prefetched = null
        if (ready.index == index) return ready.frame
        ready.frame.bitmap.recycle()
        return null
    }

    private fun clearPrefetched() {
        prefetched?.frame?.bitmap?.recycle()
        prefetched = null
    }

    private fun present(frame: PhotoFrame) {
        val distance = window?.decorView?.width?.toFloat()?.takeIf { it > 0f } ?: 1920f
        stage.present(frame.bitmap, frame.file.name, preferences.transition, distance)

        stage.dateLabel.text = if (preferences.showPhotoDate) frame.date else ""
        stage.locationLabel.text = ""
        if (preferences.showPhotoLocation) {
            locationLoader.load(frame.file) { stage.locationLabel.text = it.orEmpty() }
        } else {
            locationLoader.cancel()
        }
    }

    private fun scheduleNext(delayMs: Long) {
        mainHandler.postDelayed({ showNextPhoto() }, delayMs)
    }

    private fun stopSlideshow() {
        lastShownPath?.let { preferences.dreamResumePath = it }
        lastShownPath = null
        consecutiveFailures = 0
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        clearPrefetched()
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
    private data class PrefetchedFrame(val index: Int, val frame: PhotoFrame)
}
