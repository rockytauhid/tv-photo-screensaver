package com.photodream.tv

import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale

internal enum class PhotoSortOrder {
    NAME,
    DATE_TIME,
    RANDOM
}

internal enum class GallerySortOrder(val preferenceValue: String) {
    NAME_ASCENDING("name_ascending"),
    NAME_DESCENDING("name_descending"),
    DATE_OLDEST_FIRST("date_oldest_first"),
    DATE_NEWEST_FIRST("date_newest_first");

    val photoSortOrder: PhotoSortOrder
        get() = when (this) {
            NAME_ASCENDING, NAME_DESCENDING -> PhotoSortOrder.NAME
            DATE_OLDEST_FIRST, DATE_NEWEST_FIRST -> PhotoSortOrder.DATE_TIME
        }

    val newestFirst: Boolean
        get() = this == DATE_NEWEST_FIRST

    val namesAscending: Boolean
        get() = this == NAME_ASCENDING

    fun next(): GallerySortOrder =
        values()[(ordinal + 1) % values().size]

    companion object {
        fun fromPreference(value: String?): GallerySortOrder =
            values().firstOrNull { it.preferenceValue == value } ?: NAME_ASCENDING
    }
}

internal object PhotoFiles {
    private val extensions = setOf("jpg", "jpeg", "png", "webp", "gif")

    fun listImages(root: File): List<File> {
        if (!root.isDirectory) return emptyList()

        val pending = ArrayDeque<File>()
        val visited = HashSet<String>()
        val photos = ArrayList<File>()
        pending.add(root)

        while (!pending.isEmpty()) {
            val directory = pending.removeLast()
            val canonicalPath = try {
                directory.canonicalPath
            } catch (_: IOException) {
                continue
            }
            if (!visited.add(canonicalPath)) continue

            val children = directory.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    pending.add(child)
                } else if (child.isFile &&
                    child.extension.lowercase(Locale.ROOT) in extensions
                ) {
                    photos.add(child)
                }
            }
        }

        return photos.sortedBy { it.absolutePath.lowercase(Locale.ROOT) }
    }

    fun sortImages(
        photos: List<File>,
        order: PhotoSortOrder,
        newestFirst: Boolean,
        captureTime: (File) -> Long,
        namesAscending: Boolean = true
    ): List<File> {
        return when (order) {
            PhotoSortOrder.NAME -> {
                val comparator = if (namesAscending) {
                    compareBy<File> { it.name.lowercase(Locale.ROOT) }
                } else {
                    compareByDescending<File> { it.name.lowercase(Locale.ROOT) }
                }
                photos.sortedWith(comparator.thenBy { it.absolutePath.lowercase(Locale.ROOT) })
            }
            PhotoSortOrder.DATE_TIME -> {
                val timedPhotos = photos.map { it to captureTime(it) }
                val comparator = if (newestFirst) {
                    compareByDescending<Pair<File, Long>> { it.second }
                        .thenBy { it.first.absolutePath.lowercase(Locale.ROOT) }
                } else {
                    compareBy<Pair<File, Long>> { it.second }
                        .thenBy { it.first.absolutePath.lowercase(Locale.ROOT) }
                }
                timedPhotos.sortedWith(comparator).map { it.first }
            }
            PhotoSortOrder.RANDOM -> photos.shuffled()
        }
    }

    fun sortGalleryImages(
        photos: List<File>,
        order: GallerySortOrder,
        captureTime: (File) -> Long
    ): List<File> = sortImages(
        photos,
        order.photoSortOrder,
        order.newestFirst,
        captureTime,
        order.namesAscending
    )

    fun wrapIndex(currentIndex: Int, delta: Int, size: Int): Int {
        require(size > 0)
        return ((currentIndex + delta) % size + size) % size
    }
}
