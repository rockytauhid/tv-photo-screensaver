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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var folderLabel: TextView
    private lateinit var folderButton: Button
    private lateinit var screensaverButton: Button
    private lateinit var screensaverStatus: TextView
    private lateinit var permissionButton: Button
    private lateinit var speedButton: Button
    private lateinit var metadataButton: Button
    private lateinit var styleButton: Button
    private lateinit var shuffleButton: Button
    private lateinit var photoSetButton: Button
    private lateinit var previewDetailsButton: Button

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
        if (permissionButton.isEnabled) {
            permissionButton.requestFocus()
        } else {
            folderButton.requestFocus()
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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
            gravity = Gravity.TOP
            setBackgroundColor(0xFF202020.toInt())
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
        addButton(header, getString(R.string.settings_close)) { finish() }
        content.addView(header, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        folderLabel = addLabel(content)
        permissionButton = addButton(content, "") { requestPhotoPermission() }
        folderButton = addButton(content, getString(R.string.choose_folder)) {
            startActivityForResult(Intent(this, FolderPickerActivity::class.java), PICK_FOLDER)
        }
        screensaverStatus = addLabel(content)
        screensaverButton = addButton(content, getString(R.string.set_as_screensaver)) {
            openScreensaverSettings()
        }
        addLabel(content).apply { text = getString(R.string.set_screensaver_hint) }
        speedButton = addButton(content, "") { cycleSpeed() }
        metadataButton = addButton(content, "") {
            val enabled = preferences.getBoolean(SHOW_METADATA, true)
            preferences.edit().putBoolean(SHOW_METADATA, !enabled).apply()
            refreshSettings()
        }
        styleButton = addButton(content, "") {
            val current = preferences.getString(TRANSITION, FADE)
            val next = when (current) {
                FADE -> SLIDE
                SLIDE -> CUT
                else -> FADE
            }
            preferences.edit().putString(TRANSITION, next).apply()
            refreshSettings()
        }
        shuffleButton = addButton(content, "") {
            val current = preferences.getString(PHOTO_ORDER, ORDER_DATE_TIME)
            val next = if (current == ORDER_RANDOM) ORDER_DATE_TIME else ORDER_RANDOM
            preferences.edit().putString(PHOTO_ORDER, next).apply()
            refreshSettings()
        }
        photoSetButton = addButton(content, "") {
            if (PhotoFavorites.load(preferences).isNotEmpty()) {
                val current = preferences.getBoolean(PLAY_FAVORITES_ONLY, false)
                preferences.edit().putBoolean(PLAY_FAVORITES_ONLY, !current).apply()
                refreshSettings()
            }
        }
        previewDetailsButton = addButton(content, "") {
            val showDetails = preferences.getBoolean(SHOW_PREVIEW_DETAILS, false)
            preferences.edit().putBoolean(SHOW_PREVIEW_DETAILS, !showDetails).apply()
            refreshSettings()
        }
        addLabel(content).apply { text = getString(R.string.settings_hint) }

        return ScrollView(this).apply {
            setBackgroundColor(0xFF202020.toInt())
            isFocusable = false
            addView(content)
        }
    }

    private fun addLabel(parent: LinearLayout): TextView =
        TextView(this).also {
            it.textSize = 20f
            it.setTextColor(0xFFDDDDDD.toInt())
            it.setPadding(0, 12, 0, 8)
            parent.addView(it)
        }

    private fun addButton(parent: LinearLayout, text: String, onClick: () -> Unit): Button =
        Button(this).also {
            it.text = text
            it.textSize = 18f
            it.isFocusable = true
            it.isFocusableInTouchMode = true
            it.minHeight = 56
            it.setOnClickListener { onClick() }
            parent.addView(it)
        }

    private fun refreshSettings() {
        val folder = preferences.getString(PHOTO_FOLDER, null)
        folderLabel.text = getString(
            R.string.current_folder,
            folder ?: getString(R.string.no_folder_selected)
        )
        val permission = photoPermission()
        val granted = Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        permissionButton.text = getString(
            if (granted) R.string.storage_permission_granted else R.string.grant_storage_permission
        )
        permissionButton.isEnabled = !granted
        folderButton.isEnabled = granted
        val screensaverSelected = isPhotoDreamSelected()
        screensaverStatus.text = getString(
            if (screensaverSelected) R.string.screensaver_selected else R.string.screensaver_not_selected
        )
        screensaverButton.text = getString(
            if (screensaverSelected) R.string.screensaver_already_selected else R.string.set_as_screensaver
        )
        screensaverButton.isEnabled = !screensaverSelected
        val speed = SlideshowSpeed.normalize(preferences)
        speedButton.text = getString(R.string.speed_setting, getString(speed.labelRes))
        metadataButton.text = getString(
            R.string.metadata_setting,
            getString(
                if (preferences.getBoolean(SHOW_METADATA, true)) R.string.on else R.string.off
            )
        )
        val transition = preferences.getString(TRANSITION, FADE)
        val transitionName = when (transition) {
            SLIDE -> R.string.transition_slide
            CUT -> R.string.transition_cut
            else -> R.string.transition_fade
        }
        styleButton.text = getString(R.string.style_setting, getString(transitionName))
        val shuffleEnabled = preferences.getString(PHOTO_ORDER, ORDER_DATE_TIME) == ORDER_RANDOM
        shuffleButton.text = getString(
            R.string.shuffle_setting,
            getString(if (shuffleEnabled) R.string.on else R.string.off)
        )
        val favoriteCount = PhotoFavorites.load(preferences).size
        val favoritesOnly = preferences.getBoolean(PLAY_FAVORITES_ONLY, false) &&
            favoriteCount > 0
        if (favoriteCount == 0 && preferences.getBoolean(PLAY_FAVORITES_ONLY, false)) {
            preferences.edit().putBoolean(PLAY_FAVORITES_ONLY, false).apply()
        }
        photoSetButton.text = getString(
            R.string.slideshow_photo_set,
            getString(if (favoritesOnly) R.string.show_favorite else R.string.show_all)
        )
        photoSetButton.isEnabled = favoriteCount > 0
        previewDetailsButton.text = getString(
            R.string.preview_details_setting,
            getString(if (preferences.getBoolean(SHOW_PREVIEW_DETAILS, false)) R.string.on else R.string.off)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFERENCES = "photo_dream_settings"
        const val PHOTO_FOLDER = "photo_folder"
        const val INTERVAL_SECONDS = "interval_seconds"
        const val SHOW_METADATA = "show_metadata"
        const val TRANSITION = "transition"
        const val PHOTO_ORDER = "photo_order"
        const val PLAY_FAVORITES_ONLY = "play_favorites_only"
        const val SHOW_PREVIEW_DETAILS = "show_preview_details"
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
