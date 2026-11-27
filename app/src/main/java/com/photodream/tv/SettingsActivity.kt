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
import android.os.Build
import android.os.Bundle
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
import java.util.Locale

class SettingsActivity : Activity() {
    private lateinit var preferences: android.content.SharedPreferences
    private var previewOnly = false
    private lateinit var closeButton: TextView
    private lateinit var folderLabel: TextView
    private lateinit var folderOption: TextView
    private lateinit var screensaverOption: TextView
    private lateinit var screensaverStatus: TextView
    private lateinit var permissionOption: TextView
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
        when {
            previewOnly -> showDateOption.requestFocus()
            permissionOption.isEnabled -> permissionOption.requestFocus()
            folderOption.isEnabled -> folderOption.requestFocus()
            else -> closeButton.requestFocus()
        }
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
            if (folderOption.isEnabled) folderOption.requestFocus()
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
            setTypeface(null, Typeface.BOLD)
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
            permissionOption = addOption(content) { requestPhotoPermission() }
            folderOption = addOption(content) {
                val intent = Intent(this, FolderPickerActivity::class.java)
                preferences.getString(PHOTO_FOLDER, null)?.let {
                    intent.putExtra(FolderPickerActivity.START_FOLDER, it)
                }
                startActivityForResult(intent, PICK_FOLDER)
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
                setTypeface(null, Typeface.BOLD)
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
        val permission = photoPermission()
        val granted = Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        permissionOption.text = getString(
            if (granted) R.string.storage_permission_granted else R.string.grant_storage_permission
        )
        permissionOption.isEnabled = !granted
        refreshCardOptionStyle(permissionOption)

        folderOption.text = getString(R.string.choose_folder)
        folderOption.isEnabled = granted
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

    companion object {
        const val PREFERENCES = "photo_dream_settings"
        const val PREVIEW_ONLY = "preview_only"
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
