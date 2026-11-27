// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Source-agnostic photo reference shared across local, LAN (Immich, SMB), and cloud sources.
 * For local storage, [id] is the absolute file path.
 */
internal data class PhotoRef(
    val id: String,
    val sourceId: String,
    val displayName: String,
    val captureTimeMillis: Long,
    val lastModified: Long = 0L,
    val size: Long = 0L
)

internal data class PhotoSourceRefreshResult(
    val items: List<PhotoRef>,
    val changed: Boolean
)

internal interface PhotoSource {
    val id: String
    fun loadCached(): List<PhotoRef>
    fun refresh(cached: List<PhotoRef>): PhotoSourceRefreshResult
    fun open(ref: PhotoRef, maxWidth: Int, maxHeight: Int): InputStream?
}

internal class LocalPhotoSource(
    private val root: File,
    private val indexFile: File,
    private val captureTime: (File) -> Long = PhotoMetadata::captureTimeMillis
) : PhotoSource {
    override val id: String = "local:${root.absolutePath}"

    override fun loadCached(): List<PhotoRef> =
        PhotoIndex.load(indexFile, root.absolutePath).map(::toRef)

    override fun refresh(cached: List<PhotoRef>): PhotoSourceRefreshResult {
        val cachedIndexed = cached.map {
            IndexedPhoto(it.id, it.lastModified, it.size, it.captureTimeMillis)
        }
        val currentFiles = PhotoFiles.listImages(root)
        val result = PhotoIndex.reconcile(cachedIndexed, currentFiles, captureTime)
        if (result.changed || !indexFile.isFile) {
            PhotoIndex.save(indexFile, root.absolutePath, result.entries)
        }
        return PhotoSourceRefreshResult(result.entries.map(::toRef), result.changed)
    }

    override fun open(ref: PhotoRef, maxWidth: Int, maxHeight: Int): InputStream? =
        try {
            FileInputStream(File(ref.id))
        } catch (_: IOException) {
            null
        }

    private fun toRef(entry: IndexedPhoto): PhotoRef =
        PhotoRef(
            id = entry.path,
            sourceId = id,
            displayName = File(entry.path).name,
            captureTimeMillis = entry.captureTimeMillis,
            lastModified = entry.lastModified,
            size = entry.size
        )
}

/**
 * Single entry point for loading cached/refreshed photo timelines and playback lists.
 * Future local-network (Immich, SMB) and cloud sources plug in behind [PhotoSource].
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
        val refs = loadCachedOrRefresh(source)
        val files = refs.map { File(it.id) }
        val candidates = if (preferences.playFavoritesOnly) {
            PhotoFavorites.filter(files, preferences.favoritePaths())
        } else {
            files
        }
        return when (preferences.sortOrder) {
            PhotoSortOrder.RANDOM -> candidates.shuffled()
            PhotoSortOrder.DATE_TIME -> {
                val timeline = PhotoIndex.buildTimelineFromRefs(refs)
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

        /**
         * Offline-first helper: returns cached items immediately when available, or refreshes when
         * empty. Swallows [IOException] so an offline LAN/cloud source degrades gracefully.
         */
        internal fun loadCachedOrRefresh(source: PhotoSource): List<PhotoRef> {
            val cached = source.loadCached()
            if (cached.isNotEmpty()) return cached
            return safeRefresh(source, emptyList()).items
        }

        /**
         * Refreshes [source] against [cached]; if the source is offline or throws [IOException],
         * preserves [cached] with `changed = false` so the UI keeps working uninterrupted.
         */
        internal fun safeRefresh(
            source: PhotoSource,
            cached: List<PhotoRef>
        ): PhotoSourceRefreshResult =
            try {
                source.refresh(cached)
            } catch (_: IOException) {
                PhotoSourceRefreshResult(cached, changed = false)
            }
    }
}
