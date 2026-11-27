// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.content.Context
import java.io.File

internal interface PhotoSource {
    val id: String
    fun loadCached(): List<IndexedPhoto>
    fun refresh(cached: List<IndexedPhoto>): PhotoReconcileResult
}

internal class LocalPhotoSource(
    private val root: File,
    private val indexFile: File,
    private val captureTime: (File) -> Long = PhotoMetadata::captureTimeMillis
) : PhotoSource {
    override val id: String = "local:${root.absolutePath}"

    override fun loadCached(): List<IndexedPhoto> =
        PhotoIndex.load(indexFile, root.absolutePath)

    override fun refresh(cached: List<IndexedPhoto>): PhotoReconcileResult {
        val currentFiles = PhotoFiles.listImages(root)
        val result = PhotoIndex.reconcile(cached, currentFiles, captureTime)
        if (result.changed || !indexFile.isFile) {
            PhotoIndex.save(indexFile, root.absolutePath, result.entries)
        }
        return result
    }
}

/**
 * Single entry point for loading cached/refreshed photo timelines and playback lists.
 * Future cloud/LAN sources (OneDrive, Plex, WebDAV, SMB) plug in behind [PhotoSource].
 */
internal class PhotoRepository(private val context: Context) {
    private val indexFile: File
        get() = File(context.applicationContext.filesDir, INDEX_FILE_NAME)

    fun localSource(folderPath: String): LocalPhotoSource =
        LocalPhotoSource(File(folderPath), indexFile)

    /**
     * Loads the ordered list of photos for slideshow or screensaver playback, starting from the
     * cached index immediately when available and falling back to a fresh scan when empty.
     */
    fun loadPlaybackPhotos(
        folderPath: String,
        preferences: PhotoPreferences
    ): List<File> {
        val source = localSource(folderPath)
        val cached = source.loadCached()
        val entries = if (cached.isNotEmpty()) cached else source.refresh(emptyList()).entries
        val files = entries.map { File(it.path) }
        val candidates = if (preferences.playFavoritesOnly) {
            PhotoFavorites.filter(files, preferences.favoritePaths())
        } else {
            files
        }
        return when (preferences.sortOrder) {
            PhotoSortOrder.RANDOM -> candidates.shuffled()
            PhotoSortOrder.DATE_TIME -> {
                val timeline = PhotoIndex.buildTimeline(entries)
                PhotoFiles.photosInTimeline(
                    candidates,
                    timeline,
                    year = null,
                    month = null,
                    newestFirst = true
                )
            }
        }
    }

    companion object {
        private const val INDEX_FILE_NAME = "photo_index.tsv"
    }
}
