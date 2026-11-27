// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FolderPickerNavigationTest {
    @Test
    fun parentFolderSkipsUnreadableEmulatedDirectory() {
        val storageRoot = File("storage")
        val internalStorage = File(storageRoot, "emulated/0")
        val pictures = File(internalStorage, "Pictures")
        val usbVolume = File(storageRoot, "USB-1234")

        assertEquals(
            internalStorage.absolutePath,
            FolderPickerActivity.parentFolderOf(pictures, storageRoot, internalStorage).absolutePath
        )
        assertEquals(
            storageRoot.absolutePath,
            FolderPickerActivity.parentFolderOf(internalStorage, storageRoot, internalStorage).absolutePath
        )
        assertEquals(
            storageRoot.absolutePath,
            FolderPickerActivity.parentFolderOf(usbVolume, storageRoot, internalStorage).absolutePath
        )
        assertEquals(
            storageRoot.absolutePath,
            FolderPickerActivity.parentFolderOf(storageRoot, storageRoot, internalStorage).absolutePath
        )
    }

    @Test
    fun storageRootEntriesShowInternalShortcutsAndUsbVolumesWithoutSystemEntries() {
        val root = createTempDirectory()
        try {
            val emulated = File(root, "emulated").apply { mkdir() }
            val internal = File(emulated, "0").apply { mkdir() }
            val pictures = File(internal, "Pictures").apply { mkdir() }
            val selfDir = File(root, "self").apply { mkdir() }
            val hidden = File(root, ".hidden").apply { mkdir() }
            val usbB = File(root, "USB-B").apply { mkdir() }
            val usbA = File(root, "USB-A").apply { mkdir() }

            val entries = FolderPickerActivity.storageRootEntries(
                listOf(emulated, selfDir, hidden, usbB, usbA),
                internal,
                "Internal Storage",
                "Internal Pictures"
            )

            assertEquals(
                listOf(
                    "Internal Storage" to internal.absolutePath,
                    "Internal Pictures" to pictures.absolutePath,
                    "USB-A" to usbA.absolutePath,
                    "USB-B" to usbB.absolutePath
                ),
                entries.map { it.label to it.directory.absolutePath }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createTempDirectory(): File {
        val temporaryFile = File.createTempFile("photo-dream-folder", "test")
        temporaryFile.delete()
        temporaryFile.mkdir()
        return temporaryFile
    }
}
