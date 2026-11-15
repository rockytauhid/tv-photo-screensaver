package com.photodream.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class FolderPickerActivity : Activity() {
    private lateinit var currentFolder: File
    private lateinit var pathLabel: TextView
    private lateinit var folders: LinearLayout
    private lateinit var useFolderButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentFolder = File(intent.getStringExtra(START_FOLDER) ?: STORAGE_ROOT)
        setContentView(createPickerView())
        showFolder(currentFolder)
    }

    override fun onBackPressed() {
        if (currentFolder.absolutePath == STORAGE_ROOT) {
            super.onBackPressed()
        } else {
            showFolder(currentFolder.parentFile ?: File(STORAGE_ROOT))
        }
    }

    private fun createPickerView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
            setBackgroundColor(0xFF101010.toInt())
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.folder_picker_title)
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
        })
        pathLabel = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFBBBBBB.toInt())
            setPadding(0, 8, 0, 8)
        }
        root.addView(pathLabel)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button(getString(R.string.go_up)) {
            showFolder(currentFolder.parentFile ?: File(STORAGE_ROOT))
        })
        useFolderButton = button(getString(R.string.use_this_folder)) {
            setResult(
                RESULT_OK,
                Intent().putExtra(SELECTED_FOLDER, currentFolder.absolutePath)
            )
            finish()
        }
        controls.addView(useFolderButton)
        root.addView(controls)

        folders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFocusable = false
            addView(folders)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun showFolder(folder: File) {
        currentFolder = folder
        pathLabel.text = folder.absolutePath
        folders.removeAllViews()
        val atStorageRoot = folder.absolutePath == STORAGE_ROOT
        useFolderButton.isEnabled = !atStorageRoot
        val children = folder.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        if (atStorageRoot) {
            folders.addView(TextView(this).apply {
                text = getString(R.string.choose_storage_volume)
                textSize = 18f
                setTextColor(0xFFDDDDDD.toInt())
                setPadding(8, 16, 8, 16)
            })
        }
        if (children.isEmpty()) {
            folders.addView(TextView(this).apply {
                text = getString(R.string.no_subfolders)
                textSize = 18f
                setTextColor(0xFFBBBBBB.toInt())
                setPadding(8, 16, 8, 16)
            })
        }
        children.forEach { child ->
            folders.addView(button(child.name) { showFolder(child) })
        }
        if (atStorageRoot) {
            val internal = File(android.os.Environment.getExternalStorageDirectory(), "Pictures")
            if (internal.isDirectory && children.none { it == internal }) {
                folders.addView(button(getString(R.string.internal_pictures)) {
                    showFolder(internal)
                })
            }
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 18f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            minHeight = 54
            setOnClickListener { onClick() }
        }

    companion object {
        const val SELECTED_FOLDER = "selected_folder"
        private const val START_FOLDER = "start_folder"
        private const val STORAGE_ROOT = "/storage"
    }
}
