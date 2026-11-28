// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Full-screen slideshow that mirrors [PhotoDream] visually, with no on-screen buttons. Remote keys:
 * Left/Right previous/next photo, Play/Pause (or OK) pause and resume, Back returns to the gallery.
 */
class SlideshowActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var worker: ThreadPoolExecutor? = null
    private var photoLoadTask: Future<*>? = null
    private var prefetchTask: Future<*>? = null
    private var scheduledNext: Runnable? = null
    private var isRunning = false
    private var isPaused = false
    private var photos: List<PhotoRef> = emptyList()
    private var nextPhoto = 0
    private var currentPhotoIndex = -1
    private var displayedPhotoIndex = -1
    private var initialPhotoPath: String? = null
    private var displayGeneration = 0
    private var consecutiveFailures = 0
    private var prefetched: PrefetchedFrame? = null
    private lateinit var preferences: PhotoPreferences
    private lateinit var repository: PhotoRepository
    private lateinit var stage: SlideshowStage
    private lateinit var pauseIndicator: TextView
    private var pausePulseAnimator: ObjectAnimator? = null
    private lateinit var locationLoader: PhotoLocationLoader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = PhotoPreferences(this)
        repository = PhotoRepository(this)
        initialPhotoPath = intent.getStringExtra(START_PHOTO_PATH)
        window.requestFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        stage = SlideshowStage(this, mainHandler)
        locationLoader = PhotoLocationLoader(this, mainHandler)
        pauseIndicator = TextView(this).apply {
            setText(R.string.slideshow_paused)
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = TvDesignTokens.fontLight
            setShadowLayer(8f, 0f, 2f, 0xDD000000.toInt())
            visibility = View.GONE
        }
        stage.root.addView(
            pauseIndicator,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(TV_HORIZONTAL_MARGIN_DP), dp(28), 0, 0)
            }
        )
        setContentView(stage.root)
    }

    override fun onResume() {
        super.onResume()
        stage.clock.start()
        if (isPaused) startPausePulse()
        startSlideshow()
    }

    override fun onPause() {
        stage.clock.stop()
        stopPausePulse()
        initialPhotoPath =
            photos.getOrNull(displayedPhotoIndex)?.id ?: initialPhotoPath
        stopSlideshow(preserveCurrentPhoto = true)
        super.onPause()
    }

    override fun onDestroy() {
        stopPausePulse()
        stopSlideshow()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    setPlaybackPaused(true)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    setPlaybackPaused(false)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    togglePlayback()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0) {
            returnToGallery()
            return true
        }
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> 0
        }
        if (direction != 0) {
            movePhoto(direction)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        returnToGallery()
    }

    private fun startSlideshow() {
        if (isRunning) return
        isRunning = true
        consecutiveFailures = 0
        worker = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(4),
            ThreadFactory { runnable -> Thread(runnable, "photo-slideshow").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardOldestPolicy()
        )

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
                    val startingIndex = ordered.indexOfFirst { it.id == initialPhotoPath }
                    initialPhotoPath = null
                    nextPhoto = startingIndex.takeIf { it >= 0 } ?: 0
                    if (isPaused) {
                        if (stage.imageViews[stage.visibleImage].drawable != null && startingIndex >= 0) {
                            currentPhotoIndex = startingIndex
                            displayedPhotoIndex = startingIndex
                            nextPhoto = (startingIndex + 1) % photos.size
                        }
                    } else {
                        showNextPhoto()
                    }
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
                val currentId = photos.getOrNull(displayedPhotoIndex)?.id
                    ?: photos.getOrNull(currentPhotoIndex)?.id
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
                    currentPhotoIndex = -1
                    displayedPhotoIndex = -1
                    nextPhoto = 0
                    stage.status.setText(R.string.no_photos_found)
                    stage.status.visibility = View.VISIBLE
                    return@post
                }
                val newCurrentIndex = currentId?.let { id -> photos.indexOfFirst { it.id == id } } ?: -1
                if (newCurrentIndex >= 0) {
                    displayedPhotoIndex = newCurrentIndex
                    currentPhotoIndex = newCurrentIndex
                }
                nextPhoto = PhotoRepository.nextIndexAfterRefresh(photos, currentId, oldNextId)
                val prefetchedId = prefetched?.refId
                if (prefetchedId != null && photos.getOrNull(nextPhoto)?.id != prefetchedId) {
                    clearPrefetched()
                }
            }
        }
    }

    private fun hasPhotoPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun showNextPhoto() {
        if (!isRunning || isPaused || photos.isEmpty()) return
        showPhotoAt(nextPhoto)
    }

    private fun movePhoto(direction: Int) {
        if (!isRunning || photos.isEmpty()) return
        consecutiveFailures = 0
        val baseIndex = currentPhotoIndex.takeIf { it in photos.indices } ?: nextPhoto
        showPhotoAt(PhotoFiles.wrapIndex(baseIndex, direction, photos.size))
    }

    private fun showPhotoAt(index: Int) {
        if (!isRunning || photos.isEmpty()) return
        val currentWorker = worker ?: return
        cancelScheduledNext()
        cancelPhotoLoad()
        cancelPrefetch()
        val generation = ++displayGeneration
        currentPhotoIndex = index
        nextPhoto = (index + 1) % photos.size
        val ref = photos[index]

        val cached = takePrefetched(ref.id)
        if (cached != null) {
            onFrameReady(cached)
            return
        }

        photoLoadTask = currentWorker.submit {
            val frame = decode(ref)
            mainHandler.post {
                if (!isRunning || generation != displayGeneration) {
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
        if (!isRunning || isPaused || photos.size < 2) return
        val targetRef = photos.getOrNull(nextPhoto) ?: return
        if (prefetched?.refId == targetRef.id) return
        clearPrefetched()
        cancelPrefetch()
        val generation = displayGeneration
        prefetchTask = currentWorker.submit {
            val frame = decode(targetRef) ?: return@submit
            mainHandler.post {
                if (!isRunning ||
                    generation != displayGeneration ||
                    photos.getOrNull(nextPhoto)?.id != targetRef.id
                ) {
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
        val distance = window.decorView.width.toFloat().takeIf { it > 0f } ?: 1920f
        stage.present(
            frame.bitmap,
            frame.ref.displayName,
            preferences.transition,
            distance,
            preferences.speed.intervalSeconds * 1000L
        )

        stage.dateLabel.text = if (preferences.showPhotoDate) frame.date else ""
        stage.locationLabel.text = ""
        if (preferences.showPhotoLocation) {
            locationLoader.load(File(frame.ref.id)) { stage.locationLabel.text = it.orEmpty() }
        } else {
            locationLoader.cancel()
        }
        displayedPhotoIndex = currentPhotoIndex
    }

    private fun scheduleNext(delayMs: Long) {
        if (!isRunning || isPaused) return
        cancelScheduledNext()
        val callback = Runnable {
            scheduledNext = null
            showNextPhoto()
        }
        scheduledNext = callback
        mainHandler.postDelayed(callback, delayMs)
    }

    private fun cancelScheduledNext() {
        scheduledNext?.let(mainHandler::removeCallbacks)
        scheduledNext = null
    }

    private fun cancelPhotoLoad() {
        val task = photoLoadTask ?: return
        task.cancel(true)
        if (task is Runnable) worker?.remove(task)
        photoLoadTask = null
    }

    private fun cancelPrefetch() {
        val task = prefetchTask ?: return
        task.cancel(true)
        if (task is Runnable) worker?.remove(task)
        prefetchTask = null
    }

    private fun stopSlideshow(preserveCurrentPhoto: Boolean = false) {
        isRunning = false
        consecutiveFailures = 0
        displayGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        scheduledNext = null
        cancelPhotoLoad()
        cancelPrefetch()
        clearPrefetched()
        locationLoader.shutdown()
        worker?.shutdownNow()
        worker = null
        photos = emptyList()
        nextPhoto = 0
        currentPhotoIndex = -1
        displayedPhotoIndex = -1
        stage.clearImages(preserveVisible = preserveCurrentPhoto)
    }

    private fun decode(ref: PhotoRef): PhotoFrame? {
        val bitmap = PhotoImageDecoder.decodeForDisplay(this, File(ref.id)) ?: return null
        return PhotoFrame(bitmap, PhotoMetadata.dateText(ref.captureTimeMillis), ref)
    }

    private fun togglePlayback() {
        setPlaybackPaused(!isPaused)
    }

    private fun setPlaybackPaused(paused: Boolean) {
        if (isPaused == paused) return
        isPaused = paused
        if (paused) {
            cancelScheduledNext()
            val requestedIndex = currentPhotoIndex.takeIf { it in photos.indices }
            cancelPhotoLoad()
            cancelPrefetch()
            displayGeneration++
            if (displayedPhotoIndex in photos.indices) {
                currentPhotoIndex = displayedPhotoIndex
                nextPhoto = (displayedPhotoIndex + 1) % photos.size
            } else {
                if (requestedIndex != null) nextPhoto = requestedIndex
                currentPhotoIndex = -1
            }
        } else if (isRunning && photos.isNotEmpty()) {
            if (displayedPhotoIndex in photos.indices) {
                currentPhotoIndex = displayedPhotoIndex
                nextPhoto = (displayedPhotoIndex + 1) % photos.size
                scheduleNext(preferences.speed.intervalSeconds * 1000L)
                prefetchNext()
            } else {
                showPhotoAt(nextPhoto.takeIf { it in photos.indices } ?: 0)
            }
        }
        stage.setMotionPaused(paused)
        showPlaybackIndicator(paused)
    }

    /** Subtle gently pulsing "Paused" text in the top-left corner while paused; hidden when playing. */
    private fun showPlaybackIndicator(paused: Boolean) {
        if (paused) {
            pauseIndicator.visibility = View.VISIBLE
            startPausePulse()
        } else {
            stopPausePulse()
            pauseIndicator.visibility = View.GONE
        }
    }

    private fun startPausePulse() {
        stopPausePulse()
        pauseIndicator.alpha = 1f
        pausePulseAnimator = ObjectAnimator.ofFloat(pauseIndicator, View.ALPHA, 1f, 0.45f).apply {
            duration = 1200L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPausePulse() {
        pausePulseAnimator?.cancel()
        pausePulseAnimator = null
        pauseIndicator.alpha = 1f
    }

    private fun returnToGallery() {
        val shownPhotoPath = photos.getOrNull(displayedPhotoIndex)?.id
            ?: photos.getOrNull(currentPhotoIndex)?.id
            ?: initialPhotoPath
        if (shownPhotoPath != null) {
            setResult(
                RESULT_OK,
                Intent().putExtra(SHOWN_PHOTO_PATH, shownPhotoPath)
            )
        }
        finish()
    }

    private class PhotoFrame(
        val bitmap: Bitmap,
        val date: String,
        val ref: PhotoRef
    )

    private data class PrefetchedFrame(val refId: String, val frame: PhotoFrame)

    companion object {
        const val START_PHOTO_PATH = "start_photo_path"
        const val SHOWN_PHOTO_PATH = "shown_photo_path"
    }
}
