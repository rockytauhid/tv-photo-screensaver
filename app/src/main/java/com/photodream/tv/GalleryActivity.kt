// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.DateFormatSymbols
import java.util.LinkedHashSet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class GalleryActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanExecutor: ThreadPoolExecutor? = null
    private lateinit var grid: HorizontalGridView
    private lateinit var headerTitle: TextView
    private lateinit var favoritesFilterButton: ImageButton
    private lateinit var startSlideshowButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var selectedDateLabel: TextView
    private lateinit var optionsOverlay: RightOptionsOverlay
    private lateinit var dateOptionButton: TextView
    private lateinit var locationOptionButton: TextView
    private lateinit var screensaverOptionButton: TextView
    private lateinit var shortcutsScroll: HorizontalScrollView
    private lateinit var dateShortcuts: LinearLayout
    private lateinit var leftPeek: ImageView
    private lateinit var rightPeek: ImageView
    private lateinit var leftPeekHolder: GalleryHolder
    private lateinit var rightPeekHolder: GalleryHolder
    private lateinit var galleryFrame: FrameLayout
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var status: TextView
    private lateinit var adapter: GalleryAdapter
    private var photoFolderPath: String? = null
    private var allPhotos: List<File> = emptyList()
    private var allTimeline: PhotoTimeline? = null
    private var timeline: PhotoTimeline? = null
    private var favoritePaths: Set<String> = emptySet()
    private var favoritesOnly = false
    private val newestFirst = true

    /** Year and month of the highlighted photo; both shortcuts turn gold. */
    private var highlightedDate: PhotoDate? = null

    /** The one year whose months are shown in the shortcut row. */
    private var expandedYear: Int? = null
    private lateinit var preferences: PhotoPreferences
    private lateinit var repository: PhotoRepository
    private var scanGeneration = 0
    private var loadedWithPermission = false
    private var refreshFavoritesWhenResumed = false
    private var openedInitialSettings = false
    private var thumbnailSizeDp = 120

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = PhotoPreferences(this)
        repository = PhotoRepository(this)
        openedInitialSettings =
            savedInstanceState?.getBoolean(OPENED_INITIAL_SETTINGS, false) ?: false
        window.requestFeature(Window.FEATURE_NO_TITLE)
        setContentView(createContentView())
        adapter = GalleryAdapter()
        grid.adapter = adapter
        grid.setOnChildViewHolderSelectedListener(
            object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(
                    parent: RecyclerView,
                    child: RecyclerView.ViewHolder?,
                    position: Int,
                    subposition: Int
                ) {
                    if (position in 0 until adapter.itemCount) {
                        adapter.setHighlightedPosition(position)
                        updateEdgePreviews(position)
                        updateSelectedMetadata(position)
                        onPhotoHighlighted(position)
                    } else {
                        adapter.setHighlightedPosition(-1)
                        updateEdgePreviews(-1)
                    }
                }
            }
        )
        grid.setOnFocusChangeListener { _, hasFocus ->
            adapter.setHighlightedPosition(
                if (hasFocus) grid.selectedPosition else -1
            )
            if (hasFocus) updateSelectedMetadata(grid.selectedPosition)
        }
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    grid.post {
                        updateEdgePreviews(grid.selectedPosition)
                    }
                }
            }
        })
        loadPhotos()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(OPENED_INITIAL_SETTINGS, openedInitialSettings)
    }

    override fun onResume() {
        super.onResume()
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        val currentFolder = preferences.getString(SettingsActivity.PHOTO_FOLDER, null)
        val hasPermission = hasPhotoPermission()
        if (currentFolder != photoFolderPath || hasPermission != loadedWithPermission) {
            refreshFavoritesWhenResumed = false
            loadPhotos()
        } else if (refreshFavoritesWhenResumed) {
            refreshFavoritesWhenResumed = false
            refreshFavoriteFilter()
            updateSelectedMetadata(grid.selectedPosition)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SETTINGS) {
            val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
            val currentFolder = preferences.getString(SettingsActivity.PHOTO_FOLDER, null)
            val hasPermission = hasPhotoPermission()
            if (currentFolder != photoFolderPath || hasPermission != loadedWithPermission) {
                loadPhotos()
            } else {
                refreshFavoriteFilter()
                updateSelectedMetadata(grid.selectedPosition)
            }
            return
        }
        if ((requestCode == REQUEST_SLIDESHOW || requestCode == REQUEST_PREVIEW) &&
            resultCode == RESULT_OK
        ) {
            if (requestCode == REQUEST_SLIDESHOW) refreshFavoriteFilter()
            data?.getStringExtra(SlideshowActivity.SHOWN_PHOTO_PATH)?.let(::selectReturnedPhoto)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            !optionsOverlay.isVisible &&
            (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        ) {
            startSlideshowFromSelection()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        scanGeneration++
        scanExecutor?.shutdownNow()
        if (::adapter.isInitialized) adapter.close()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0 &&
            (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS)
        ) {
            toggleOptions()
            return true
        }
        if (event.repeatCount == 0 &&
            grid.hasFocus() &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            val selectedPosition = grid.selectedPosition
            if (selectedPosition in 0 until adapter.itemCount) {
                openPreview(selectedPosition)
                return true
            }
        }
        if (optionsOverlay.isVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closeOptions()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            ) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun createContentView(): View {
        val verticalEdge = dp(TV_EDGE_MARGIN_DP)
        val headerTop = dp(TV_HEADER_TOP_MARGIN_DP)
        val horizontalEdge = dp(TV_HORIZONTAL_MARGIN_DP)
        val shortcutInnerPadding = dp(SHORTCUT_HORIZONTAL_PADDING_DP)
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF101010.toInt())
            clipChildren = false
            clipToPadding = false
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerTitle = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 18f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(headerTitle, LinearLayout.LayoutParams(0, -2, 1f))
        // Gold when only favorites are shown, white when all photos are shown.
        favoritesFilterButton = createIconButton(
            R.drawable.ic_favorite,
            getString(R.string.gallery_filter_show_favorites)
        ) {
            toggleFavoritesFilter()
        }
        header.addView(favoritesFilterButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            setMargins(dp(8), 0, dp(4), 0)
        })
        startSlideshowButton = createIconButton(
            R.drawable.ic_play_arrow,
            getString(R.string.start_slideshow)
        ) {
            startSlideshowFromSelection()
        }
        header.addView(startSlideshowButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            setMargins(dp(8), 0, dp(8), 0)
        })
        settingsButton = createIconButton(
            R.drawable.ic_settings,
            getString(R.string.settings_button)
        ) {
            openSettings()
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(
            header,
            FrameLayout.LayoutParams(-1, dp(44), Gravity.TOP).apply {
                setMargins(horizontalEdge, headerTop, horizontalEdge, 0)
            }
        )

        dateShortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        shortcutsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            clipToPadding = false
            // Offset by inner shortcut padding so the Year text aligns exactly with horizontalEdge.
            setPadding(
                (horizontalEdge - shortcutInnerPadding).coerceAtLeast(0),
                dp(2),
                horizontalEdge,
                dp(2)
            )
            addView(dateShortcuts, ViewGroup.LayoutParams(-2, -1))
            visibility = View.GONE
        }
        root.addView(
            shortcutsScroll,
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                topMargin = headerTop + dp(42)
            }
        )

        val screenWidthDp =
            (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val screenHeightDp =
            (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt()
        thumbnailSizeDp = computeThumbnailSizeDp(screenWidthDp, screenHeightDp)
        val gridHeightDp = thumbnailSizeDp * 3 + GRID_SPACING_DP * 2 + GRID_VERTICAL_PADDING_DP * 2
        grid = HorizontalGridView(this).apply {
            setNumRows(3)
            horizontalSpacing = dp(GRID_SPACING_DP)
            verticalSpacing = dp(GRID_SPACING_DP)
            // Start aligned with the title/Year/date on the left; scroll full-width once past the first page,
            // and use the full right edge with no right padding.
            setPadding(horizontalEdge, dp(GRID_VERTICAL_PADDING_DP), 0, dp(GRID_VERTICAL_PADDING_DP))
            clipToPadding = false
            clipChildren = false
            isFocusable = true
            isFocusableInTouchMode = true
            isHorizontalScrollBarEnabled = false
        }
        galleryFrame = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            visibility = View.INVISIBLE
        }
        galleryFrame.addView(grid, FrameLayout.LayoutParams(-1, -1))
        leftPeek = createPeekImage()
        rightPeek = createPeekImage()
        leftPeekHolder = createPeekHolder(leftPeek)
        rightPeekHolder = createPeekHolder(rightPeek)
        galleryFrame.addView(
            leftPeek,
            FrameLayout.LayoutParams(dp(24), dp(90), Gravity.TOP or Gravity.LEFT).apply {
                topMargin = dp(GRID_VERTICAL_PADDING_DP)
            }
        )
        galleryFrame.addView(
            rightPeek,
            FrameLayout.LayoutParams(dp(24), dp(90), Gravity.TOP or Gravity.RIGHT).apply {
                topMargin = dp(GRID_VERTICAL_PADDING_DP)
            }
        )
        val galleryContainer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(galleryFrame, FrameLayout.LayoutParams(-1, dp(gridHeightDp), Gravity.CENTER))
        }
        root.addView(
            galleryContainer,
            FrameLayout.LayoutParams(-1, -1).apply {
                topMargin = headerTop + dp(78)
                bottomMargin = verticalEdge + dp(24)
            }
        )

        loadingOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF101010.toInt())
            visibility = View.GONE
            addView(
                ImageView(this@GalleryActivity).apply {
                    setImageResource(R.mipmap.ic_launcher)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(64), dp(64))
            )
            addView(
                TextView(this@GalleryActivity).apply {
                    text = getString(R.string.app_name)
                    textSize = 24f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(14), 0, dp(20))
                },
                LinearLayout.LayoutParams(-2, -2)
            )
            addView(
                ProgressBar(this@GalleryActivity).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
                },
                LinearLayout.LayoutParams(dp(36), dp(36))
            )
        }
        root.addView(loadingOverlay, FrameLayout.LayoutParams(-1, -1))

        status = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFFBBBBBB.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
                setMargins(horizontalEdge, 0, horizontalEdge, 0)
            }
        )

        // Only the date is shown here (bottom left); aligned with the title, Year, and bottom safe margin.
        selectedDateLabel = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(
            selectedDateLabel,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                setMargins(horizontalEdge, 0, horizontalEdge, verticalEdge)
            }
        )

        loadingOverlay.bringToFront()

        optionsOverlay = RightOptionsOverlay(this)
        optionsOverlay.onDismissed = { grid.requestFocus() }
        dateOptionButton = optionsOverlay.addAction("") { toggleShowDate() }
        locationOptionButton = optionsOverlay.addAction("") { toggleShowLocation() }
        screensaverOptionButton = optionsOverlay.addAction(
            getString(R.string.set_as_screensaver)
        ) {
            closeOptions()
            openScreensaverSettings()
        }
        optionsOverlay.addBottomAction(getString(R.string.options_close)) { closeOptions() }
        root.addView(optionsOverlay.view, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun createPeekImage(): ImageView =
        ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF252525.toInt())
            alpha = 0.9f
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }

    private fun createPeekHolder(image: ImageView): GalleryHolder =
        GalleryHolder(image, image, TextView(this), null)

    private fun openSettings() {
        startActivityForResult(Intent(this, SettingsActivity::class.java), REQUEST_SETTINGS)
    }

    private fun openSettingsOnFirstRun() {
        if (openedInitialSettings) return
        openedInitialSettings = true
        openSettings()
    }

    private fun openPreview(position: Int) {
        refreshFavoritesWhenResumed = true
        startActivityForResult(
            Intent(this, PhotoPreviewActivity::class.java)
                .putExtra(PhotoPreviewActivity.PHOTO_PATH, adapter.getItem(position).absolutePath)
                .putExtra(PhotoPreviewActivity.PHOTO_FOLDER, photoFolderPath)
                .putExtra(PhotoPreviewActivity.PHOTO_FAVORITES_ONLY, favoritesOnly),
            REQUEST_PREVIEW
        )
    }

    private fun startSlideshowFromSelection() {
        if (adapter.itemCount == 0) return
        val selectedPosition = grid.selectedPosition
            .takeIf { it in 0 until adapter.itemCount }
            ?: 0
        startActivityForResult(
            Intent(this, SlideshowActivity::class.java)
                .putExtra(
                    SlideshowActivity.START_PHOTO_PATH,
                    adapter.getItem(selectedPosition).absolutePath
                ),
            REQUEST_SLIDESHOW
        )
    }

    private fun selectReturnedPhoto(path: String) {
        if (findPhotoPosition(path) < 0 &&
            allPhotos.any { it.absolutePath == path } &&
            favoritesOnly &&
            path !in favoritePaths
        ) {
            favoritesOnly = false
            getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
                .edit()
                .putBoolean(SettingsActivity.PLAY_FAVORITES_ONLY, false)
                .apply()
            updateFavoritesFilterButton()
            applyFilter(path)
        }
        val position = findPhotoPosition(path)
        if (position >= 0) {
            jumpTo(position, null)
            grid.requestFocus()
        }
    }

    private fun findPhotoPosition(path: String): Int =
        (0 until adapter.itemCount).firstOrNull {
            adapter.getItem(it).absolutePath == path
        } ?: -1

    private fun currentPhotoPath(): String? =
        grid.selectedPosition
            .takeIf { it in 0 until adapter.itemCount }
            ?.let { adapter.getItem(it).absolutePath }

    private fun updateEdgePreviews(selectedPosition: Int) {
        val count = adapter.itemCount
        if (count < 2 || selectedPosition !in 0 until count) {
            adapter.bindEdgeThumbnail(-1, leftPeekHolder)
            adapter.bindEdgeThumbnail(-1, rightPeekHolder)
            leftPeek.visibility = View.GONE
            rightPeek.visibility = View.GONE
            return
        }

        val visiblePositions = (0 until grid.childCount)
            .map { grid.getChildAdapterPosition(grid.getChildAt(it)) }
            .filter { it >= 0 }
        val previous = selectedPosition - 1
        val next = selectedPosition + 1
        val showLeft = previous >= 0 && previous !in visiblePositions
        val showRight = next < count && next !in visiblePositions

        leftPeek.visibility = if (showLeft) View.VISIBLE else View.GONE
        rightPeek.visibility = if (showRight) View.VISIBLE else View.GONE
        adapter.bindEdgeThumbnail(if (showLeft) previous else -1, leftPeekHolder)
        adapter.bindEdgeThumbnail(if (showRight) next else -1, rightPeekHolder)
    }

    private fun loadPhotos() {
        val generation = ++scanGeneration
        scanExecutor?.shutdownNow()
        scanExecutor = null
        allPhotos = emptyList()
        allTimeline = null
        timeline = null
        favoritePaths = emptySet()
        favoritesOnly = false
        selectedDateLabel.text = ""
        highlightedDate = null
        expandedYear = null
        photoFolderPath = null
        adapter.setPhotos(emptyList())
        updateEdgePreviews(-1)
        startSlideshowButton.isEnabled = false
        updateFavoritesFilterButton()
        dateShortcuts.removeAllViews()
        shortcutsScroll.visibility = View.GONE
        galleryFrame.visibility = View.INVISIBLE
        loadingOverlay.visibility = View.GONE
        status.visibility = View.GONE
        headerTitle.text = getString(R.string.app_name)

        val hasPermission = hasPhotoPermission()
        loadedWithPermission = hasPermission
        if (!hasPermission) {
            status.setText(R.string.permission_required)
            status.visibility = View.VISIBLE
            settingsButton.requestFocus()
            openSettingsOnFirstRun()
            return
        }

        val folderPath = preferences.photoFolder
        if (folderPath == null) {
            status.setText(R.string.gallery_no_folder)
            status.visibility = View.VISIBLE
            settingsButton.requestFocus()
            openSettingsOnFirstRun()
            return
        }
        photoFolderPath = folderPath

        val source = repository.localSource(folderPath)
        val cachedEntries = source.loadCached()
        if (cachedEntries.isNotEmpty()) {
            applyLoadedEntries(cachedEntries, keepPath = null)
        } else {
            loadingOverlay.visibility = View.VISIBLE
        }

        scanExecutor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            ThreadFactory { runnable -> Thread(runnable, "photo-index").apply { isDaemon = true } }
        )
        scanExecutor?.execute {
            val refreshed = source.refresh(cachedEntries)
            if (cachedEntries.isNotEmpty() && !refreshed.changed) return@execute
            mainHandler.post {
                if (isFinishing || isDestroyed || generation != scanGeneration) return@post
                applyLoadedEntries(refreshed.entries, keepPath = currentPhotoPath())
            }
        }
    }

    private fun applyLoadedEntries(entries: List<IndexedPhoto>, keepPath: String?) {
        val foundPhotos = entries.map { File(it.path) }
        val allPhotoTimeline = PhotoIndex.buildTimeline(entries)
        val initialFavoritePaths = preferences.favoritePaths()
        val favoritesAvailable = foundPhotos.any { it.absolutePath in initialFavoritePaths }
        val showFavoritesOnly = (
            preferences.raw.getBoolean(
                SettingsActivity.PLAY_FAVORITES_ONLY,
                favoritesAvailable
            ) && favoritesAvailable
        )
        if ((preferences.hasSavedFavoritesFilter &&
                preferences.playFavoritesOnly != showFavoritesOnly
            ) ||
            (!preferences.hasSavedFavoritesFilter && showFavoritesOnly)
        ) {
            preferences.playFavoritesOnly = showFavoritesOnly
        }
        val activePhotos = if (showFavoritesOnly) {
            PhotoFavorites.filter(foundPhotos, initialFavoritePaths)
        } else {
            foundPhotos
        }
        val activeTimeline = if (showFavoritesOnly) {
            PhotoFiles.subsetTimeline(activePhotos, allPhotoTimeline)
        } else {
            allPhotoTimeline
        }
        // The grid is one continuous timeline, newest first; the year and month shortcuts jump in it.
        val visiblePhotos = PhotoFiles.photosInTimeline(
            activePhotos,
            activeTimeline,
            year = null,
            month = null,
            newestFirst = newestFirst
        )
        loadingOverlay.visibility = View.GONE
        allPhotos = foundPhotos
        allTimeline = allPhotoTimeline
        timeline = activeTimeline
        favoritePaths = initialFavoritePaths
        favoritesOnly = showFavoritesOnly
        updateFavoritesFilterButton()
        adapter.setPhotos(visiblePhotos)
        startSlideshowButton.isEnabled = foundPhotos.isNotEmpty()
        updateHeaderCount(if (showFavoritesOnly) activePhotos.size else foundPhotos.size)
        status.visibility = if (foundPhotos.isEmpty()) View.VISIBLE else View.GONE
        galleryFrame.visibility = if (foundPhotos.isEmpty()) View.INVISIBLE else View.VISIBLE
        if (foundPhotos.isEmpty()) {
            status.setText(R.string.gallery_no_photos)
            rebuildDateShortcuts()
            settingsButton.requestFocus()
        } else {
            val targetIndex = keepPath
                ?.let { path -> visiblePhotos.indexOfFirst { it.absolutePath == path } }
                ?.takeIf { it >= 0 }
                ?: 0
            jumpTo(targetIndex, null)
            grid.requestFocus()
        }
    }

    private fun hasPhotoPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            "android.permission.READ_MEDIA_IMAGES"
        } else {
            "android.permission.READ_EXTERNAL_STORAGE"
        }
        return Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun toggleFavoritesFilter() {
        if (!favoritesOnly && !hasFavoritesInFolder()) return
        favoritesOnly = !favoritesOnly
        getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(SettingsActivity.PLAY_FAVORITES_ONLY, favoritesOnly)
            .apply()
        updateFavoritesFilterButton()
        applyFilter(currentPhotoPath())
    }

    private fun refreshFavoriteFilter() {
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        val newFavoritePaths = PhotoFavorites.load(preferences)
        val previousFavoritesOnly = favoritesOnly
        val previousFavoritePaths = favoritePaths
        favoritePaths = newFavoritePaths
        val hasFavorites = hasFavoritesInFolder()
        val hasSavedFilter = preferences.contains(SettingsActivity.PLAY_FAVORITES_ONLY)
        val requestedFavoritesOnly = preferences.getBoolean(
            SettingsActivity.PLAY_FAVORITES_ONLY,
            hasFavorites
        )
        favoritesOnly = requestedFavoritesOnly && hasFavorites
        if ((hasSavedFilter && requestedFavoritesOnly != favoritesOnly) ||
            (!hasSavedFilter && favoritesOnly)
        ) {
            preferences.edit()
                .putBoolean(SettingsActivity.PLAY_FAVORITES_ONLY, favoritesOnly)
                .apply()
        }
        updateFavoritesFilterButton()
        if (favoritesOnly != previousFavoritesOnly ||
            (favoritesOnly && favoritePaths != previousFavoritePaths)
        ) {
            applyFilter(currentPhotoPath())
        }
    }

    /** Rebuilds the grid for the current All/Favorites filter and keeps [keepPath] highlighted if it is still shown. */
    private fun applyFilter(keepPath: String?) {
        val fullTimeline = allTimeline ?: return
        val activePhotos = if (favoritesOnly) {
            PhotoFavorites.filter(allPhotos, favoritePaths)
        } else {
            allPhotos
        }
        val activeTimeline = if (favoritesOnly) {
            PhotoFiles.subsetTimeline(activePhotos, fullTimeline)
        } else {
            fullTimeline
        }
        timeline = activeTimeline
        val ordered = PhotoFiles.photosInTimeline(
            activePhotos,
            activeTimeline,
            year = null,
            month = null,
            newestFirst = newestFirst
        )
        updateHeaderCount(activePhotos.size)
        adapter.setPhotos(ordered)
        highlightedDate = null
        expandedYear = null
        val index = keepPath
            ?.let { path -> ordered.indexOfFirst { it.absolutePath == path } }
            ?.takeIf { it >= 0 }
            ?: 0
        if (ordered.isEmpty()) {
            rebuildDateShortcuts()
            updateSelectedMetadata(-1)
            updateEdgePreviews(-1)
        } else {
            jumpTo(index, null)
        }
    }

    private fun hasFavoritesInFolder(): Boolean =
        allPhotos.any { it.absolutePath in favoritePaths }

    /** Selects [position] in the grid and updates the shortcuts without taking focus from the caller. */
    private fun jumpTo(position: Int, focusTag: String?) {
        if (position !in 0 until adapter.itemCount) return
        val date = timeline?.datesByPath?.get(adapter.getItem(position).absolutePath)
        if (date != null) {
            highlightedDate = date
            expandedYear = date.year
        }
        rebuildDateShortcuts(focusTag)
        grid.setSelectedPosition(position)
        updateSelectedMetadata(position)
        updateEdgePreviews(position)
    }

    /** Follows the highlighted photo: the shortcuts select its year and month and open its year. */
    private fun onPhotoHighlighted(position: Int) {
        val photo = adapter.getItem(position)
        val date = timeline?.datesByPath?.get(photo.absolutePath) ?: return
        val previous = highlightedDate
        if (date == previous) return
        highlightedDate = date
        if (previous == null || date.year != previous.year) {
            expandedYear = date.year
            rebuildDateShortcuts()
        } else {
            updateShortcutSelection()
        }
    }

    private fun onYearClicked(year: Int) {
        if (year == highlightedDate?.year) {
            // The year of the highlighted photo: collapse its months, or open them again.
            expandedYear = if (expandedYear == year) null else year
            rebuildDateShortcuts("year-$year")
        } else {
            val position = PhotoFiles.indexOfFirstInPeriod(
                adapter.items(),
                timeline ?: return,
                year,
                null
            )
            jumpTo(position, "year-$year")
        }
    }

    private fun onMonthClicked(year: Int, month: Int) {
        // Photos are newest first, so the first photo of a month is its last day.
        val position = PhotoFiles.indexOfFirstInPeriod(
            adapter.items(),
            timeline ?: return,
            year,
            month
        )
        jumpTo(position, "month-$year-$month")
    }

    private fun rebuildDateShortcuts(focusTag: String? = null) {
        dateShortcuts.removeAllViews()
        val currentTimeline = timeline
        if (currentTimeline == null || currentTimeline.years.isEmpty()) {
            shortcutsScroll.visibility = View.GONE
            return
        }
        currentTimeline.years.forEach { year ->
            dateShortcuts.addView(
                createDateShortcut("year-$year", year.toString()) { onYearClicked(year) }
            )
            if (year == expandedYear) {
                currentTimeline.monthsByYear[year].orEmpty().forEach { month ->
                    val monthName = DateFormatSymbols.getInstance().shortMonths
                        .getOrNull(month - 1)
                        ?.takeIf(String::isNotBlank)
                        ?: month.toString()
                    dateShortcuts.addView(
                        createDateShortcut("month-$year-$month", monthName) {
                            onMonthClicked(year, month)
                        }
                    )
                }
            }
        }
        shortcutsScroll.visibility = View.VISIBLE
        updateShortcutSelection()
        if (focusTag != null) {
            shortcutsScroll.post {
                dateShortcuts.findViewWithTag<View>(focusTag)?.requestFocus()
            }
        }
    }

    private fun updateShortcutSelection() {
        var selectedView: View? = null
        for (index in 0 until dateShortcuts.childCount) {
            val view = dateShortcuts.getChildAt(index) as? TextView ?: continue
            styleShortcut(view)
            if (isShortcutSelected(view)) selectedView = view
        }
        val target = selectedView ?: return
        if (dateShortcuts.hasFocus()) return
        shortcutsScroll.post {
            val left = target.left
            val right = target.right
            if (left < shortcutsScroll.scrollX) {
                shortcutsScroll.smoothScrollTo(left - dp(24), 0)
            } else if (right > shortcutsScroll.scrollX + shortcutsScroll.width) {
                shortcutsScroll.smoothScrollTo(right - shortcutsScroll.width + dp(24), 0)
            }
        }
    }

    private fun isShortcutSelected(view: TextView): Boolean {
        val tag = view.tag as? String ?: return false
        val date = highlightedDate ?: return false
        return tag == "year-${date.year}" || tag == "month-${date.year}-${date.month}"
    }

    /** Gold when it matches the highlighted photo, complete pill border while it has focus. */
    private fun styleShortcut(view: TextView) {
        val selected = isShortcutSelected(view)
        view.setTextColor(
            when {
                selected -> GOLD_COLOR
                view.hasFocus() -> Color.WHITE
                else -> 0xFFE0E0E0.toInt()
            }
        )
        view.setTypeface(
            null,
            if (selected || view.hasFocus()) Typeface.BOLD else Typeface.NORMAL
        )
        view.background = if (view.hasFocus()) focusRing(circle = false) else null
    }

    private fun updateFavoritesFilterButton() {
        if (!::favoritesFilterButton.isInitialized) return
        val hasFavorites = hasFavoritesInFolder()
        favoritesFilterButton.setIconActive(favoritesOnly)
        favoritesFilterButton.contentDescription = getString(
            if (favoritesOnly) {
                R.string.gallery_filter_show_all
            } else {
                R.string.gallery_filter_show_favorites
            }
        )
        favoritesFilterButton.isEnabled = favoritesOnly || hasFavorites
        favoritesFilterButton.alpha = if (favoritesOnly || hasFavorites) 1f else 0.45f
    }

    private fun createDateShortcut(
        tag: String,
        label: String,
        onClick: () -> Unit
    ): TextView =
        TextView(this).apply {
            this.tag = tag
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFE0E0E0.toInt())
            setPadding(dp(SHORTCUT_HORIZONTAL_PADDING_DP), dp(4), dp(SHORTCUT_HORIZONTAL_PADDING_DP), dp(4))
            minHeight = dp(34)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = null
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(4) }
            setOnFocusChangeListener { view, _ -> styleShortcut(view as TextView) }
            setOnClickListener { onClick() }
        }

    private fun updateHeaderCount(count: Int) {
        val itemCount = resources.getQuantityString(R.plurals.gallery_item_count, count, count)
        headerTitle.text = getString(
            R.string.gallery_header_count,
            getString(R.string.app_name),
            itemCount
        )
    }

    private fun updateHeaderScanning() {
        headerTitle.text = getString(
            R.string.gallery_header_scanning,
            getString(R.string.app_name),
            getString(R.string.gallery_scanning)
        )
    }

    private fun updateSelectedMetadata(position: Int) {
        if (!::adapter.isInitialized || !::selectedDateLabel.isInitialized) return
        val photo = if (position in 0 until adapter.itemCount) adapter.getItem(position) else null
        val showDate = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
            .getBoolean(SettingsActivity.SHOW_PHOTO_DATE, true)
        if (photo == null || !showDate) {
            selectedDateLabel.text = ""
            return
        }
        val captureTime = timeline?.captureTimesByPath?.get(photo.absolutePath)
            ?: photo.lastModified()
        selectedDateLabel.text =
            android.text.format.DateFormat.format("MMM dd, yyyy", captureTime)
    }

    private fun toggleOptions() {
        if (optionsOverlay.isVisible) {
            closeOptions()
        } else {
            updateOptionLabels()
            optionsOverlay.show(dateOptionButton)
        }
    }

    private fun closeOptions() {
        optionsOverlay.hide()
    }

    private fun toggleShowDate() {
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        val show = !preferences.getBoolean(SettingsActivity.SHOW_PHOTO_DATE, true)
        preferences.edit().putBoolean(SettingsActivity.SHOW_PHOTO_DATE, show).apply()
        updateOptionLabels()
        updateSelectedMetadata(grid.selectedPosition)
    }

    /** The gallery itself shows no location; this setting is shared with the preview and slideshow. */
    private fun toggleShowLocation() {
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        val show = !preferences.getBoolean(SettingsActivity.SHOW_PHOTO_LOCATION, true)
        preferences.edit().putBoolean(SettingsActivity.SHOW_PHOTO_LOCATION, show).apply()
        updateOptionLabels()
    }

    private fun updateOptionLabels() {
        val preferences = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        dateOptionButton.setToggleText(
            getString(R.string.option_show_photo_date),
            preferences.getBoolean(SettingsActivity.SHOW_PHOTO_DATE, true)
        )
        locationOptionButton.setToggleText(
            getString(R.string.option_show_photo_location),
            preferences.getBoolean(SettingsActivity.SHOW_PHOTO_LOCATION, true)
        )
    }

    private inner class GalleryAdapter : RecyclerView.Adapter<GalleryHolder>() {
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
                (discarded as? CancellableGalleryTask)?.cancel()
                if (executor.isShutdown || !executor.queue.offer(task)) {
                    (task as? CancellableGalleryTask)?.cancel()
                }
            }
        )
        private var closed = false
        private var highlightedPosition = -1

        override fun getItemCount(): Int = photos.size

        fun getItem(position: Int): File = photos[position]

        fun items(): List<File> = photos

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryHolder =
            createHolder()

        fun setPhotos(items: List<File>) {
            photos.clear()
            photos.addAll(items)
            highlightedPosition = -1
            notifyDataSetChanged()
        }

        override fun onBindViewHolder(holder: GalleryHolder, position: Int) {
            val photo = photos[position]
            val key = photo.absolutePath
            holder.boundKey = key
            holder.boundPosition = position
            holder.image.contentDescription = photo.name
            holder.image.tag = key
            holder.root.setOnClickListener {
                val clickedPosition = holder.adapterPosition
                if (clickedPosition != RecyclerView.NO_POSITION) openPreview(clickedPosition)
            }
            val highlighted = position == highlightedPosition && grid.hasFocus()
            applyHighlight(holder, highlighted, animate = false)

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
        }

        override fun onViewRecycled(holder: GalleryHolder) {
            cancel(holder)
            applyHighlight(holder, highlighted = false, animate = false)
            holder.boundKey = null
            holder.boundPosition = RecyclerView.NO_POSITION
            holder.image.tag = null
            holder.image.setImageDrawable(null)
            super.onViewRecycled(holder)
        }

        fun setHighlightedPosition(position: Int) {
            if (highlightedPosition == position) return
            val previousPosition = highlightedPosition
            highlightedPosition = position
            updateHighlight(previousPosition, false)
            updateHighlight(position, true)
        }

        private fun updateHighlight(position: Int, highlighted: Boolean) {
            if (position < 0) return
            for (childIndex in 0 until grid.childCount) {
                val cell = grid.getChildAt(childIndex)
                if (grid.getChildAdapterPosition(cell) == position) {
                    val holder = grid.getChildViewHolder(cell) as? GalleryHolder ?: return
                    applyHighlight(holder, highlighted, animate = true)
                    return
                }
            }
        }

        private fun applyHighlight(holder: GalleryHolder, highlighted: Boolean, animate: Boolean) {
            holder.focusBorder?.visibility = if (highlighted) View.VISIBLE else View.GONE
            val scale = if (highlighted) 1.14f else 1f
            val elevation = if (highlighted) dp(10).toFloat() else 0f
            holder.root.elevation = elevation
            holder.root.translationZ = elevation
            if (animate) {
                holder.root.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(120)
                    .start()
            } else {
                holder.root.animate().cancel()
                holder.root.scaleX = scale
                holder.root.scaleY = scale
            }
        }

        fun bindEdgeThumbnail(position: Int, holder: GalleryHolder) {
            val photo = photos.getOrNull(position)
            if (photo == null) {
                cancel(holder)
                holder.boundKey = null
                holder.image.tag = null
                holder.image.setImageDrawable(null)
                return
            }

            val key = photo.absolutePath
            if (holder.boundKey != key) {
                cancel(holder)
                holder.boundKey = key
                holder.image.tag = key
                holder.image.setImageDrawable(null)
            }
            val cached = thumbnailCache.get(key)
            if (cached != null) {
                cancel(holder)
                holder.image.setImageBitmap(cached)
            } else if (hasFailedThumbnail(key)) {
                cancel(holder)
                holder.image.setImageDrawable(null)
            } else if (holder.taskKey != key) {
                holder.image.setImageDrawable(null)
                val task = ThumbnailTask(key, photo, holder)
                holder.task = task
                holder.taskKey = key
                thumbnailExecutor.execute(task)
            }
        }

        fun close() {
            closed = true
            thumbnailExecutor.shutdownNow().forEach { (it as? CancellableGalleryTask)?.cancel() }
            thumbnailCache.evictAll()
            synchronized(failedThumbnails) { failedThumbnails.clear() }
        }

        private fun createHolder(): GalleryHolder {
            val root = FrameLayout(this@GalleryActivity).apply {
                isFocusable = true
                isFocusableInTouchMode = true
            }
            val image = ImageView(this@GalleryActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
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
            root.addView(image, FrameLayout.LayoutParams(-1, -1))
            root.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
            val focusBorder = View(this@GalleryActivity).apply {
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), Color.WHITE)
                }
                visibility = View.GONE
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            root.addView(focusBorder, FrameLayout.LayoutParams(-1, -1))
            val holder = GalleryHolder(
                root,
                image,
                placeholder,
                focusBorder
            )
            root.setOnFocusChangeListener { _, hasFocus ->
                applyHighlight(holder, hasFocus, animate = true)
            }
            root.isClickable = true
            root.layoutParams = ViewGroup.LayoutParams(dp(thumbnailSizeDp), dp(thumbnailSizeDp))
            return holder
        }

        private fun cancel(holder: GalleryHolder) {
            holder.task?.let {
                it.cancel()
                thumbnailExecutor.remove(it)
            }
            holder.task = null
            holder.taskKey = null
        }

        private fun decodeThumbnail(file: File): Bitmap? =
            PhotoImageDecoder.decode(
                file,
                minOf(dp(thumbnailSizeDp) * 2, 512),
                minOf(dp(thumbnailSizeDp) * 2, 512),
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
        ) : CancellableGalleryTask {
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

            override fun cancel() {
                cancelled = true
                if (holder.task === this) {
                    holder.task = null
                    holder.taskKey = null
                }
            }
        }
    }

    private class GalleryHolder(
        root: View,
        val image: ImageView,
        val placeholder: TextView,
        val focusBorder: View?
    ) : RecyclerView.ViewHolder(root) {
        val root: View = root
        var boundPosition = -1
        var boundKey: String? = null
        var taskKey: String? = null
        var task: CancellableGalleryTask? = null
    }

    private interface CancellableGalleryTask : Runnable {
        fun cancel()
    }

    companion object {
        private const val REQUEST_SLIDESHOW = 1
        private const val REQUEST_PREVIEW = 2
        private const val REQUEST_SETTINGS = 3
        private const val MAX_FAILED_THUMBNAILS = 128
        private const val OPENED_INITIAL_SETTINGS = "opened_initial_settings"
        private const val GRID_SPACING_DP = 10
        private const val GRID_VERTICAL_PADDING_DP = 14
        private const val SHORTCUT_HORIZONTAL_PADDING_DP = 10
        private const val TARGET_VISIBLE_COLUMNS = 7.75

        internal fun computeThumbnailSizeDp(screenWidthDp: Int, screenHeightDp: Int): Int {
            val widthBudget = (screenWidthDp - TV_HORIZONTAL_MARGIN_DP - GRID_SPACING_DP * 7).coerceAtLeast(1)
            val byWidth = (widthBudget / TARGET_VISIBLE_COLUMNS).toInt()
            val verticalChrome = TV_EDGE_MARGIN_DP * 2 + 78 + 24
            val availableHeight = (screenHeightDp - verticalChrome).coerceAtLeast(180)
            val byHeight = (availableHeight - GRID_SPACING_DP * 2 - GRID_VERTICAL_PADDING_DP * 2) / 3
            return minOf(byWidth, byHeight).coerceIn(56, 136)
        }
    }
}
