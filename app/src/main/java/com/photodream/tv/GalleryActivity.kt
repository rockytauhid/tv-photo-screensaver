package com.photodream.tv

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.LinkedHashSet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class GalleryActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanExecutor: ThreadPoolExecutor? = null
    private lateinit var grid: GridView
    private lateinit var status: TextView
    private lateinit var sortButton: Button
    private lateinit var adapter: GalleryAdapter
    private var gallerySortOrder = GallerySortOrder.NAME_ASCENDING
    private var photoFolderPath: String? = null
    private var allPhotos: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gallerySortOrder = GallerySortOrder.fromPreference(
            getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
                .getString(GALLERY_SORT_ORDER, GallerySortOrder.NAME_ASCENDING.preferenceValue)
        )
        setContentView(createContentView())
        adapter = GalleryAdapter()
        grid.adapter = adapter
        updateSortButton()
        grid.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                startActivity(
                    Intent(this, PhotoPreviewActivity::class.java)
                        .putExtra(PhotoPreviewActivity.PHOTO_PATH, adapter.getItem(position).absolutePath)
                        .putExtra(PhotoPreviewActivity.PHOTO_FOLDER, photoFolderPath)
                        .putExtra(PhotoPreviewActivity.PHOTO_SORT_ORDER, gallerySortOrder.preferenceValue)
                )
            }
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                    grid.post { adapter.notifyDataSetChanged() }
                }
            }

            override fun onScroll(
                view: AbsListView,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) = Unit
        })
        loadPhotos()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && grid.hasFocus() && adapter.count > 0) {
            val selectedPosition = grid.selectedItemPosition
            val delta = when {
                event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && selectedPosition == 0 -> -1
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                    selectedPosition == adapter.count - 1 -> 1
                else -> 0
            }
            if (delta != 0) {
                grid.setSelection(PhotoFiles.wrapIndex(selectedPosition, delta, adapter.count))
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        scanExecutor?.shutdownNow()
        if (::adapter.isInitialized) adapter.close()
        super.onDestroy()
    }

    private fun createContentView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setBackgroundColor(0xFF101010.toInt())
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.gallery_title)
            textSize = 26f
            setTextColor(Color.WHITE)
        })
        sortButton = Button(this).apply {
            textSize = 16f
            isFocusable = true
            isFocusableInTouchMode = true
            isEnabled = false
            setOnClickListener { cycleSortOrder() }
        }
        content.addView(sortButton)
        status = TextView(this).apply {
            text = getString(R.string.gallery_scanning)
            textSize = 16f
            setTextColor(0xFFBBBBBB.toInt())
            setPadding(0, dp(6), 0, dp(8))
        }
        content.addView(status)
        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(176)
            horizontalSpacing = dp(8)
            verticalSpacing = dp(10)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            gravity = Gravity.TOP
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipToPadding = false
            isFocusable = true
            isFocusableInTouchMode = true
            isVerticalScrollBarEnabled = true
        }
        content.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        return content
    }

    private fun loadPhotos() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            "android.permission.READ_MEDIA_IMAGES"
        } else {
            "android.permission.READ_EXTERNAL_STORAGE"
        }
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        ) {
            status.setText(R.string.permission_required)
            return
        }

        val folderPath = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
            .getString(SettingsActivity.PHOTO_FOLDER, null)
        if (folderPath == null) {
            status.setText(R.string.gallery_no_folder)
            return
        }
        photoFolderPath = folderPath

        scanExecutor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            ThreadFactory { runnable -> Thread(runnable, "photo-index").apply { isDaemon = true } }
        )
        scanExecutor?.execute {
            val foundPhotos = PhotoFiles.listImages(File(folderPath))
            val orderedPhotos = PhotoFiles.sortGalleryImages(foundPhotos, gallerySortOrder) {
                PhotoMetadata.captureTimeMillis(it)
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                allPhotos = foundPhotos
                adapter.setPhotos(orderedPhotos)
                sortButton.isEnabled = orderedPhotos.isNotEmpty()
                status.text = if (orderedPhotos.isEmpty()) {
                    getString(R.string.gallery_no_photos)
                } else {
                    getString(R.string.gallery_count, orderedPhotos.size)
                }
            }
        }
    }

    private fun cycleSortOrder() {
        if (!sortButton.isEnabled || allPhotos.isEmpty()) return
        val selectedPath = grid.selectedItemPosition
            .takeIf { it in 0 until adapter.count }
            ?.let { adapter.getItem(it).absolutePath }
        gallerySortOrder = gallerySortOrder.next()
        getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(GALLERY_SORT_ORDER, gallerySortOrder.preferenceValue)
            .apply()
        updateSortButton()
        sortButton.isEnabled = false
        status.setText(R.string.gallery_sorting)

        val order = gallerySortOrder
        val photos = allPhotos
        scanExecutor?.execute {
            val sortedPhotos = PhotoFiles.sortGalleryImages(photos, order) {
                PhotoMetadata.captureTimeMillis(it)
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                adapter.setPhotos(sortedPhotos)
                val selection = selectedPath?.let { path ->
                    sortedPhotos.indexOfFirst { it.absolutePath == path }
                } ?: 0
                grid.setSelection(selection.coerceAtLeast(0))
                sortButton.isEnabled = sortedPhotos.isNotEmpty()
                status.text = getString(R.string.gallery_count, sortedPhotos.size)
            }
        }
    }

    private fun updateSortButton() {
        val orderName = when (gallerySortOrder) {
            GallerySortOrder.NAME_ASCENDING -> R.string.gallery_sort_name_ascending
            GallerySortOrder.NAME_DESCENDING -> R.string.gallery_sort_name_descending
            GallerySortOrder.DATE_OLDEST_FIRST -> R.string.oldest_first
            GallerySortOrder.DATE_NEWEST_FIRST -> R.string.newest_first
        }
        sortButton.text = getString(R.string.gallery_sort_setting, getString(orderName))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class GalleryAdapter : BaseAdapter() {
        private val photos = ArrayList<File>()
        private val cacheBytes = minOf(
            6L * 1024 * 1024,
            (Runtime.getRuntime().maxMemory() / 32).coerceAtLeast(1024L * 1024)
        ).toInt()
        private val thumbnailCache = object : LruCache<String, Bitmap>(cacheBytes) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount
        }
        private val failedThumbnails = LinkedHashSet<String>()
        private val thumbnailExecutor = ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(32),
            ThreadFactory { runnable -> Thread(runnable, "photo-thumbnail").apply { isDaemon = true } },
            RejectedExecutionHandler { task, executor ->
                val discarded = executor.queue.poll()
                if (discarded is ThumbnailTask) discarded.cancel()
                if (executor.isShutdown || !executor.queue.offer(task)) {
                    (task as? ThumbnailTask)?.cancel()
                }
            }
        )
        private var closed = false

        override fun getCount(): Int = photos.size

        override fun getItem(position: Int): File = photos[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun hasStableIds(): Boolean = false

        fun setPhotos(items: List<File>) {
            photos.clear()
            photos.addAll(items)
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell: View
            val holder: GalleryHolder
            if (convertView == null) {
                holder = createHolder()
                cell = holder.root
                cell.tag = holder
            } else {
                cell = convertView
                holder = cell.tag as GalleryHolder
            }

            val photo = photos[position]
            val key = photo.absolutePath
            holder.boundKey = key
            holder.caption.text = photo.name
            holder.image.contentDescription = photo.name
            holder.image.tag = key

            val cached = thumbnailCache.get(key)
            if (cached != null) {
                cancel(holder)
                holder.image.setImageBitmap(cached)
                holder.placeholder.visibility = View.GONE
            } else if (hasFailedThumbnail(key)) {
                cancel(holder)
                holder.image.setImageDrawable(null)
                holder.placeholder.setText(R.string.gallery_thumbnail_unavailable)
                holder.placeholder.visibility = View.VISIBLE
            } else {
                holder.image.setImageDrawable(null)
                holder.placeholder.setText(R.string.gallery_thumbnail_loading)
                holder.placeholder.visibility = View.VISIBLE
                if (holder.taskKey != key) {
                    cancel(holder)
                    val task = ThumbnailTask(key, photo, holder)
                    holder.task = task
                    holder.taskKey = key
                    thumbnailExecutor.execute(task)
                }
            }
            return cell
        }

        fun close() {
            closed = true
            thumbnailExecutor.shutdownNow().forEach { (it as? ThumbnailTask)?.cancel() }
            thumbnailCache.evictAll()
            synchronized(failedThumbnails) { failedThumbnails.clear() }
        }

        private fun createHolder(): GalleryHolder {
            val root = LinearLayout(this@GalleryActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(2))
                isFocusable = true
                isFocusableInTouchMode = true
            }
            val image = ImageView(this@GalleryActivity).apply {
                layoutParams = FrameLayout.LayoutParams(-1, -1)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFF252525.toInt())
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val placeholder = TextView(this@GalleryActivity).apply {
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(0xFFBBBBBB.toInt())
                setBackgroundColor(0xFF252525.toInt())
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val thumbnail = FrameLayout(this@GalleryActivity).apply {
                addView(image)
                addView(placeholder, FrameLayout.LayoutParams(-1, -1))
            }
            thumbnail.layoutParams = LinearLayout.LayoutParams(-1, dp(90))
            val caption = TextView(this@GalleryActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(24))
                gravity = Gravity.CENTER_VERTICAL
                textSize = 12f
                setTextColor(0xFFDDDDDD.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                isFocusable = false
            }
            root.addView(thumbnail)
            root.addView(caption)
            root.layoutParams = AbsListView.LayoutParams(dp(176), dp(120))
            return GalleryHolder(root, image, placeholder, caption)
        }

        private fun cancel(holder: GalleryHolder) {
            holder.task?.let {
                if (it is ThumbnailTask) {
                    it.cancel()
                    thumbnailExecutor.remove(it)
                }
            }
            holder.task = null
            holder.taskKey = null
        }

        private fun decodeThumbnail(file: File): Bitmap? =
            PhotoImageDecoder.decode(
                file,
                minOf(dp(168), 512),
                minOf(dp(90), 288),
                Bitmap.Config.RGB_565
            )

        private fun hasFailedThumbnail(key: String): Boolean =
            synchronized(failedThumbnails) { key in failedThumbnails }

        private fun rememberThumbnailFailure(key: String) {
            synchronized(failedThumbnails) {
                failedThumbnails.add(key)
                if (failedThumbnails.size > MAX_FAILED_THUMBNAILS) {
                    failedThumbnails.remove(failedThumbnails.first())
                }
            }
        }

        private inner class ThumbnailTask(
            val key: String,
            private val file: File,
            private val holder: GalleryHolder
        ) : Runnable {
            @Volatile
            private var cancelled = false

            override fun run() {
                val bitmap = decodeThumbnail(file)
                if (cancelled || closed) {
                    bitmap?.recycle()
                    return
                }
                if (bitmap != null) {
                    thumbnailCache.put(key, bitmap)
                } else {
                    rememberThumbnailFailure(key)
                }
                mainHandler.post {
                    if (cancelled) return@post
                    if (holder.task === this) {
                        holder.task = null
                        holder.taskKey = null
                    }
                    if (holder.boundKey == key && holder.image.tag == key) {
                        if (bitmap != null) {
                            holder.image.setImageBitmap(bitmap)
                            holder.placeholder.visibility = View.GONE
                        } else {
                            holder.placeholder.setText(R.string.gallery_thumbnail_unavailable)
                            holder.placeholder.visibility = View.VISIBLE
                        }
                    }
                }
            }

            fun cancel() {
                cancelled = true
                if (holder.task === this) {
                    holder.task = null
                    holder.taskKey = null
                }
            }
        }
    }

    private class GalleryHolder(
        val root: View,
        val image: ImageView,
        val placeholder: TextView,
        val caption: TextView
    ) {
        var boundKey: String? = null
        var taskKey: String? = null
        var task: Runnable? = null
    }

    companion object {
        private const val GALLERY_SORT_ORDER = "gallery_sort_order"
        private const val MAX_FAILED_THUMBNAILS = 128
    }
}
