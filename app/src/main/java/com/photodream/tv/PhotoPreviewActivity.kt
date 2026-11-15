package com.photodream.tv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
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
    private lateinit var detailsPanel: LinearLayout
    private lateinit var detailsText: TextView
    private lateinit var detailsButton: Button
    private var detailsVisible = false
    private var photos: List<File> = emptyList()
    private var currentIndex = -1
    private var indexReady = false
    private var pendingNavigation = 0
    private var displayGeneration = 0
    private var currentBitmap: Bitmap? = null
    private var gallerySortOrder = GallerySortOrder.NAME_ASCENDING

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
        gallerySortOrder = GallerySortOrder.fromPreference(intent.getStringExtra(PHOTO_SORT_ORDER))
        detailsVisible = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
            .getBoolean(SettingsActivity.SHOW_PREVIEW_DETAILS, false)
        setContentView(createContentView())
        updateDetailsVisibility()
        detailsButton.requestFocus()
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

        detailsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(68), dp(20), dp(20))
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
            FrameLayout.LayoutParams(panelWidth, -1, Gravity.RIGHT)
        )

        detailsButton = Button(this).apply {
            textSize = 15f
            minHeight = dp(48)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                detailsVisible = !detailsVisible
                updateDetailsVisibility()
            }
        }
        val buttonWidth = (panelWidth - dp(16)).coerceAtLeast(dp(96))
        content.addView(
            detailsButton,
            FrameLayout.LayoutParams(buttonWidth, dp(52), Gravity.TOP or Gravity.RIGHT).apply {
                setMargins(0, dp(10), dp(8), 0)
            }
        )

        status = TextView(this).apply {
            text = getString(R.string.gallery_scanning)
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        content.addView(status, FrameLayout.LayoutParams(-1, -1))
        return content
    }

    private fun loadPhotoAndIndex() {
        indexWorker = Executors.newSingleThreadExecutor()
        indexWorker?.execute {
            val initialPhoto = photo
            val bitmap = PhotoImageDecoder.decodeForDisplay(this, initialPhoto)
            val details = PhotoMetadata.details(this, initialPhoto, includeName = true)
            mainHandler.post {
                if (isFinishing || isDestroyed) {
                    bitmap?.recycle()
                    return@post
                }
                showPhoto(bitmap, details)
            }

            val folderPath = intent.getStringExtra(PHOTO_FOLDER)
            val orderedPhotos = folderPath?.let {
                PhotoFiles.sortGalleryImages(PhotoFiles.listImages(File(it)), gallerySortOrder) {
                    PhotoMetadata.captureTimeMillis(it)
                }
            }.orEmpty()
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
            val details = PhotoMetadata.details(this, file, includeName = true)
            mainHandler.post {
                if (generation != displayGeneration || isFinishing || isDestroyed) {
                    bitmap?.recycle()
                    return@post
                }
                showPhoto(bitmap, details)
            }
        }
    }

    private fun showPhoto(bitmap: Bitmap?, details: String) {
        detailsText.text = details
        if (bitmap == null) {
            status.text = getString(R.string.preview_photo_unavailable, photo.name)
            status.visibility = View.VISIBLE
            return
        }
        image.contentDescription = photo.name
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
        detailsButton.text = getString(
            if (detailsVisible) R.string.preview_hide_details else R.string.preview_show_details
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val PHOTO_PATH = "photo_path"
        const val PHOTO_FOLDER = "photo_folder"
        const val PHOTO_SORT_ORDER = "photo_sort_order"
    }
}
