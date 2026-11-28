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
        // When an external USB drive is sleeping or remounting after TV wake, its mount point is
        // temporarily missing or unreadable. Preserve the cached index instead of wiping it.
        if (!isFolderAvailable(root)) {
            return PhotoSourceRefreshResult(cached, changed = false)
        }
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

    companion object {
        fun isFolderAvailable(folder: File): Boolean =
            folder.isDirectory && folder.canRead() && folder.listFiles() != null
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
    ): List<File> =
        orderPlaybackRefs(
            loadCachedOrRefresh(localSource(folderPath)),
            preferences.sortOrder,
            preferences.playFavoritesOnly,
            preferences.favoritePaths()
        ).map { File(it.id) }

    companion object {
        private const val INDEX_FILE_NAME = "photo_index.tsv"

        /**
         * Filters and orders [refs] for slideshow/screensaver playback. If [favoritesOnly] is true
         * but none of [favoritePaths] are present in [refs], falls back to all [refs] for this run
         * without rewriting preferences.
         */
        internal fun orderPlaybackRefs(
            refs: List<PhotoRef>,
            sortOrder: PhotoSortOrder,
            favoritesOnly: Boolean,
            favoritePaths: Set<String>
        ): List<PhotoRef> {
            val candidates = if (favoritesOnly) {
                val matched = refs.filter { it.id in favoritePaths }
                matched.ifEmpty { refs }
            } else {
                refs
            }
            return when (sortOrder) {
                PhotoSortOrder.RANDOM -> candidates.shuffled()
                PhotoSortOrder.DATE_TIME -> candidates.sortedByDescending { it.captureTimeMillis }
            }
        }

        /**
         * Computes the new `nextPhoto` index after a background refresh swaps in [newItems] without
         * interrupting the currently displayed photo ([currentId]). Keeps pointing at [nextId] if
         * it is still present, otherwise continues after [currentId], otherwise starts at 0.
         */
        internal fun nextIndexAfterRefresh(
            newItems: List<PhotoRef>,
            currentId: String?,
            nextId: String?
        ): Int {
            if (newItems.isEmpty()) return 0
            if (nextId != null) {
                val nextIndex = newItems.indexOfFirst { it.id == nextId }
                if (nextIndex >= 0) return nextIndex
            }
            if (currentId != null) {
                val currentIndex = newItems.indexOfFirst { it.id == currentId }
                if (currentIndex >= 0) return (currentIndex + 1) % newItems.size
            }
            return 0
        }

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
