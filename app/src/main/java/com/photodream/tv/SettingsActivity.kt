package com.photodream.tv

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var folderLabel: TextView
    private lateinit var folderButton: Button
    private lateinit var galleryButton: Button
    private lateinit var startNowButton: Button
    private lateinit var screensaverButton: Button
    private lateinit var screensaverStatus: TextView
    private lateinit var permissionButton: Button
    private lateinit var intervalLabel: TextView
    private lateinit var slowerButton: Button
    private lateinit var fasterButton: Button
    private lateinit var metadataButton: Button
    private lateinit var transitionButton: Button
    private lateinit var orderButton: Button
    private lateinit var dateDirectionButton: Button
    private lateinit var previewDetailsButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        setContentView(createSettingsView())
        refreshSettings()
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
            setPadding(48, 28, 48, 36)
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 20)
        })
        folderLabel = addLabel(content)
        permissionButton = addButton(content, "") { requestPhotoPermission() }
        folderButton = addButton(content, getString(R.string.choose_folder)) {
            startActivityForResult(Intent(this, FolderPickerActivity::class.java), PICK_FOLDER)
        }
        galleryButton = addButton(content, getString(R.string.open_gallery)) {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        startNowButton = addButton(content, "") {
            startActivity(Intent(this, SlideshowActivity::class.java))
        }
        addLabel(content).apply { text = getString(R.string.start_slideshow_hint) }
        screensaverStatus = addLabel(content)
        screensaverButton = addButton(content, getString(R.string.set_as_screensaver)) {
            openScreensaverSettings()
        }
        addLabel(content).apply { text = getString(R.string.set_screensaver_hint) }
        intervalLabel = addLabel(content)
        val intervalControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        slowerButton = addButton(intervalControls, getString(R.string.interval_decrease)) {
            changeInterval(-5)
        }
        fasterButton = addButton(intervalControls, getString(R.string.interval_increase)) {
            changeInterval(5)
        }
        content.addView(intervalControls)
        metadataButton = addButton(content, "") {
            val enabled = preferences.getBoolean(SHOW_METADATA, true)
            preferences.edit().putBoolean(SHOW_METADATA, !enabled).apply()
            refreshSettings()
        }
        transitionButton = addButton(content, "") {
            val current = preferences.getString(TRANSITION, FADE)
            val next = when (current) {
                FADE -> SLIDE
                SLIDE -> CUT
                else -> FADE
            }
            preferences.edit().putString(TRANSITION, next).apply()
            refreshSettings()
        }
        orderButton = addButton(content, "") {
            val next = when (preferences.getString(PHOTO_ORDER, ORDER_NAME)) {
                ORDER_NAME -> ORDER_DATE_TIME
                ORDER_DATE_TIME -> ORDER_RANDOM
                else -> ORDER_NAME
            }
            preferences.edit().putString(PHOTO_ORDER, next).apply()
            refreshSettings()
        }
        dateDirectionButton = addButton(content, "") {
            val newestFirst = preferences.getBoolean(DATE_NEWEST_FIRST, true)
            preferences.edit().putBoolean(DATE_NEWEST_FIRST, !newestFirst).apply()
            refreshSettings()
        }
        previewDetailsButton = addButton(content, "") {
            val showDetails = preferences.getBoolean(SHOW_PREVIEW_DETAILS, false)
            preferences.edit().putBoolean(SHOW_PREVIEW_DETAILS, !showDetails).apply()
            refreshSettings()
        }
        addLabel(content).apply { text = getString(R.string.settings_hint) }

        return ScrollView(this).apply {
            setBackgroundColor(0xFF101010.toInt())
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
        galleryButton.isEnabled = granted && !folder.isNullOrBlank()
        startNowButton.text = getString(R.string.start_screensaver_now)
        startNowButton.isEnabled = granted && !folder.isNullOrBlank()
        val screensaverSelected = isPhotoDreamSelected()
        screensaverStatus.text = getString(
            if (screensaverSelected) R.string.screensaver_selected else R.string.screensaver_not_selected
        )
        screensaverButton.text = getString(
            if (screensaverSelected) R.string.screensaver_already_selected else R.string.set_as_screensaver
        )
        screensaverButton.isEnabled = !screensaverSelected
        val interval = preferences.getInt(INTERVAL_SECONDS, 30).coerceIn(5, 300)
        intervalLabel.text = getString(R.string.interval_setting, interval)
        slowerButton.isEnabled = interval > 5
        fasterButton.isEnabled = interval < 300
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
        transitionButton.text = getString(R.string.transition_setting, getString(transitionName))
        val order = preferences.getString(PHOTO_ORDER, ORDER_NAME)
        val orderName = when (order) {
            ORDER_DATE_TIME -> R.string.order_datetime
            ORDER_RANDOM -> R.string.order_random
            else -> R.string.order_name
        }
        orderButton.text = getString(R.string.order_setting, getString(orderName))
        val newestFirst = preferences.getBoolean(DATE_NEWEST_FIRST, true)
        dateDirectionButton.text = getString(
            R.string.date_direction_setting,
            getString(if (newestFirst) R.string.newest_first else R.string.oldest_first)
        )
        dateDirectionButton.isEnabled = order == ORDER_DATE_TIME
        previewDetailsButton.text = getString(
            R.string.preview_details_setting,
            getString(if (preferences.getBoolean(SHOW_PREVIEW_DETAILS, false)) R.string.on else R.string.off)
        )
    }

    private fun requestPhotoPermission() {
        requestPermissions(arrayOf(photoPermission()), REQUEST_STORAGE_PERMISSION)
    }

    private fun changeInterval(delta: Int) {
        val current = preferences.getInt(INTERVAL_SECONDS, 30)
        preferences.edit().putInt(INTERVAL_SECONDS, (current + delta).coerceIn(5, 300)).apply()
        refreshSettings()
    }

    private fun openScreensaverSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.screensaver_settings_unavailable, Toast.LENGTH_LONG).show()
        }
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
        const val SHOW_METADATA = "show_metadata"
        const val TRANSITION = "transition"
        const val PHOTO_ORDER = "photo_order"
        const val DATE_NEWEST_FIRST = "date_newest_first"
        const val SHOW_PREVIEW_DETAILS = "show_preview_details"
        const val FADE = "fade"
        const val SLIDE = "slide"
        const val CUT = "cut"
        const val ORDER_NAME = "name"
        const val ORDER_DATE_TIME = "date_time"
        const val ORDER_RANDOM = "random"
        private const val SCREENSAVER_COMPONENTS = "screensaver_components"

        private const val PICK_FOLDER = 1
        private const val REQUEST_STORAGE_PERMISSION = 2
    }
}
