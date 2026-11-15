package com.photodream.tv

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
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
import android.widget.TextView
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class SlideshowActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var worker: ThreadPoolExecutor? = null
    private var photoLoadTask: Future<*>? = null
    private var scheduledNext: Runnable? = null
    private var isRunning = false
    private var photos: List<File> = emptyList()
    private var nextPhoto = 0
    private var currentPhotoIndex = -1
    private var displayGeneration = 0
    private lateinit var imageViews: Array<ImageView>
    private lateinit var info: TextView
    private lateinit var status: TextView
    private var visibleImage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setShadowLayer(5f, 1f, 1f, Color.BLACK)
            gravity = Gravity.START
        }
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
                info,
                FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                    setMargins(dp(40), 0, dp(40), dp(32))
                }
            )
        }
        setContentView(content)
    }

    override fun onResume() {
        super.onResume()
        startSlideshow()
    }

    override fun onPause() {
        stopSlideshow()
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
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
        val sortOrder = when (preferences.getString(SettingsActivity.PHOTO_ORDER, SettingsActivity.ORDER_NAME)) {
            SettingsActivity.ORDER_DATE_TIME -> PhotoSortOrder.DATE_TIME
            SettingsActivity.ORDER_RANDOM -> PhotoSortOrder.RANDOM
            else -> PhotoSortOrder.NAME
        }
        val newestFirst = preferences.getBoolean(SettingsActivity.DATE_NEWEST_FIRST, true)
        worker?.execute {
            val found = PhotoFiles.listImages(folder)
            val ordered = PhotoFiles.sortImages(
                found,
                sortOrder,
                newestFirst,
                PhotoMetadata::captureTimeMillis
            )
            mainHandler.post {
                if (!isRunning) return@post
                photos = ordered
                if (photos.isEmpty()) {
                    status.setText(R.string.no_photos_found)
                } else {
                    nextPhoto = 0
                    showNextPhoto()
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
        if (!isRunning || photos.isEmpty()) return
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
        if (replacedBitmap != null && replacedBitmap !== frame.bitmap) replacedBitmap.recycle()
        incoming.visibility = View.VISIBLE
        incoming.alpha = 1f
        incoming.translationX = 0f
        status.visibility = View.GONE

        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        val showMetadata = preferences.getBoolean(SettingsActivity.SHOW_METADATA, true)
        info.text = if (showMetadata) frame.metadata else ""
        info.visibility = if (showMetadata && frame.metadata.isNotEmpty()) View.VISIBLE else View.GONE

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
        val seconds = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
            .getInt(SettingsActivity.INTERVAL_SECONDS, 30)
            .coerceIn(5, 300)
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

    private fun stopSlideshow() {
        isRunning = false
        displayGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        scheduledNext = null
        cancelPhotoLoad()
        worker?.shutdownNow()
        worker = null
        photos = emptyList()
        nextPhoto = 0
        currentPhotoIndex = -1
        visibleImage = 0
        imageViews.forEach {
            it.animate().cancel()
            val bitmap = (it.drawable as? BitmapDrawable)?.bitmap
            it.setImageDrawable(null)
            bitmap?.recycle()
        }
    }

    private fun decode(file: File): PhotoFrame? {
        val bitmap = PhotoImageDecoder.decodeForDisplay(this, file) ?: return null
        return PhotoFrame(bitmap, PhotoMetadata.details(this, file))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class PhotoFrame(val bitmap: Bitmap, val metadata: String)
}
