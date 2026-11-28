// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale

class FolderPickerActivity : Activity() {
    private lateinit var currentFolder: File
    private var configuredFolderPath: String? = null
    private lateinit var pathLabel: TextView
    private lateinit var summaryLabel: TextView
    private lateinit var upButton: TextView
    private lateinit var useFolderButton: TextView
    private lateinit var scroll: ScrollView
    private lateinit var folders: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.requestFeature(Window.FEATURE_NO_TITLE)
        configuredFolderPath = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
            .getString(SettingsActivity.PHOTO_FOLDER, null)
            ?.let { File(it).absolutePath }
        val requested = intent.getStringExtra(START_FOLDER)?.let(::File)
        currentFolder = if (requested != null && requested.isDirectory) {
            requested
        } else {
            File(STORAGE_ROOT)
        }
        setContentView(createPickerView())
        showFolder(currentFolder)
    }

    override fun onBackPressed() {
        if (currentFolder.absolutePath == STORAGE_ROOT) {
            super.onBackPressed()
        } else {
            navigateUp()
        }
    }

    private fun createPickerView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(28), dp(40), dp(20))
            setBackgroundColor(BACKGROUND_COLOR)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleBlock.addView(TextView(this).apply {
            text = getString(R.string.folder_picker_title)
            textSize = 26f
            typeface = TvDesignTokens.fontLight
            setTextColor(TEXT_COLOR)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        pathLabel = TextView(this).apply {
            textSize = 15f
            typeface = TvDesignTokens.fontLight
            setTextColor(SECONDARY_TEXT_COLOR)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(0, dp(4), 0, 0)
        }
        titleBlock.addView(pathLabel)
        summaryLabel = TextView(this).apply {
            textSize = 13f
            typeface = TvDesignTokens.fontLight
            setTextColor(MUTED_TEXT_COLOR)
            maxLines = 1
            setPadding(0, dp(2), 0, 0)
        }
        titleBlock.addView(summaryLabel)
        header.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f).apply {
            rightMargin = dp(20)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        upButton = createActionButton(getString(R.string.go_up)) { navigateUp() }.apply {
            id = View.generateViewId()
        }
        actions.addView(upButton, LinearLayout.LayoutParams(-2, dp(44)).apply {
            rightMargin = dp(12)
        })
        useFolderButton = createActionButton(getString(R.string.use_this_folder)) {
            setResult(
                RESULT_OK,
                Intent().putExtra(SELECTED_FOLDER, currentFolder.absolutePath)
            )
            finish()
        }.apply {
            id = View.generateViewId()
        }
        actions.addView(useFolderButton, LinearLayout.LayoutParams(-2, dp(44)))
        header.addView(actions, LinearLayout.LayoutParams(-2, -2))
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val divider = View(this).apply {
            setBackgroundColor(DIVIDER_COLOR)
        }
        root.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(16)
            bottomMargin = dp(12)
        })

        folders = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(28))
            clipChildren = false
            clipToPadding = false
        }
        scroll = ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = true
            clipToPadding = false
            addView(folders, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(
            TextView(this).apply {
                text = getString(R.string.folder_picker_hint)
                textSize = 13f
                setTextColor(MUTED_TEXT_COLOR)
                setPadding(0, dp(10), 0, 0)
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        return root
    }

    private fun navigateUp() {
        val previousPath = currentFolder.absolutePath
        val parent = parentFolderOf(
            currentFolder,
            File(STORAGE_ROOT),
            internalStorageDirectory()
        )
        showFolder(parent, focusPath = previousPath)
    }

    private fun showFolder(folder: File, focusPath: String? = null) {
        currentFolder = folder
        pathLabel.text = folder.absolutePath
        folders.removeAllViews()

        val atStorageRoot = folder.absolutePath == STORAGE_ROOT
        upButton.isEnabled = !atStorageRoot
        upButton.isFocusable = !atStorageRoot
        useFolderButton.isEnabled = !atStorageRoot
        useFolderButton.isFocusable = !atStorageRoot
        styleActionButton(upButton, upButton.hasFocus())
        styleActionButton(useFolderButton, useFolderButton.hasFocus())

        val entries: List<FolderEntry>
        if (atStorageRoot) {
            summaryLabel.text = getString(R.string.choose_storage_volume)
            entries = storageRootEntries(
                folder.listFiles()?.toList().orEmpty(),
                internalStorageDirectory(),
                getString(R.string.internal_storage),
                getString(R.string.internal_pictures)
            )
        } else {
            val listed = folder.listFiles()?.toList().orEmpty()
            val childFolders = listed
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
            val directPhotoCount = listed.count(PhotoFiles::isSupportedImage)
            val subfolderText = resources.getQuantityString(
                R.plurals.folder_subfolder_count,
                childFolders.size,
                childFolders.size
            )
            val photoText = resources.getQuantityString(
                R.plurals.folder_photo_count,
                directPhotoCount,
                directPhotoCount
            )
            summaryLabel.text = getString(R.string.folder_summary, subfolderText, photoText)
            entries = childFolders.map { FolderEntry(it.name, it) }
        }

        var focusTarget: View? = null
        var firstRow: View? = null
        entries.forEach { entry ->
            val isCurrent = entry.directory.absolutePath == configuredFolderPath
            val row = createFolderRow(entry.label, isCurrent) {
                showFolder(entry.directory)
            }
            if (firstRow == null) {
                row.id = View.generateViewId()
                firstRow = row
            }
            if (focusPath != null && entry.directory.absolutePath == focusPath) {
                focusTarget = row
            }
            folders.addView(
                row,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            )
        }

        val topRow = firstRow
        if (topRow != null) {
            upButton.nextFocusDownId = topRow.id
            useFolderButton.nextFocusDownId = topRow.id
            topRow.nextFocusUpId = if (useFolderButton.isEnabled) useFolderButton.id else View.NO_ID
        } else {
            upButton.nextFocusDownId = View.NO_ID
            useFolderButton.nextFocusDownId = View.NO_ID
        }

        if (entries.isEmpty()) {
            folders.addView(TextView(this).apply {
                text = getString(R.string.no_subfolders)
                textSize = 16f
                setTextColor(SECONDARY_TEXT_COLOR)
                setPadding(dp(16), dp(20), dp(16), dp(20))
            })
        }

        val target = focusTarget ?: firstRow ?: if (useFolderButton.isEnabled) useFolderButton else upButton
        scroll.post {
            if (focusTarget == null) scroll.scrollTo(0, 0)
            target.requestFocus()
            if (target !== upButton && target !== useFolderButton) {
                scrollRowIntoView(target)
            }
        }
    }

    private fun scrollRowIntoView(row: View) {
        val margin = dp(24)
        val viewportHeight = scroll.height
        if (viewportHeight <= 0) return
        val top = row.top
        val bottom = row.bottom
        val scrollY = scroll.scrollY
        when {
            top - margin < scrollY -> scroll.smoothScrollTo(0, (top - margin).coerceAtLeast(0))
            bottom + margin > scrollY + viewportHeight ->
                scroll.smoothScrollTo(0, bottom + margin - viewportHeight)
        }
    }

    private fun createActionButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            styleActionButton(this, hasFocus = false)
            setOnFocusChangeListener { view, hasFocus ->
                styleActionButton(view as TextView, hasFocus)
            }
            setOnClickListener {
                if (isEnabled) onClick()
            }
        }

    private fun styleActionButton(button: TextView, hasFocus: Boolean) {
        val enabled = button.isEnabled
        button.setTextColor(
            when {
                !enabled -> DISABLED_TEXT_COLOR
                hasFocus -> TEXT_COLOR
                else -> ROW_TEXT_COLOR
            }
        )
        button.typeface = if (hasFocus && enabled) TvDesignTokens.fontRegular else TvDesignTokens.fontLight
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(100).toFloat()
            setColor(
                when {
                    !enabled -> DISABLED_FILL_COLOR
                    hasFocus -> FOCUSED_FILL_COLOR
                    else -> ROW_FILL_COLOR
                }
            )
            if (hasFocus && enabled) {
                setStroke(focusStrokePx(), TEXT_COLOR)
            } else {
                setStroke(dp(1), if (enabled) SUBTLE_BORDER_COLOR else Color.TRANSPARENT)
            }
        }
    }

    private fun createFolderRow(
        label: String,
        isCurrent: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_folder)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val nameView = TextView(this).apply {
            text = label
            textSize = 18f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val currentBadge = if (isCurrent) {
            TextView(this).apply {
                text = getString(R.string.folder_current_badge)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(GOLD_COLOR)
                setPadding(dp(12), 0, dp(12), 0)
            }
        } else {
            null
        }
        val chevron = TextView(this).apply {
            text = "›"
            textSize = 22f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            minimumHeight = dp(56)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                rightMargin = dp(14)
            })
            addView(nameView, LinearLayout.LayoutParams(0, -2, 1f))
            if (currentBadge != null) {
                addView(currentBadge, LinearLayout.LayoutParams(-2, -2))
            }
            addView(chevron, LinearLayout.LayoutParams(-2, -2))
            setOnClickListener { onClick() }
        }

        fun applyRowStyle(focused: Boolean) {
            row.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(if (focused) FOCUSED_FILL_COLOR else ROW_FILL_COLOR)
                if (focused) {
                    setStroke(focusStrokePx(), TEXT_COLOR)
                } else {
                    setStroke(dp(1), SUBTLE_BORDER_COLOR)
                }
            }
            icon.imageTintList = ColorStateList.valueOf(
                when {
                    isCurrent -> GOLD_COLOR
                    focused -> TEXT_COLOR
                    else -> SECONDARY_TEXT_COLOR
                }
            )
            nameView.setTextColor(if (focused) TEXT_COLOR else ROW_TEXT_COLOR)
            nameView.typeface = if (focused) TvDesignTokens.fontRegular else TvDesignTokens.fontLight
            chevron.setTextColor(if (focused) TEXT_COLOR else MUTED_TEXT_COLOR)
        }

        applyRowStyle(focused = false)
        row.setOnFocusChangeListener { _, hasFocus ->
            applyRowStyle(hasFocus)
            if (hasFocus) scrollRowIntoView(row)
        }
        return row
    }

    @Suppress("DEPRECATION")
    private fun internalStorageDirectory(): File? =
        Environment.getExternalStorageDirectory()?.takeIf { it.isDirectory }

    internal data class FolderEntry(val label: String, val directory: File)

    companion object {
        const val SELECTED_FOLDER = "selected_folder"
        const val START_FOLDER = "start_folder"
        private const val STORAGE_ROOT = "/storage"

        private val BACKGROUND_COLOR get() = TvDesignTokens.background
        private val ROW_FILL_COLOR get() = TvDesignTokens.surfaceRow
        private val FOCUSED_FILL_COLOR get() = TvDesignTokens.surfaceFocused
        private val DISABLED_FILL_COLOR get() = TvDesignTokens.surfaceDisabled
        private val SUBTLE_BORDER_COLOR get() = TvDesignTokens.borderSubtle
        private val DIVIDER_COLOR get() = TvDesignTokens.divider
        private val ROW_TEXT_COLOR get() = TvDesignTokens.textPrimary
        private val SECONDARY_TEXT_COLOR get() = TvDesignTokens.textSecondary
        private val MUTED_TEXT_COLOR get() = TvDesignTokens.textMuted
        private val DISABLED_TEXT_COLOR get() = TvDesignTokens.textDisabled

        private val ignoredStorageRootNames = setOf("emulated", "self")

        internal fun parentFolderOf(
            current: File,
            storageRoot: File,
            internalStorage: File?
        ): File {
            if (current.absolutePath == storageRoot.absolutePath) return storageRoot
            if (internalStorage != null && current.absolutePath == internalStorage.absolutePath) {
                return storageRoot
            }
            val parent = current.parentFile ?: return storageRoot
            if (parent.parentFile?.absolutePath == storageRoot.absolutePath &&
                parent.name.lowercase(Locale.ROOT) in ignoredStorageRootNames
            ) {
                return storageRoot
            }
            return parent
        }

        internal fun storageRootEntries(
            storageChildren: List<File>,
            internalStorage: File?,
            internalStorageLabel: String,
            internalPicturesLabel: String
        ): List<FolderEntry> {
            val entries = ArrayList<FolderEntry>()
            val seenPaths = HashSet<String>()

            if (internalStorage != null && internalStorage.isDirectory) {
                if (seenPaths.add(internalStorage.absolutePath)) {
                    entries.add(FolderEntry(internalStorageLabel, internalStorage))
                }
                val pictures = File(internalStorage, "Pictures")
                if (pictures.isDirectory && seenPaths.add(pictures.absolutePath)) {
                    entries.add(FolderEntry(internalPicturesLabel, pictures))
                }
            }

            storageChildren
                .filter {
                    it.isDirectory &&
                        !it.name.startsWith(".") &&
                        it.name.lowercase(Locale.ROOT) !in ignoredStorageRootNames
                }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
                .forEach { volume ->
                    if (seenPaths.add(volume.absolutePath)) {
                        entries.add(FolderEntry(volume.name, volume))
                    }
                }

            return entries
        }
    }
}
