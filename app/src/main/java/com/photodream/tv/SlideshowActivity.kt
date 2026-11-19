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
import android.graphics.drawable.BitmapDrawable
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
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Full-screen slideshow with no on-screen buttons: the photo is the hero. Remote keys:
 * Left/Right previous/next photo, Play/Pause (or OK) pause and resume, Back returns to the gallery.
 */
class SlideshowActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var worker: ThreadPoolExecutor? = null
    private var photoLoadTask: Future<*>? = null
    private var scheduledNext: Runnable? = null
    private var isRunning = false
    private var isPaused = false
    private var photos: List<File> = emptyList()
    private var nextPhoto = 0
    private var currentPhotoIndex = -1
    private var displayedPhotoIndex = -1
    private var initialPhotoPath: String? = null
    private var displayGeneration = 0
    private lateinit var imageViews: Array<ImageView>
    private lateinit var dateLabel: TextView
    private lateinit var locationLabel: TextView
    private lateinit var status: TextView
    private lateinit var clock: PhotoClock
    private lateinit var pauseIndicator: ImageView
    private lateinit var locationLoader: PhotoLocationLoader
    private var visibleImage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialPhotoPath = intent.getStringExtra(START_PHOTO_PATH)
        window.requestFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        imageViews = Array(2) {
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.INVISIBLE
                setBackgroundColor(Color.BLACK)
            }
        }
        dateLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        locationLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.RIGHT
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(dateLabel, LinearLayout.LayoutParams(0, -1, 1f))
            addView(locationLabel, LinearLayout.LayoutParams(0, -1, 1f))
        }
        clock = PhotoClock(this, mainHandler, Gravity.START)
        locationLoader = PhotoLocationLoader(this, mainHandler)
        status = TextView(this).apply {
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 22f
            gravity = Gravity.CENTER
        }
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
        val content = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(imageViews[0], FrameLayout.LayoutParams(-1, -1))
            addView(imageViews[1], FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -1))
            addView(footer, FrameLayout.LayoutParams(-1, dp(36), Gravity.BOTTOM))
            addView(
                clock.view,
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT).apply {
                    setMargins(dp(20), dp(12), 0, 0)
                }
            )
            addView(
                pauseIndicator,
                FrameLayout.LayoutParams(dp(140), dp(140), Gravity.CENTER)
            )
        }
        setContentView(content)
    }

    override fun onResume() {
        super.onResume()
        clock.start()
        startSlideshow()
    }

    override fun onPause() {
        clock.stop()
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
        worker = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            ThreadFactory { runnable -> Thread(runnable, "photo-slideshow").apply { isDaemon = true } }
        )

        if (!hasPhotoPermission()) {
            status.setText(R.string.permission_required)
            return
        }
        val folder = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
            .getString(SettingsActivity.PHOTO_FOLDER, null)
            ?.let(::File)
        if (folder == null) {
            status.setText(R.string.choose_photo_folder)
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
                    status.setText(
                        if (favoritesOnly) R.string.no_favorite_photos else R.string.no_photos_found
                    )
                } else {
                    val startingIndex = ordered.indexOfFirst {
                        it.absolutePath == initialPhotoPath
                    }
                    initialPhotoPath = null
                    nextPhoto = startingIndex.takeIf { it >= 0 } ?: 0
                    if (isPaused) {
                        if (imageViews[visibleImage].drawable != null && startingIndex >= 0) {
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
        val baseIndex = currentPhotoIndex.takeIf { it in photos.indices } ?: nextPhoto
        showPhotoAt(PhotoFiles.wrapIndex(baseIndex, direction, photos.size))
    }

    private fun showPhotoAt(index: Int) {
        if (!isRunning || photos.isEmpty()) return
        val currentWorker = worker ?: return
        cancelScheduledNext()
        cancelPhotoLoad()
        val generation = ++displayGeneration
        currentPhotoIndex = index
        nextPhoto = (index + 1) % photos.size
        val photo = photos[index]
        photoLoadTask = currentWorker.submit {
            val frame = decode(photo)
            mainHandler.post {
                if (!isRunning || generation != displayGeneration) {
                    frame?.bitmap?.recycle()
                    return@post
                }
                if (frame == null) {
                    status.setText(R.string.photo_unavailable)
                    status.visibility = View.VISIBLE
                    scheduleNext()
                    return@post
                }
                present(frame)
                scheduleNext()
            }
        }
    }

    private fun present(frame: PhotoFrame) {
        val newIndex = if (imageViews[visibleImage].drawable == null) visibleImage else 1 - visibleImage
        val oldIndex = 1 - newIndex
        val incoming = imageViews[newIndex]
        val outgoing = imageViews[oldIndex]
        incoming.animate().cancel()
        outgoing.animate().cancel()
        val replacedBitmap = (incoming.drawable as? BitmapDrawable)?.bitmap
        incoming.setImageBitmap(frame.bitmap)
        incoming.contentDescription = frame.photoName
        if (replacedBitmap != null && replacedBitmap !== frame.bitmap) replacedBitmap.recycle()
        incoming.visibility = View.VISIBLE
        incoming.alpha = 1f
        incoming.translationX = 0f
        status.visibility = View.GONE

        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        val showDate = preferences.getBoolean(SettingsActivity.SHOW_PHOTO_DATE, true)
        val showLocation = preferences.getBoolean(SettingsActivity.SHOW_PHOTO_LOCATION, true)
        dateLabel.text = if (showDate) frame.date else ""
        locationLabel.text = ""
        if (showLocation) {
            locationLoader.load(frame.file) { locationLabel.text = it.orEmpty() }
        } else {
            locationLoader.cancel()
        }
        displayedPhotoIndex = currentPhotoIndex

        when (preferences.getString(SettingsActivity.TRANSITION, SettingsActivity.FADE)) {
            SettingsActivity.FADE -> {
                if (outgoing.drawable == null) outgoing.visibility = View.INVISIBLE
                incoming.alpha = 0f
                incoming.animate().alpha(1f).setDuration(900).start()
                outgoing.animate().alpha(0f).setDuration(900)
                    .withEndAction { outgoing.visibility = View.INVISIBLE }
                    .start()
            }
            SettingsActivity.SLIDE -> {
                val distance = window.decorView.width.toFloat()
                incoming.translationX = distance
                incoming.animate().translationX(0f).setDuration(700).start()
                outgoing.animate().translationX(-distance).setDuration(700)
                    .withEndAction {
                        outgoing.visibility = View.INVISIBLE
                        outgoing.translationX = 0f
                    }
                    .start()
            }
            else -> outgoing.visibility = View.INVISIBLE
        }
        visibleImage = newIndex
    }

    private fun scheduleNext() {
        if (!isRunning || isPaused) return
        val seconds = SlideshowSpeed.normalize(
            getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        ).intervalSeconds
        cancelScheduledNext()
        val callback = Runnable {
            scheduledNext = null
            showNextPhoto()
        }
        scheduledNext = callback
        mainHandler.postDelayed(callback, seconds * 1000L)
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

    private fun stopSlideshow(preserveCurrentPhoto: Boolean = false) {
        isRunning = false
        displayGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        scheduledNext = null
        cancelPhotoLoad()
        locationLoader.shutdown()
        worker?.shutdownNow()
        worker = null
        photos = emptyList()
        nextPhoto = 0
        currentPhotoIndex = -1
        displayedPhotoIndex = -1
        imageViews.forEachIndexed { index, image ->
            image.animate().cancel()
            if (preserveCurrentPhoto && index == visibleImage && image.drawable != null) {
                image.alpha = 1f
                image.translationX = 0f
                image.visibility = View.VISIBLE
            } else {
                val bitmap = (image.drawable as? BitmapDrawable)?.bitmap
                image.setImageDrawable(null)
                bitmap?.recycle()
                image.visibility = View.INVISIBLE
                image.alpha = 1f
                image.translationX = 0f
            }
        }
        if (!preserveCurrentPhoto) visibleImage = 0
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
                scheduleNext()
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

    companion object {
        const val START_PHOTO_PATH = "start_photo_path"
        const val SHOWN_PHOTO_PATH = "shown_photo_path"
    }
}
