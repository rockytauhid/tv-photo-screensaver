// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Full-screen preview of one photo. Left/Right move through the gallery's photos. Press Up to reach the
 * icons at the top right (favorite, slideshow, settings) and Down to return to the photo.
 */
class PhotoPreviewActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var indexWorker: ExecutorService? = null
    private var imageWorker: ThreadPoolExecutor? = null
    private var imageLoadTask: Future<*>? = null
    private lateinit var locationLoader: PhotoLocationLoader
    private lateinit var photo: File
    private lateinit var contentRoot: FrameLayout
    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var topIcons: LinearLayout
    private lateinit var favoriteButton: ImageButton
    private lateinit var playSlideshowButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var dateLabel: TextView
    private lateinit var locationLabel: TextView
    private lateinit var optionsOverlay: RightOptionsOverlay
    private lateinit var dateOptionButton: TextView
    private lateinit var locationOptionButton: TextView
    private var optionsVisible = false
    private var favoritesOnly = false
    private var favoritePaths: Set<String> = emptySet()
    private var photos: List<File> = emptyList()
    private var currentIndex = -1
    private var indexReady = false
    private var pendingNavigation = 0
    private var displayGeneration = 0
    private var currentBitmap: Bitmap? = null
    private var currentDate = ""
    private var currentLocation = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.requestFeature(Window.FEATURE_NO_TITLE)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val path = intent.getStringExtra(PHOTO_PATH)
        if (path == null) {
            finish()
            return
        }
        photo = File(path)
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        favoritesOnly = intent.getBooleanExtra(PHOTO_FAVORITES_ONLY, false)
        favoritePaths = PhotoFavorites.load(preferences)
        locationLoader = PhotoLocationLoader(this, mainHandler)
        setContentView(createContentView())
        updateFavoriteButton()
        updateOptionLabels()
        contentRoot.requestFocus()
        imageWorker = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            { runnable -> Thread(runnable, "photo-preview").apply { isDaemon = true } }
        )
        loadPhotoAndIndex()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            !optionsVisible &&
            (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        ) {
            startSlideshowFromPhoto()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isOptionsKey(keyCode) && event.repeatCount == 0) {
            toggleOptions()
            return true
        }
        if (optionsVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closeOptions()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        val onTopIcons = topIcons.hasFocus()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // On the icons, Left/Right move between them; otherwise they change the photo.
                if (onTopIcons) return super.onKeyDown(keyCode, event)
                movePhoto(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> if (!onTopIcons) {
                favoriteButton.requestFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (onTopIcons) {
                contentRoot.requestFocus()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SLIDESHOW && resultCode == RESULT_OK) {
            val path = data?.getStringExtra(SlideshowActivity.SHOWN_PHOTO_PATH) ?: return
            setResult(
                RESULT_OK,
                Intent().putExtra(SlideshowActivity.SHOWN_PHOTO_PATH, path)
            )
            finish()
        }
    }

    override fun onBackPressed() {
        if (optionsVisible) {
            closeOptions()
            return
        }
        setResult(
            RESULT_OK,
            Intent().putExtra(SlideshowActivity.SHOWN_PHOTO_PATH, photo.absolutePath)
        )
        finish()
    }

    override fun onDestroy() {
        indexWorker?.shutdownNow()
        cancelImageLoad()
        imageWorker?.shutdownNow()
        if (::locationLoader.isInitialized) locationLoader.shutdown()
        if (::image.isInitialized) image.setImageDrawable(null)
        currentBitmap?.recycle()
        currentBitmap = null
        super.onDestroy()
    }

    private fun createContentView(): View {
        contentRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val content = contentRoot
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = photo.name
        }
        content.addView(image, FrameLayout.LayoutParams(-1, -1))

        favoriteButton = createIconButton(
            R.drawable.ic_favorite,
            getString(R.string.options_add_favorite)
        ) {
            toggleFavorite()
        }
        playSlideshowButton = createIconButton(
            R.drawable.ic_play_arrow,
            getString(R.string.start_slideshow)
        ) {
            startSlideshowFromPhoto()
        }
        settingsButton = createIconButton(
            R.drawable.ic_settings,
            getString(R.string.settings_button)
        ) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        topIcons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            listOf(favoriteButton, playSlideshowButton, settingsButton).forEachIndexed { index, button ->
                addView(
                    button,
                    LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                        if (index > 0) leftMargin = dp(8)
                    }
                )
            }
        }
        content.addView(
            topIcons,
            FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP or Gravity.RIGHT).apply {
                setMargins(0, dp(12), dp(20), 0)
            }
        )

        dateLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        locationLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
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
        content.addView(footer, FrameLayout.LayoutParams(-1, dp(36), Gravity.BOTTOM))

        status = TextView(this).apply {
            text = getString(R.string.gallery_scanning)
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        content.addView(status, FrameLayout.LayoutParams(-1, -1))

        optionsOverlay = RightOptionsOverlay(this)
        optionsOverlay.onDismissed = {
            optionsVisible = false
            contentRoot.requestFocus()
        }
        dateOptionButton = optionsOverlay.addAction("") { toggleShowDate() }
        locationOptionButton = optionsOverlay.addAction("") { toggleShowLocation() }
        optionsOverlay.addBottomAction(getString(R.string.options_close)) { closeOptions() }
        content.addView(optionsOverlay.view, FrameLayout.LayoutParams(-1, -1))
        return content
    }

    private fun loadPhotoAndIndex() {
        indexWorker = Executors.newSingleThreadExecutor()
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        indexWorker?.execute {
            val initialPhoto = photo
            val bitmap = PhotoImageDecoder.decodeForDisplay(this, initialPhoto)
            val date = PhotoMetadata.dateText(initialPhoto)
            mainHandler.post {
                if (isFinishing || isDestroyed) {
                    bitmap?.recycle()
                    return@post
                }
                showPhoto(initialPhoto, bitmap, date)
            }

            val folderPath = intent.getStringExtra(PHOTO_FOLDER)
            val allPhotos = folderPath?.let { PhotoFiles.listImages(File(it)) }.orEmpty()
            val timeline = PhotoFiles.buildTimeline(allPhotos, PhotoMetadata::captureTimeMillis)
            val navigationPhotos = if (favoritesOnly) {
                PhotoFavorites.filter(allPhotos, PhotoFavorites.load(preferences))
            } else {
                allPhotos
            }
            val orderedPhotos = PhotoFiles.photosInTimeline(
                navigationPhotos,
                timeline,
                year = null,
                month = null,
                newestFirst = true
            )
            val selectedIndex = orderedPhotos.indexOfFirst { it.absolutePath == initialPhoto.absolutePath }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                photos = orderedPhotos
                currentIndex = selectedIndex
                indexReady = true
                val queuedDirection = pendingNavigation
                pendingNavigation = 0
                if (queuedDirection != 0) movePhoto(queuedDirection)
            }
        }
    }

    private fun startSlideshowFromPhoto() {
        startActivityForResult(
            Intent(this, SlideshowActivity::class.java)
                .putExtra(SlideshowActivity.START_PHOTO_PATH, photo.absolutePath),
            REQUEST_SLIDESHOW
        )
    }

    private fun movePhoto(direction: Int) {
        if (!indexReady) {
            pendingNavigation = (pendingNavigation + direction).coerceIn(-100_000, 100_000)
            return
        }
        if (photos.isEmpty() || currentIndex !in photos.indices) return
        currentIndex = PhotoFiles.wrapIndex(currentIndex, direction, photos.size)
        displayPhoto(photos[currentIndex])
    }

    private fun displayPhoto(file: File) {
        photo = file
        val generation = ++displayGeneration
        cancelImageLoad()
        imageLoadTask = imageWorker?.submit {
            val bitmap = PhotoImageDecoder.decodeForDisplay(this, file)
            val date = PhotoMetadata.dateText(file)
            mainHandler.post {
                if (generation != displayGeneration || isFinishing || isDestroyed) {
                    bitmap?.recycle()
                    return@post
                }
                showPhoto(file, bitmap, date)
            }
        }
    }

    private fun showPhoto(file: File, bitmap: Bitmap?, date: String) {
        currentDate = date
        currentLocation = ""
        updateFooter()
        refreshLocation()
        updateFavoriteButton()
        if (bitmap == null) {
            status.text = getString(R.string.preview_photo_unavailable, file.name)
            status.visibility = View.VISIBLE
            return
        }
        image.contentDescription = file.name
        image.setImageBitmap(bitmap)
        currentBitmap?.takeIf { it !== bitmap }?.recycle()
        currentBitmap = bitmap
        status.visibility = View.GONE
    }

    private fun cancelImageLoad() {
        val task = imageLoadTask ?: return
        task.cancel(true)
        if (task is Runnable) imageWorker?.remove(task)
        imageLoadTask = null
    }

    private fun showDate(): Boolean =
        getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
            .getBoolean(SettingsActivity.SHOW_PHOTO_DATE, true)

    private fun showLocation(): Boolean =
        getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
            .getBoolean(SettingsActivity.SHOW_PHOTO_LOCATION, true)

    private fun updateFooter() {
        dateLabel.text = if (showDate()) currentDate else ""
        locationLabel.text = if (showLocation()) currentLocation else ""
    }

    /** Looks the location up in the background; the label stays empty when none can be found. */
    private fun refreshLocation() {
        if (!showLocation()) {
            locationLoader.cancel()
            return
        }
        locationLoader.load(photo) {
            currentLocation = it.orEmpty()
            updateFooter()
        }
    }

    private fun toggleOptions() {
        if (optionsVisible) {
            closeOptions()
            return
        }
        optionsVisible = true
        updateOptionLabels()
        optionsOverlay.show(dateOptionButton)
    }

    private fun closeOptions() {
        if (!optionsVisible) return
        optionsVisible = false
        optionsOverlay.hide()
    }

    private fun toggleShowDate() {
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        preferences.edit()
            .putBoolean(SettingsActivity.SHOW_PHOTO_DATE, !showDate())
            .apply()
        updateFooter()
        updateOptionLabels()
    }

    private fun toggleShowLocation() {
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        preferences.edit()
            .putBoolean(SettingsActivity.SHOW_PHOTO_LOCATION, !showLocation())
            .apply()
        currentLocation = ""
        updateFooter()
        refreshLocation()
        updateOptionLabels()
    }

    private fun toggleFavorite() {
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        PhotoFavorites.toggle(preferences, photo)
        favoritePaths = PhotoFavorites.load(preferences)
        updateFavoriteButton()
    }

    private fun updateFavoriteButton() {
        if (!::favoriteButton.isInitialized) return
        val isFavorite = photo.absolutePath in favoritePaths
        favoriteButton.setIconActive(isFavorite)
        favoriteButton.contentDescription = getString(
            if (isFavorite) R.string.options_remove_favorite else R.string.options_add_favorite
        )
    }

    private fun updateOptionLabels() {
        if (!::dateOptionButton.isInitialized) return
        dateOptionButton.setToggleText(getString(R.string.option_show_photo_date), showDate())
        locationOptionButton.setToggleText(
            getString(R.string.option_show_photo_location),
            showLocation()
        )
    }

    private fun isOptionsKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS

    companion object {
        private const val REQUEST_SLIDESHOW = 1
        const val PHOTO_PATH = "photo_path"
        const val PHOTO_FOLDER = "photo_folder"
        const val PHOTO_FAVORITES_ONLY = "photo_favorites_only"
    }
}
