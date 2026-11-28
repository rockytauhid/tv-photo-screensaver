// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SettingsActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var detailsExecutor: ExecutorService? = null
    private lateinit var preferences: android.content.SharedPreferences
    private var previewOnly = false
    private var previewPhotoPath: String? = null
    private var detailsContainer: LinearLayout? = null
    private lateinit var closeButton: TextView
    private lateinit var folderLabel: TextView
    private lateinit var folderOption: TextView
    private lateinit var screensaverOption: TextView
    private lateinit var screensaverStatus: TextView
    private lateinit var speedOption: TextView
    private lateinit var styleOption: TextView
    private lateinit var shuffleOption: TextView
    private lateinit var photoSetOption: TextView
    private lateinit var showDateOption: TextView
    private lateinit var showLocationOption: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        previewOnly = intent.getBooleanExtra(PREVIEW_ONLY, false)
        previewPhotoPath = intent.getStringExtra(PREVIEW_PHOTO_PATH)
        setContentView(createSettingsView())
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setGravity(Gravity.RIGHT or Gravity.TOP)
        window.setLayout(
            resources.displayMetrics.widthPixels / 2,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        window.attributes = window.attributes.apply { dimAmount = 0.55f }
        refreshSettings()
        if (previewOnly) loadPreviewPhotoDetails()
        when {
            previewOnly -> showDateOption.requestFocus()
            folderOption.isEnabled -> folderOption.requestFocus()
            else -> closeButton.requestFocus()
        }
    }

    override fun onDestroy() {
        detailsExecutor?.shutdownNow()
        detailsExecutor = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshSettings()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FOLDER && resultCode == RESULT_OK) {
            val path = data?.getStringExtra(FolderPickerActivity.SELECTED_FOLDER) ?: return
            preferences.edit().putString(PHOTO_FOLDER, path).apply()
            refreshSettings()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            refreshSettings()
            if (hasPhotoPermission()) {
                openFolderPicker()
            } else {
                folderOption.requestFocus()
            }
        }
    }

    private fun createSettingsView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(28))
            gravity = Gravity.TOP
            setBackgroundColor(TvDesignTokens.panelBackground)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = getString(
                if (previewOnly) R.string.preview_settings_title else R.string.settings_title
            )
            textSize = 26f
            typeface = TvDesignTokens.fontLight
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12) })
        closeButton = createCardOption(getString(R.string.settings_close), 15f, 42, pill = true) {
            finish()
        }
        header.addView(closeButton, LinearLayout.LayoutParams(-2, dp(42)))
        content.addView(header, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(16)
        })

        if (!previewOnly) {
            addSectionHeader(
                content,
                getString(R.string.settings_section_photos),
                subtitle = getString(R.string.settings_hint),
                showDivider = false
            )
            folderLabel = addHelperText(content)
            folderOption = addOption(content) {
                if (hasPhotoPermission()) {
                    openFolderPicker()
                } else {
                    requestPhotoPermission()
                }
            }
        }

        addSectionHeader(
            content,
            getString(R.string.settings_section_display),
            showDivider = !previewOnly
        )
        showDateOption = addOption(content) {
            val enabled = preferences.getBoolean(SHOW_PHOTO_DATE, true)
            preferences.edit().putBoolean(SHOW_PHOTO_DATE, !enabled).apply()
            refreshSettings()
        }
        showLocationOption = addOption(content) {
            val enabled = preferences.getBoolean(SHOW_PHOTO_LOCATION, true)
            preferences.edit().putBoolean(SHOW_PHOTO_LOCATION, !enabled).apply()
            refreshSettings()
        }

        if (previewOnly && previewPhotoPath != null) {
            addSectionHeader(
                content,
                getString(R.string.settings_section_photo_details),
                showDivider = true
            )
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isFocusable = true
                isFocusableInTouchMode = true
                background = createDetailsCardBackground(focused = false)
                setOnFocusChangeListener { v, hasFocus ->
                    v.background = createDetailsCardBackground(focused = hasFocus)
                }
            }
            card.addView(TextView(this).apply {
                text = getString(R.string.detail_loading)
                textSize = 14f
                typeface = TvDesignTokens.fontLight
                setTextColor(TvDesignTokens.textSecondary)
            })
            detailsContainer = card
            content.addView(
                card,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            )
        }

        if (!previewOnly) {
            addSectionHeader(
                content,
                getString(R.string.settings_section_screensaver),
                subtitle = getString(R.string.set_screensaver_hint)
            )
            screensaverStatus = addHelperText(content)
            screensaverOption = addOption(content) { openScreensaverSettings() }

            addSectionHeader(
                content,
                getString(R.string.slideshow_screensaver_section),
                subtitle = getString(R.string.slideshow_screensaver_subtitle)
            )
            speedOption = addOption(content) { cycleSpeed() }
            styleOption = addOption(content) {
                val next = when (preferences.getString(TRANSITION, FADE)) {
                    FADE -> SLIDE
                    SLIDE -> CUT
                    else -> FADE
                }
                preferences.edit().putString(TRANSITION, next).apply()
                refreshSettings()
            }
            shuffleOption = addOption(content) {
                val current = preferences.getString(PHOTO_ORDER, ORDER_DATE_TIME)
                val next = if (current == ORDER_RANDOM) ORDER_DATE_TIME else ORDER_RANDOM
                preferences.edit().putString(PHOTO_ORDER, next).apply()
                refreshSettings()
            }
            photoSetOption = addOption(content) {
                val folder = preferences.getString(PHOTO_FOLDER, null)
                if (PhotoFavorites.hasAnyInFolder(PhotoFavorites.load(preferences), folder)) {
                    val current = preferences.getBoolean(PLAY_FAVORITES_ONLY, false)
                    preferences.edit().putBoolean(PLAY_FAVORITES_ONLY, !current).apply()
                    refreshSettings()
                }
            }
        }

        return ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
            isFillViewport = true
            addView(content, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun addSectionHeader(
        parent: LinearLayout,
        title: String,
        subtitle: String? = null,
        showDivider: Boolean = true
    ) {
        if (showDivider) {
            parent.addView(
                View(this).apply { setBackgroundColor(TvDesignTokens.divider) },
                LinearLayout.LayoutParams(-1, dp(1)).apply {
                    topMargin = dp(16)
                    bottomMargin = dp(14)
                }
            )
        }
        parent.addView(
            TextView(this).apply {
                text = title.uppercase(Locale.getDefault())
                textSize = 12f
                letterSpacing = 0.08f
                typeface = TvDesignTokens.fontRegular
                setTextColor(TvDesignTokens.textMuted)
                setPadding(dp(4), 0, dp(4), dp(4))
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        if (!subtitle.isNullOrBlank()) {
            addHelperText(parent).text = subtitle
        }
    }

    private fun addHelperText(parent: LinearLayout): TextView =
        TextView(this).also {
            it.textSize = 13f
            it.typeface = TvDesignTokens.fontLight
            it.setTextColor(TvDesignTokens.textSecondary)
            it.setPadding(dp(4), dp(2), dp(4), dp(8))
            parent.addView(it, LinearLayout.LayoutParams(-1, -2))
        }

    private fun addOption(parent: LinearLayout, onClick: () -> Unit): TextView =
        createCardOption("", 16f, 48, pill = false, onClick = onClick).also {
            parent.addView(
                it,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            )
        }

    private fun refreshSettings() {
        showDateOption.setToggleText(
            getString(R.string.option_show_photo_date),
            preferences.getBoolean(SHOW_PHOTO_DATE, true)
        )
        showLocationOption.setToggleText(
            getString(R.string.option_show_photo_location),
            preferences.getBoolean(SHOW_PHOTO_LOCATION, true)
        )
        refreshCardOptionStyle(showDateOption)
        refreshCardOptionStyle(showLocationOption)
        if (previewOnly) return

        val folder = preferences.getString(PHOTO_FOLDER, null)
        folderLabel.text = getString(
            R.string.current_folder,
            folder ?: getString(R.string.no_folder_selected)
        )
        folderOption.text = getString(R.string.choose_folder)
        folderOption.isEnabled = true
        refreshCardOptionStyle(folderOption)

        val screensaverSelected = isPhotoDreamSelected()
        screensaverStatus.text = getString(
            if (screensaverSelected) R.string.screensaver_selected else R.string.screensaver_not_selected
        )
        screensaverOption.text = getString(
            if (screensaverSelected) R.string.screensaver_already_selected else R.string.set_as_screensaver
        )
        screensaverOption.isEnabled = !screensaverSelected
        refreshCardOptionStyle(screensaverOption)

        val speed = SlideshowSpeed.normalize(preferences)
        speedOption.text = getString(R.string.speed_setting, getString(speed.labelRes))
        refreshCardOptionStyle(speedOption)

        val transitionName = when (preferences.getString(TRANSITION, FADE)) {
            SLIDE -> R.string.transition_slide
            CUT -> R.string.transition_cut
            else -> R.string.transition_fade
        }
        styleOption.text = getString(R.string.style_setting, getString(transitionName))
        refreshCardOptionStyle(styleOption)

        shuffleOption.setToggleText(
            getString(R.string.option_shuffle),
            preferences.getString(PHOTO_ORDER, ORDER_DATE_TIME) == ORDER_RANDOM
        )
        refreshCardOptionStyle(shuffleOption)

        val hasFavoritesInFolder = PhotoFavorites.hasAnyInFolder(
            PhotoFavorites.load(preferences),
            folder
        )
        val favoritesOnly = preferences.getBoolean(PLAY_FAVORITES_ONLY, false) &&
            hasFavoritesInFolder
        if (!hasFavoritesInFolder && preferences.getBoolean(PLAY_FAVORITES_ONLY, false)) {
            preferences.edit().putBoolean(PLAY_FAVORITES_ONLY, false).apply()
        }
        if (!hasFavoritesInFolder) {
            photoSetOption.text = getString(R.string.slideshow_photo_set_no_favorites)
        } else {
            val value = getString(if (favoritesOnly) R.string.show_favorite else R.string.show_all)
            val full = getString(R.string.slideshow_photo_set, value)
            photoSetOption.text = if (favoritesOnly) {
                SpannableString(full).apply {
                    val start = full.lastIndexOf(value)
                    if (start >= 0) {
                        setSpan(
                            ForegroundColorSpan(GOLD_COLOR),
                            start,
                            start + value.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            } else {
                full
            }
        }
        photoSetOption.isEnabled = hasFavoritesInFolder
        refreshCardOptionStyle(photoSetOption)
    }

    private fun openFolderPicker() {
        val intent = Intent(this, FolderPickerActivity::class.java)
        preferences.getString(PHOTO_FOLDER, null)?.let {
            intent.putExtra(FolderPickerActivity.START_FOLDER, it)
        }
        startActivityForResult(intent, PICK_FOLDER)
    }

    private fun hasPhotoPermission(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(photoPermission()) == PackageManager.PERMISSION_GRANTED

    private fun requestPhotoPermission() {
        requestPermissions(arrayOf(photoPermission()), REQUEST_STORAGE_PERMISSION)
    }

    private fun cycleSpeed() {
        val nextSpeed = SlideshowSpeed.normalize(preferences).next()
        preferences.edit().putInt(INTERVAL_SECONDS, nextSpeed.intervalSeconds).apply()
        refreshSettings()
    }

    private fun isPhotoDreamSelected(): Boolean {
        val selected = Settings.Secure.getString(contentResolver, SCREENSAVER_COMPONENTS) ?: return false
        val component = ComponentName(this, PhotoDream::class.java)
        return selected.split(',').any { entry ->
            val flattened = entry.trim().removePrefix("ComponentInfo{").removeSuffix("}")
            ComponentName.unflattenFromString(flattened) == component
        }
    }

    private fun photoPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun createDetailsCardBackground(focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(if (focused) TvDesignTokens.surfaceCard else TvDesignTokens.surfaceRow)
            setStroke(
                if (focused) focusStrokePx() else dp(1),
                if (focused) Color.WHITE else TvDesignTokens.borderSubtle
            )
        }

    private fun loadPreviewPhotoDetails() {
        val path = previewPhotoPath ?: return
        val file = File(path)
        val worker = detailsExecutor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "photo-details").apply { isDaemon = true }
        }.also { detailsExecutor = it }
        worker.execute {
            val details = PhotoMetadata.readDetails(file)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                renderPhotoDetails(details, locationName = null)
            }
            val coords = details.coordinates ?: return@execute
            val locationName = PhotoLocationNames.lookup(this, coords[0], coords[1]) ?: return@execute
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                renderPhotoDetails(details, locationName = locationName)
            }
        }
    }

    private fun renderPhotoDetails(details: PhotoDetails, locationName: String?) {
        val container = detailsContainer ?: return
        container.removeAllViews()
        val rows = buildList {
            add(getString(R.string.detail_file) to details.fileName)
            details.dateTimeText?.let { add(getString(R.string.detail_date_time) to it) }
            val resolutionAndSize = listOfNotNull(details.resolution, details.fileSizeText)
                .joinToString(" · ")
                .ifEmpty { null }
            resolutionAndSize?.let { add(getString(R.string.detail_resolution) to it) }
            details.camera?.let { add(getString(R.string.detail_camera) to it) }
            details.shutterSpeed?.let { add(getString(R.string.detail_shutter) to it) }
            details.aperture?.let { add(getString(R.string.detail_aperture) to it) }
            details.iso?.let { add(getString(R.string.detail_iso) to it) }
            details.focalLength?.let { add(getString(R.string.detail_focal_length) to it) }
            locationName?.let { add(getString(R.string.detail_location) to it) }
            details.coordinatesText?.let { add(getString(R.string.detail_coordinates) to it) }
        }
        rows.forEachIndexed { index, (label, value) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            row.addView(
                TextView(this).apply {
                    text = label
                    textSize = 14f
                    typeface = TvDesignTokens.fontLight
                    setTextColor(TvDesignTokens.textMuted)
                    maxLines = 1
                },
                LinearLayout.LayoutParams(0, -2, 0.42f).apply { rightMargin = dp(10) }
            )
            row.addView(
                TextView(this).apply {
                    text = value
                    textSize = 14f
                    typeface = TvDesignTokens.fontLight
                    setTextColor(TvDesignTokens.textPrimary)
                    gravity = Gravity.END
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, -2, 0.58f)
            )
            container.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (index < rows.lastIndex) {
                container.addView(
                    View(this).apply { setBackgroundColor(TvDesignTokens.divider) },
                    LinearLayout.LayoutParams(-1, dp(1))
                )
            }
        }
    }

    companion object {
        const val PREFERENCES = "photo_dream_settings"
        const val PREVIEW_ONLY = "preview_only"
        const val PREVIEW_PHOTO_PATH = "preview_photo_path"
        const val PHOTO_FOLDER = "photo_folder"
        const val INTERVAL_SECONDS = "interval_seconds"
        const val SHOW_PHOTO_DATE = "show_photo_date"
        const val SHOW_PHOTO_LOCATION = "show_photo_location"
        const val TRANSITION = "transition"
        const val PHOTO_ORDER = "photo_order"
        const val PLAY_FAVORITES_ONLY = "play_favorites_only"
        const val DREAM_RESUME_PATH = "dream_resume_path"
        const val FADE = "fade"
        const val SLIDE = "slide"
        const val CUT = "cut"
        const val ORDER_DATE_TIME = "date_time"
        const val ORDER_RANDOM = "random"
        private const val SCREENSAVER_COMPONENTS = "screensaver_components"

        private const val PICK_FOLDER = 1
        private const val REQUEST_STORAGE_PERMISSION = 2
    }
}
