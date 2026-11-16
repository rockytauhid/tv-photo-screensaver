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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
    private var isPaused = false
    private var photos: List<File> = emptyList()
    private var allPhotos: List<File> = emptyList()
    private var nextPhoto = 0
    private var currentPhotoIndex = -1
    private var displayedPhotoIndex = -1
    private var initialPhotoPath: String? = null
    private var displayGeneration = 0
    private lateinit var imageViews: Array<ImageView>
    private lateinit var metadataFooter: LinearLayout
    private lateinit var dateLabel: TextView
    private lateinit var locationLabel: TextView
    private lateinit var status: TextView
    private lateinit var clock: PhotoClock
    private lateinit var topControls: LinearLayout
    private lateinit var photoSetButton: ImageButton
    private lateinit var playbackButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var optionsOverlay: RightOptionsOverlay
    private lateinit var detailsOptionButton: TextView
    private lateinit var showPhotoSetOptionButton: TextView
    private lateinit var speedOptionButton: TextView
    private lateinit var shuffleOptionButton: TextView
    private lateinit var styleOptionButton: TextView
    private var visibleImage = 0
    private val controlsHideRunnable = Runnable { hideTopControls() }

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
        metadataFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            visibility = View.GONE
            addView(dateLabel, LinearLayout.LayoutParams(0, -1, 1f))
            addView(locationLabel, LinearLayout.LayoutParams(0, -1, 1f))
        }
        clock = PhotoClock(this, mainHandler, Gravity.START)
        status = TextView(this).apply {
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 22f
            gravity = Gravity.CENTER
        }
        optionsOverlay = RightOptionsOverlay(this)
        optionsOverlay.onDismissed = {
            settingsButton.requestFocus()
            scheduleControlsAutoHide()
        }
        detailsOptionButton = optionsOverlay.addAction("") { toggleMetadata() }
        showPhotoSetOptionButton = optionsOverlay.addAction("") { togglePhotoSet() }
        speedOptionButton = optionsOverlay.addAction("") { cycleSpeed() }
        shuffleOptionButton = optionsOverlay.addAction("") { toggleShuffle() }
        styleOptionButton = optionsOverlay.addAction("") { cycleTransition() }
        optionsOverlay.addBottomAction(getString(R.string.options_close)) { closeOptions() }
        val content = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(imageViews[0], FrameLayout.LayoutParams(-1, -1))
            addView(imageViews[1], FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -1))
            addView(
                metadataFooter,
                FrameLayout.LayoutParams(-1, dp(36), Gravity.BOTTOM)
            )
            addView(
                clock.view,
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT).apply {
                    setMargins(dp(20), dp(12), 0, 0)
                }
            )
            topControls = LinearLayout(this@SlideshowActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            photoSetButton = createIconButton(
                R.drawable.ic_favorite,
                getString(R.string.gallery_filter_show_favorites)
            ) {
                togglePhotoSet()
            }
            topControls.addView(
                photoSetButton,
                LinearLayout.LayoutParams(dp(48), dp(48))
            )
            playbackButton = createIconButton(
                R.drawable.ic_pause,
                getString(R.string.pause_slideshow)
            ) {
                togglePlayback()
            }
            topControls.addView(
                playbackButton,
                LinearLayout.LayoutParams(dp(48), dp(48))
            )
            settingsButton = createIconButton(
                R.drawable.ic_settings,
                getString(R.string.settings_button)
            ) {
                startActivity(android.content.Intent(this@SlideshowActivity, SettingsActivity::class.java))
            }
            topControls.addView(
                settingsButton,
                LinearLayout.LayoutParams(dp(48), dp(48))
            )
            addView(
                topControls,
                FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP or Gravity.RIGHT).apply {
                    setMargins(0, dp(12), dp(20), 0)
                }
            )
            addView(optionsOverlay.view, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(content)
        updatePlaybackButton()
        updateSlideshowOptionsLabels()
    }

    private fun togglePhotoSet() {
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        val favoritesOnly = preferences.getBoolean(SettingsActivity.PLAY_FAVORITES_ONLY, false)
        val favoritePaths = PhotoFavorites.load(preferences)
        if (!favoritesOnly && allPhotos.none { it.absolutePath in favoritePaths }) return

        preferences.edit().putBoolean(SettingsActivity.PLAY_FAVORITES_ONLY, !favoritesOnly).apply()
        val currentPath = photos.getOrNull(displayedPhotoIndex)?.absolutePath
            ?: photos.getOrNull(currentPhotoIndex)?.absolutePath
        val candidates = if (favoritesOnly) {
            allPhotos
        } else {
            PhotoFavorites.filter(allPhotos, favoritePaths)
        }
        val sortOrder = if (
            preferences.getString(SettingsActivity.PHOTO_ORDER, SettingsActivity.ORDER_DATE_TIME) ==
            SettingsActivity.ORDER_RANDOM
        ) {
            PhotoSortOrder.RANDOM
        } else {
            PhotoSortOrder.DATE_TIME
        }
        photos = PhotoFiles.sortImages(
            candidates,
            sortOrder,
            newestFirst = true,
            captureTime = PhotoMetadata::captureTimeMillis
        )
        cancelScheduledNext()
        val currentIndex = photos.indexOfFirst { it.absolutePath == currentPath }
        if (currentIndex >= 0) {
            currentPhotoIndex = currentIndex
            displayedPhotoIndex = currentIndex
            nextPhoto = (currentIndex + 1) % photos.size
            if (!isPaused) scheduleNext()
        } else if (photos.isNotEmpty()) {
            currentPhotoIndex = -1
            displayedPhotoIndex = -1
            nextPhoto = 0
            showPhotoAt(0)
        } else {
            currentPhotoIndex = -1
            displayedPhotoIndex = -1
            nextPhoto = 0
            status.setText(R.string.no_favorite_photos)
            status.visibility = View.VISIBLE
            metadataFooter.visibility = View.GONE
        }
        updateSlideshowOptionsLabels()
    }

    override fun onResume() {
        super.onResume()
        clock.start()
        settingsButton.requestFocus()
        showTopControls()
        startSlideshow()
    }

    override fun onPause() {
        clock.stop()
        mainHandler.removeCallbacks(controlsHideRunnable)
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
        if (event.action == KeyEvent.ACTION_DOWN) {
            showTopControls()
        }
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
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
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
        if (event.repeatCount == 0 &&
            (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS)
        ) {
            toggleOptions()
            return true
        }
        if (optionsOverlay.isVisible) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            ) {
                return true
            }
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
                allPhotos = found
                photos = ordered
                updateSlideshowOptionsLabels()
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
        val showMetadata = preferences.getBoolean(SettingsActivity.SHOW_METADATA, true)
        dateLabel.text = frame.date
        locationLabel.text = frame.location
        metadataFooter.visibility =
            if (showMetadata && (frame.date.isNotEmpty() || frame.location.isNotEmpty())) {
                View.VISIBLE
            } else {
                View.GONE
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
        worker?.shutdownNow()
        worker = null
        photos = emptyList()
        allPhotos = emptyList()
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
        val date = android.text.format.DateFormat.format(
            "MMM dd, yyyy",
            PhotoMetadata.captureTimeMillis(file)
        ).toString()
        val location = PhotoMetadata.location(file).orEmpty()
        return PhotoFrame(bitmap, date, location, file.name)
    }

    private fun toggleOptions() {
        if (optionsOverlay.isVisible) {
            closeOptions()
        } else {
            mainHandler.removeCallbacks(controlsHideRunnable)
            updateSlideshowOptionsLabels()
            optionsOverlay.show(detailsOptionButton)
        }
    }

    private fun closeOptions() {
        optionsOverlay.hide()
    }

    private fun showTopControls() {
        if (!::topControls.isInitialized) return
        mainHandler.removeCallbacks(controlsHideRunnable)
        topControls.animate().cancel()
        if (topControls.visibility != View.VISIBLE) {
            topControls.visibility = View.VISIBLE
            topControls.translationY = -dp(64).toFloat()
            topControls.alpha = 0f
        }
        topControls.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(250)
            .start()
        scheduleControlsAutoHide()
    }

    private fun hideTopControls() {
        if (!::topControls.isInitialized || optionsOverlay.isVisible) return
        topControls.animate().cancel()
        topControls.animate()
            .translationY(-dp(64).toFloat())
            .alpha(0f)
            .setDuration(650)
            .withEndAction {
                if (!optionsOverlay.isVisible) topControls.visibility = View.GONE
            }
            .start()
    }

    private fun scheduleControlsAutoHide() {
        mainHandler.removeCallbacks(controlsHideRunnable)
        if (!::topControls.isInitialized || optionsOverlay.isVisible) return
        mainHandler.postDelayed(controlsHideRunnable, 3_000L)
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
        updatePlaybackButton()
    }

    private fun updatePlaybackButton() {
        if (!::playbackButton.isInitialized) return
        playbackButton.setImageResource(
            if (isPaused) R.drawable.ic_play_arrow else R.drawable.ic_pause
        )
        playbackButton.contentDescription = getString(
            if (isPaused) R.string.resume_slideshow else R.string.pause_slideshow
        )
    }

    private fun returnToGallery() {
        val shownPhotoPath = photos.getOrNull(displayedPhotoIndex)?.absolutePath
            ?: photos.getOrNull(currentPhotoIndex)?.absolutePath
            ?: initialPhotoPath
        if (shownPhotoPath != null) {
            setResult(
                RESULT_OK,
                android.content.Intent().putExtra(SHOWN_PHOTO_PATH, shownPhotoPath)
            )
        }
        finish()
    }

    private fun updateSlideshowOptionsLabels() {
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        val showMetadata = preferences.getBoolean(SettingsActivity.SHOW_METADATA, true)
        detailsOptionButton.text = getString(
            R.string.show_details_option,
            getString(if (showMetadata) R.string.on else R.string.off)
        )
        val favoritePaths = PhotoFavorites.load(preferences)
        val favoritesAvailable = allPhotos.any { it.absolutePath in favoritePaths }
        val favoritesOnly = preferences.getBoolean(SettingsActivity.PLAY_FAVORITES_ONLY, false)
        showPhotoSetOptionButton.text = getString(
            R.string.slideshow_photo_set,
            getString(if (favoritesOnly) R.string.show_favorite else R.string.show_all)
        )
        showPhotoSetOptionButton.isEnabled = favoritesOnly || favoritesAvailable
        updatePhotoSetButton(favoritesOnly, favoritesAvailable)
        val speed = SlideshowSpeed.normalize(preferences)
        speedOptionButton.text = getString(R.string.speed_option, getString(speed.labelRes))
        val shuffleEnabled = preferences.getString(
            SettingsActivity.PHOTO_ORDER,
            SettingsActivity.ORDER_DATE_TIME
        ) == SettingsActivity.ORDER_RANDOM
        shuffleOptionButton.text = getString(
            R.string.shuffle_option,
            getString(if (shuffleEnabled) R.string.on else R.string.off)
        )
        val transitionName = when (
            preferences.getString(SettingsActivity.TRANSITION, SettingsActivity.FADE)
        ) {
            SettingsActivity.SLIDE -> R.string.transition_slide
            SettingsActivity.CUT -> R.string.transition_cut
            else -> R.string.transition_fade
        }

        styleOptionButton.text = getString(
            R.string.style_option,
            getString(transitionName)
        )
    }

    private fun updatePhotoSetButton(favoritesOnly: Boolean, favoritesAvailable: Boolean) {
        if (!::photoSetButton.isInitialized) return
        photoSetButton.setImageResource(
            if (favoritesOnly) R.drawable.ic_view_all else R.drawable.ic_favorite
        )
        photoSetButton.contentDescription = getString(
            if (favoritesOnly) {
                R.string.gallery_filter_show_all
            } else {
                R.string.gallery_filter_show_favorites
            }
        )
        photoSetButton.isEnabled = favoritesOnly || favoritesAvailable
        photoSetButton.alpha = if (favoritesOnly || favoritesAvailable) 1f else 0.45f
    }

    private fun toggleMetadata() {
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        val showMetadata = !preferences.getBoolean(SettingsActivity.SHOW_METADATA, true)
        preferences.edit().putBoolean(SettingsActivity.SHOW_METADATA, showMetadata).apply()
        metadataFooter.visibility = if (
            showMetadata && (dateLabel.text.isNotEmpty() || locationLabel.text.isNotEmpty())
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        updateSlideshowOptionsLabels()
    }

    private fun cycleSpeed() {
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        val nextSpeed = SlideshowSpeed.normalize(preferences).next()
        preferences.edit().putInt(SettingsActivity.INTERVAL_SECONDS, nextSpeed.intervalSeconds).apply()
        if (isRunning && photos.isNotEmpty()) scheduleNext()
        updateSlideshowOptionsLabels()
    }

    private fun toggleShuffle() {
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        val shuffleEnabled = preferences.getString(
            SettingsActivity.PHOTO_ORDER,
            SettingsActivity.ORDER_DATE_TIME
        ) != SettingsActivity.ORDER_RANDOM
        val nextOrder = if (shuffleEnabled) {
            SettingsActivity.ORDER_RANDOM
        } else {
            SettingsActivity.ORDER_DATE_TIME
        }
        preferences.edit().putString(SettingsActivity.PHOTO_ORDER, nextOrder).apply()

        val currentPhoto = photos.getOrNull(currentPhotoIndex)
        val remainingPhotos = photos.filterIndexed { index, _ -> index != currentPhotoIndex }
        val order = if (shuffleEnabled) PhotoSortOrder.RANDOM else PhotoSortOrder.DATE_TIME
        val orderedRemaining = PhotoFiles.sortImages(
            remainingPhotos,
            order,
            newestFirst = true,
            captureTime = PhotoMetadata::captureTimeMillis
        )
        photos = if (currentPhoto == null) orderedRemaining else listOf(currentPhoto) + orderedRemaining
        if (currentPhoto == null) {
            currentPhotoIndex = -1
            displayedPhotoIndex = -1
            nextPhoto = 0
        } else {
            currentPhotoIndex = 0
            displayedPhotoIndex = 0
            nextPhoto = if (photos.size > 1) 1 else 0
        }
        updateSlideshowOptionsLabels()
    }

    private fun cycleTransition() {
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        val next = when (preferences.getString(SettingsActivity.TRANSITION, SettingsActivity.FADE)) {
            SettingsActivity.FADE -> SettingsActivity.SLIDE
            SettingsActivity.SLIDE -> SettingsActivity.CUT
            else -> SettingsActivity.FADE
        }
        preferences.edit().putString(SettingsActivity.TRANSITION, next).apply()
        updateSlideshowOptionsLabels()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class PhotoFrame(
        val bitmap: Bitmap,
        val date: String,
        val location: String,
        val photoName: String
    )

    companion object {
        const val START_PHOTO_PATH = "start_photo_path"
        const val SHOWN_PHOTO_PATH = "shown_photo_path"
    }
}
