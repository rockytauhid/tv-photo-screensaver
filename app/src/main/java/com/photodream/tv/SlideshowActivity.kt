// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.widget.ImageView
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
    private var photos: List<File> = emptyList()
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
    private lateinit var pauseIndicator: ImageView
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
        pauseIndicator = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(36), dp(36), dp(36), dp(36))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99000000.toInt())
                setStroke(dp(3), Color.WHITE)
            }
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        stage.root.addView(
            pauseIndicator,
            FrameLayout.LayoutParams(dp(140), dp(140), Gravity.CENTER)
        )
        setContentView(stage.root)
    }

    override fun onResume() {
        super.onResume()
        stage.clock.start()
        startSlideshow()
    }

    override fun onPause() {
        stage.clock.stop()
        initialPhotoPath =
            photos.getOrNull(displayedPhotoIndex)?.absolutePath ?: initialPhotoPath
        stopSlideshow(preserveCurrentPhoto = true)
        super.onPause()
    }

    override fun onDestroy() {
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
                    val startingIndex = ordered.indexOfFirst {
                        it.absolutePath == initialPhotoPath
                    }
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

        val cached = takePrefetched(index)
        if (cached != null) {
            onFrameReady(cached)
            return
        }

        val photo = photos[index]
        photoLoadTask = currentWorker.submit {
            val frame = decode(photo)
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
        val targetIndex = nextPhoto
        if (prefetched?.index == targetIndex) return
        clearPrefetched()
        cancelPrefetch()
        val generation = displayGeneration
        val photo = photos[targetIndex]
        prefetchTask = currentWorker.submit {
            val frame = decode(photo) ?: return@submit
            mainHandler.post {
                if (!isRunning || generation != displayGeneration || nextPhoto != targetIndex) {
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
        val distance = window.decorView.width.toFloat().takeIf { it > 0f } ?: 1920f
        stage.present(frame.bitmap, frame.photoName, preferences.transition, distance)

        stage.dateLabel.text = if (preferences.showPhotoDate) frame.date else ""
        stage.locationLabel.text = ""
        if (preferences.showPhotoLocation) {
            locationLoader.load(frame.file) { stage.locationLabel.text = it.orEmpty() }
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

    private fun decode(file: File): PhotoFrame? {
        val bitmap = PhotoImageDecoder.decodeForDisplay(this, file) ?: return null
        return PhotoFrame(bitmap, PhotoMetadata.dateText(file), file, file.name)
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
        showPlaybackIndicator(paused)
    }

    /** Big circled icon in the middle: a pause icon that stays while paused, a play icon that fades out. */
    private fun showPlaybackIndicator(paused: Boolean) {
        pauseIndicator.animate().cancel()
        pauseIndicator.setImageResource(if (paused) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        pauseIndicator.visibility = View.VISIBLE
        pauseIndicator.alpha = 1f
        if (!paused) {
            pauseIndicator.animate()
                .alpha(0f)
                .setStartDelay(1200)
                .setDuration(1500)
                .withEndAction { pauseIndicator.visibility = View.GONE }
                .start()
        }
    }

    private fun returnToGallery() {
        val shownPhotoPath = photos.getOrNull(displayedPhotoIndex)?.absolutePath
            ?: photos.getOrNull(currentPhotoIndex)?.absolutePath
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
        val file: File,
        val photoName: String
    )

    private data class PrefetchedFrame(val index: Int, val frame: PhotoFrame)

    companion object {
        const val START_PHOTO_PATH = "start_photo_path"
        const val SHOWN_PHOTO_PATH = "shown_photo_path"
    }
}
