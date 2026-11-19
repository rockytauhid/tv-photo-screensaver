// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var preferences: android.content.SharedPreferences
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
        if (permissionOption.isEnabled) {
            permissionOption.requestFocus()
        } else {
            folderOption.requestFocus()
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
        if (requestCode == REQUEST_STORAGE_PERMISSION) refreshSettings()
    }

    private fun createSettingsView(): View {
        // Semi-transparent like the other option panels; the window dims the screen behind it.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
            gravity = Gravity.TOP
            setBackgroundColor(0xD9222222.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(createOptionText(getString(R.string.settings_close), 15f, 48) { finish() })
        content.addView(header, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        folderLabel = addLabel(content)
        permissionOption = addOption(content) { requestPhotoPermission() }
        folderOption = addOption(content) {
            startActivityForResult(Intent(this, FolderPickerActivity::class.java), PICK_FOLDER)
        }
        screensaverStatus = addLabel(content)
        screensaverOption = addOption(content) { openScreensaverSettings() }
        addLabel(content).text = getString(R.string.set_screensaver_hint)
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
            if (PhotoFavorites.load(preferences).isNotEmpty()) {
                val current = preferences.getBoolean(PLAY_FAVORITES_ONLY, false)
                preferences.edit().putBoolean(PLAY_FAVORITES_ONLY, !current).apply()
                refreshSettings()
            }
        }
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
        addLabel(content).text = getString(R.string.settings_hint)

        return ScrollView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isFocusable = false
            addView(content)
        }
    }

    private fun addLabel(parent: LinearLayout): TextView =
        TextView(this).also {
            it.textSize = 16f
            it.setTextColor(0xFFBBBBBB.toInt())
            it.setPadding(dp(16), dp(8), dp(16), dp(4))
            parent.addView(it)
        }

    private fun addOption(parent: LinearLayout, onClick: () -> Unit): TextView =
        createOptionText("", 16f, 48, onClick).also { parent.addView(it) }

    private fun refreshSettings() {
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
        folderOption.text = getString(R.string.choose_folder)
        folderOption.isEnabled = granted
        val screensaverSelected = isPhotoDreamSelected()
        screensaverStatus.text = getString(
            if (screensaverSelected) R.string.screensaver_selected else R.string.screensaver_not_selected
        )
        screensaverOption.text = getString(
            if (screensaverSelected) R.string.screensaver_already_selected else R.string.set_as_screensaver
        )
        screensaverOption.isEnabled = !screensaverSelected
        val speed = SlideshowSpeed.normalize(preferences)
        speedOption.text = getString(R.string.speed_setting, getString(speed.labelRes))
        val transitionName = when (preferences.getString(TRANSITION, FADE)) {
            SLIDE -> R.string.transition_slide
            CUT -> R.string.transition_cut
            else -> R.string.transition_fade
        }
        styleOption.text = getString(R.string.style_setting, getString(transitionName))
        shuffleOption.setToggleText(
            getString(R.string.option_shuffle),
            preferences.getString(PHOTO_ORDER, ORDER_DATE_TIME) == ORDER_RANDOM
        )
        val favoriteCount = PhotoFavorites.load(preferences).size
        val favoritesOnly = preferences.getBoolean(PLAY_FAVORITES_ONLY, false) &&
            favoriteCount > 0
        if (favoriteCount == 0 && preferences.getBoolean(PLAY_FAVORITES_ONLY, false)) {
            preferences.edit().putBoolean(PLAY_FAVORITES_ONLY, false).apply()
        }
        photoSetOption.text = getString(
            R.string.slideshow_photo_set,
            getString(if (favoritesOnly) R.string.show_favorite else R.string.show_all)
        )
        photoSetOption.isEnabled = favoriteCount > 0
        showDateOption.setToggleText(
            getString(R.string.option_show_photo_date),
            preferences.getBoolean(SHOW_PHOTO_DATE, true)
        )
        showLocationOption.setToggleText(
            getString(R.string.option_show_photo_location),
            preferences.getBoolean(SHOW_PHOTO_LOCATION, true)
        )
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
