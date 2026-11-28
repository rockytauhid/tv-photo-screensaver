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
    private var photos: List<PhotoRef> = emptyList()
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
        val favoritePaths = preferences.favoritePaths()
        val source = repository.localSource(folderPath)
        worker?.execute {
            val cached = source.loadCached()
            val initialRefs = if (cached.isNotEmpty()) {
                cached
            } else {
                PhotoRepository.safeRefresh(source, emptyList()).items
            }
            val ordered = PhotoRepository.orderPlaybackRefs(
                initialRefs,
                sortOrder,
                favoritesOnly,
                favoritePaths
            )
            mainHandler.post {
                if (!isRunning) return@post
                photos = ordered
                if (photos.isEmpty()) {
                    stage.status.setText(R.string.no_photos_found)
                    stage.status.visibility = View.VISIBLE
                } else {
                    // Newest first continues where the last session stopped, so a long run does not
                    // keep showing the same newest photos. Shuffle picks a fresh random order anyway.
                    nextPhoto = if (sortOrder == PhotoSortOrder.DATE_TIME) {
                        val lastIndex = preferences.dreamResumePath?.let { resume ->
                            photos.indexOfFirst { it.id == resume }
                        } ?: -1
                        if (lastIndex < 0) 0 else (lastIndex + 1) % photos.size
                    } else {
                        0
                    }
                    showNextPhoto()
                }
                if (cached.isNotEmpty()) {
                    refreshInBackground(source, cached, sortOrder, favoritesOnly, favoritePaths)
                }
            }
        }
    }

    private fun refreshInBackground(
        source: PhotoSource,
        cached: List<PhotoRef>,
        sortOrder: PhotoSortOrder,
        favoritesOnly: Boolean,
        favoritePaths: Set<String>
    ) {
        worker?.execute {
            val refreshed = PhotoRepository.safeRefresh(source, cached)
            if (!refreshed.changed) return@execute
            mainHandler.post {
                if (!isRunning) return@post
                val oldNextId = photos.getOrNull(nextPhoto)?.id
                photos = PhotoRepository.mergeRefreshedPlaybackRefs(
                    photos,
                    refreshed.items,
                    sortOrder,
                    favoritesOnly,
                    favoritePaths
                )
                if (photos.isEmpty()) {
                    clearPrefetched()
                    stage.status.setText(R.string.no_photos_found)
                    stage.status.visibility = View.VISIBLE
                    return@post
                }
                nextPhoto = PhotoRepository.nextIndexAfterRefresh(photos, lastShownPath, oldNextId)
                val prefetchedId = prefetched?.refId
                if (prefetchedId != null && photos.getOrNull(nextPhoto)?.id != prefetchedId) {
                    clearPrefetched()
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
        val ref = photos[index]
        nextPhoto = (index + 1) % photos.size
        val cached = takePrefetched(ref.id)
        if (cached != null) {
            onFrameReady(cached)
            return
        }

        currentWorker.execute {
            val frame = decode(ref)
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
        lastShownPath = frame.ref.id
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
        val targetRef = photos.getOrNull(nextPhoto) ?: return
        if (prefetched?.refId == targetRef.id) return
        clearPrefetched()
        currentWorker.execute {
            val frame = decode(targetRef) ?: return@execute
            mainHandler.post {
                if (!isRunning || photos.getOrNull(nextPhoto)?.id != targetRef.id) {
                    frame.bitmap.recycle()
                    return@post
                }
                clearPrefetched()
                prefetched = PrefetchedFrame(targetRef.id, frame)
            }
        }
    }

    private fun takePrefetched(refId: String): PhotoFrame? {
        val ready = prefetched ?: return null
        prefetched = null
        if (ready.refId == refId) return ready.frame
        ready.frame.bitmap.recycle()
        return null
    }

    private fun clearPrefetched() {
        prefetched?.frame?.bitmap?.recycle()
        prefetched = null
    }

    private fun present(frame: PhotoFrame) {
        val distance = window?.decorView?.width?.toFloat()?.takeIf { it > 0f } ?: 1920f
        stage.present(frame.bitmap, frame.ref.displayName, preferences.transition, distance)

        stage.dateLabel.text = if (preferences.showPhotoDate) frame.date else ""
        stage.locationLabel.text = ""
        if (preferences.showPhotoLocation) {
            locationLoader.load(File(frame.ref.id)) { stage.locationLabel.text = it.orEmpty() }
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

    private fun decode(ref: PhotoRef): PhotoFrame? {
        val bitmap = PhotoImageDecoder.decodeForDisplay(this, File(ref.id)) ?: return null
        return PhotoFrame(bitmap, PhotoMetadata.dateText(ref.captureTimeMillis), ref)
    }

    private data class PhotoFrame(val bitmap: Bitmap, val date: String, val ref: PhotoRef)
    private data class PrefetchedFrame(val refId: String, val frame: PhotoFrame)
}
