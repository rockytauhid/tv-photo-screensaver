// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
    private lateinit var imageViews: Array<ImageView>
    private lateinit var dateLabel: TextView
    private lateinit var locationLabel: TextView
    private lateinit var status: TextView
    private lateinit var clock: PhotoClock
    private lateinit var locationLoader: PhotoLocationLoader
    private var visibleImage = 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false

        imageViews = Array(2) {
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.INVISIBLE
                setBackgroundColor(Color.BLACK)
            }
        }
        dateLabel = footerLabel(Gravity.START)
        locationLabel = footerLabel(Gravity.END)
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dateLabel, LinearLayout.LayoutParams(0, -2, 1f))
            addView(locationLabel, LinearLayout.LayoutParams(0, -2, 1f))
        }
        clock = PhotoClock(this, mainHandler)
        locationLoader = PhotoLocationLoader(this, mainHandler)
        status = TextView(this).apply {
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 22f
            gravity = Gravity.CENTER
        }
        val content = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(imageViews[0], FrameLayout.LayoutParams(-1, -1))
            addView(imageViews[1], FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -1))
            addView(
                footer,
                FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                    setMargins(dp(40), 0, dp(40), dp(32))
                }
            )
            addView(
                clock.view,
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
                    setMargins(0, dp(24), dp(32), 0)
                }
            )
        }
        setContentView(content)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        isRunning = true
        clock.start()
        worker = Executors.newSingleThreadExecutor()

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
        if (::clock.isInitialized) clock.stop()
        stopSlideshow()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        if (::clock.isInitialized) clock.stop()
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
                    status.setText(R.string.photo_unavailable)
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
        val newIndex = if (imageViews[visibleImage].drawable == null) visibleImage else 1 - visibleImage
        val oldIndex = 1 - newIndex
        val incoming = imageViews[newIndex]
        val outgoing = imageViews[oldIndex]
        incoming.animate().cancel()
        outgoing.animate().cancel()
        val replacedBitmap = (incoming.drawable as? BitmapDrawable)?.bitmap
        incoming.setImageBitmap(frame.bitmap)
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

        val transition = preferences.getString(SettingsActivity.TRANSITION, SettingsActivity.FADE)
        when (transition) {
            SettingsActivity.FADE -> {
                if (outgoing.drawable == null) {
                    outgoing.visibility = View.INVISIBLE
                }
                incoming.alpha = 0f
                incoming.animate().alpha(1f).setDuration(900).start()
                outgoing.animate().alpha(0f).setDuration(900)
                    .withEndAction { outgoing.visibility = View.INVISIBLE }
                    .start()
            }
            SettingsActivity.SLIDE -> {
                val distance = window?.decorView?.width?.toFloat() ?: 1920f
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
        imageViews.forEach {
            it.animate().cancel()
            val bitmap = (it.drawable as? BitmapDrawable)?.bitmap
            it.setImageDrawable(null)
            bitmap?.recycle()
        }
    }

    private fun decode(file: File): PhotoFrame? {
        val bitmap = PhotoImageDecoder.decodeForDisplay(this, file) ?: return null
        return PhotoFrame(bitmap, PhotoMetadata.dateText(file), file)
    }

    private fun footerLabel(gravity: Int): TextView =
        TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setShadowLayer(5f, 1f, 1f, Color.BLACK)
            this.gravity = gravity
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class PhotoFrame(val bitmap: Bitmap, val date: String, val file: File)
}
