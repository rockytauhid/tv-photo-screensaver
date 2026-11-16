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
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PhotoPreviewActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var indexWorker: ExecutorService? = null
    private var imageWorker: ThreadPoolExecutor? = null
    private var imageLoadTask: Future<*>? = null
    private lateinit var photo: File
    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var playSlideshowButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var detailsPanel: LinearLayout
    private lateinit var detailsText: TextView
    private lateinit var fileNameLabel: TextView
    private lateinit var dateLabel: TextView
    private lateinit var locationLabel: TextView
    private lateinit var optionsOverlay: RightOptionsOverlay
    private lateinit var detailsOptionButton: TextView
    private lateinit var favoriteOptionButton: TextView
    private var detailsVisible = false
    private var optionsVisible = false
    private var favoritesOnly = false
    private var favoritePaths: Set<String> = emptySet()
    private var photos: List<File> = emptyList()
    private var currentIndex = -1
    private var indexReady = false
    private var pendingNavigation = 0
    private var displayGeneration = 0
    private var currentBitmap: Bitmap? = null
    private var selectedYear: Int? = null
    private var selectedMonth: Int? = null

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
        selectedYear = intent.getIntExtra(PHOTO_YEAR, -1).takeIf { it >= 0 }
        selectedMonth = intent.getIntExtra(PHOTO_MONTH, 0).takeIf { it in 1..12 }
        detailsVisible = preferences
            .getBoolean(SettingsActivity.SHOW_PREVIEW_DETAILS, false)
        favoritesOnly = intent.getBooleanExtra(PHOTO_FAVORITES_ONLY, false)
        favoritePaths = PhotoFavorites.load(preferences)
        setContentView(createContentView())
        updateDetailsVisibility()
        settingsButton.requestFocus()
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
        if (optionsVisible && keyCode == KeyEvent.KEYCODE_BACK) {
            closeOptions()
            return true
        }
        if (optionsVisible &&
            (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
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

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_INFO) {
            detailsVisible = !detailsVisible
            updateDetailsVisibility()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        indexWorker?.shutdownNow()
        cancelImageLoad()
        imageWorker?.shutdownNow()
        if (::image.isInitialized) image.setImageDrawable(null)
        currentBitmap?.recycle()
        currentBitmap = null
        super.onDestroy()
    }

    private fun createContentView(): View {
        val content = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = photo.name
        }
        content.addView(image, FrameLayout.LayoutParams(-1, -1))
        playSlideshowButton = createIconButton(
            R.drawable.ic_play_arrow,
            getString(R.string.start_slideshow)
        ) {
            startSlideshowFromPhoto()
        }
        content.addView(
            playSlideshowButton,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.RIGHT).apply {
                setMargins(0, dp(12), dp(76), 0)
            }
        )
        settingsButton = createIconButton(
            R.drawable.ic_settings,
            getString(R.string.settings_button)
        ) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        content.addView(
            settingsButton,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.RIGHT).apply {
                setMargins(0, dp(12), dp(20), 0)
            }
        )

        detailsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(0xC8000000.toInt())
        }
        detailsText = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            setLineSpacing(dp(6).toFloat(), 1f)
        }
        detailsPanel.addView(
            ScrollView(this).apply {
                isFocusable = false
                addView(detailsText)
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        val panelWidth = (resources.displayMetrics.widthPixels / 4).coerceAtLeast(1)
        content.addView(
            detailsPanel,
            FrameLayout.LayoutParams(panelWidth, -1, Gravity.RIGHT).apply {
                bottomMargin = dp(36)
            }
        )

        fileNameLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
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
        val metadataFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(dateLabel, LinearLayout.LayoutParams(0, -1, 1f))
            addView(fileNameLabel, LinearLayout.LayoutParams(0, -1, 1f))
            addView(locationLabel, LinearLayout.LayoutParams(0, -1, 1f))
        }
        content.addView(
            metadataFooter,
            FrameLayout.LayoutParams(-1, dp(36), Gravity.BOTTOM)
        )

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
            settingsButton.requestFocus()
        }
        detailsOptionButton = optionsOverlay.addAction("") { toggleDetailsFromOptions() }
        favoriteOptionButton = optionsOverlay.addAction("") { toggleFavoriteFromOptions() }
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
            val metadata = previewMetadata(initialPhoto)
            mainHandler.post {
                if (isFinishing || isDestroyed) {
                    bitmap?.recycle()
                    return@post
                }
                showPhoto(initialPhoto, bitmap, metadata)
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
                selectedYear,
                selectedMonth,
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
            val metadata = previewMetadata(file)
            mainHandler.post {
                if (generation != displayGeneration || isFinishing || isDestroyed) {
                    bitmap?.recycle()
                    return@post
                }
                showPhoto(file, bitmap, metadata)
            }
        }
    }

    private fun previewMetadata(file: File): PreviewMetadata = PreviewMetadata(
        details = PhotoMetadata.details(this, file),
        date = android.text.format.DateFormat.format(
            "MMM dd, yyyy",
            PhotoMetadata.captureTimeMillis(file)
        ).toString(),
        location = PhotoMetadata.location(file).orEmpty()
    )

    private fun showPhoto(file: File, bitmap: Bitmap?, metadata: PreviewMetadata) {
        detailsText.text = metadata.details
        fileNameLabel.text = file.name
        dateLabel.text = metadata.date
        locationLabel.text = metadata.location
        updateOptionLabels()
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

    private fun updateDetailsVisibility() {
        detailsPanel.visibility = if (detailsVisible) View.VISIBLE else View.GONE
        if (::detailsOptionButton.isInitialized) updateOptionLabels()
    }

    private fun toggleOptions() {
        if (optionsVisible) {
            closeOptions()
            return
        }
        optionsVisible = true
        updateOptionLabels()
        optionsOverlay.show(detailsOptionButton)
    }

    private fun closeOptions() {
        if (!optionsVisible) return
        optionsVisible = false
        optionsOverlay.hide()
    }

    private fun toggleDetailsFromOptions() {
        detailsVisible = !detailsVisible
        updateDetailsVisibility()
        closeOptions()
    }

    private fun toggleFavoriteFromOptions() {
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        PhotoFavorites.toggle(preferences, photo)
        favoritePaths = PhotoFavorites.load(preferences)
        updateOptionLabels()
        closeOptions()
    }

    private fun updateOptionLabels() {
        if (!::detailsOptionButton.isInitialized) return
        detailsOptionButton.text = getString(
            R.string.show_details_option,
            getString(if (detailsVisible) R.string.on else R.string.off)
        )
        favoriteOptionButton.text = getString(
            if (photo.absolutePath in favoritePaths) R.string.options_remove_favorite
            else R.string.options_add_favorite
        )
    }

    private fun isOptionsKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class PreviewMetadata(
        val details: String,
        val date: String,
        val location: String
    )

    companion object {
        private const val REQUEST_SLIDESHOW = 1
        const val PHOTO_PATH = "photo_path"
        const val PHOTO_FOLDER = "photo_folder"
        const val PHOTO_YEAR = "photo_year"
        const val PHOTO_MONTH = "photo_month"
        const val PHOTO_FAVORITES_ONLY = "photo_favorites_only"
    }
}
