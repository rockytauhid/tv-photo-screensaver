package com.photodream.tv

import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.Calendar
import java.util.LinkedHashMap
import java.util.Locale

internal enum class PhotoSortOrder {
    DATE_TIME,
    RANDOM
}

internal data class PhotoDate(val year: Int, val month: Int)

internal data class PhotoTimeline(
    val datesByPath: Map<String, PhotoDate>,
    val captureTimesByPath: Map<String, Long>,
    val years: List<Int>,
    val monthsByYear: Map<Int, List<Int>>
)

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

        return photos
    }

    fun buildTimeline(photos: List<File>, captureTime: (File) -> Long): PhotoTimeline {
        val datesByPath = LinkedHashMap<String, PhotoDate>(photos.size)
        val captureTimesByPath = LinkedHashMap<String, Long>(photos.size)
        val calendar = Calendar.getInstance()

        photos.forEach { photo ->
            val path = photo.absolutePath
            val time = captureTime(photo)
            calendar.timeInMillis = time
            datesByPath[path] = PhotoDate(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1
            )
            captureTimesByPath[path] = time
        }

        val monthsByYear = datesByPath.values
            .groupBy(PhotoDate::year, PhotoDate::month)
            .mapValues { (_, months) -> months.distinct().sortedDescending() }

        return PhotoTimeline(
            datesByPath,
            captureTimesByPath,
            monthsByYear.keys.sortedDescending(),
            monthsByYear
        )
    }

    fun subsetTimeline(photos: List<File>, timeline: PhotoTimeline): PhotoTimeline {
        val datesByPath = LinkedHashMap<String, PhotoDate>(photos.size)
        val captureTimesByPath = LinkedHashMap<String, Long>(photos.size)
        photos.forEach { photo ->
            val path = photo.absolutePath
            timeline.datesByPath[path]?.let { datesByPath[path] = it }
            timeline.captureTimesByPath[path]?.let { captureTimesByPath[path] = it }
        }
        val monthsByYear = datesByPath.values
            .groupBy(PhotoDate::year, PhotoDate::month)
            .mapValues { (_, months) -> months.distinct().sortedDescending() }
        return PhotoTimeline(
            datesByPath,
            captureTimesByPath,
            monthsByYear.keys.sortedDescending(),
            monthsByYear
        )
    }

    fun photosInTimeline(
        photos: List<File>,
        timeline: PhotoTimeline,
        year: Int?,
        month: Int?,
        newestFirst: Boolean
    ): List<File> {
        val datedPhotos = photos.mapNotNull { photo ->
            val path = photo.absolutePath
            val date = timeline.datesByPath[path] ?: return@mapNotNull null
            if (year != null && date.year != year) return@mapNotNull null
            if (month != null && date.month != month) return@mapNotNull null
            timeline.captureTimesByPath[path]?.let { photo to it }
        }
        val comparator = if (newestFirst) {
            compareByDescending<Pair<File, Long>> { it.second }
        } else {
            compareBy<Pair<File, Long>> { it.second }
        }
        return datedPhotos.sortedWith(comparator).map { it.first }
    }

    fun sortImages(
        photos: List<File>,
        order: PhotoSortOrder,
        newestFirst: Boolean,
        captureTime: (File) -> Long
    ): List<File> {
        return when (order) {
            PhotoSortOrder.DATE_TIME -> {
                val timedPhotos = photos.map { it to captureTime(it) }
                val comparator = if (newestFirst) {
                    compareByDescending<Pair<File, Long>> { it.second }
                } else {
                    compareBy<Pair<File, Long>> { it.second }
                }
                timedPhotos.sortedWith(comparator).map { it.first }
            }
            PhotoSortOrder.RANDOM -> photos.shuffled()
        }
    }

    fun wrapIndex(currentIndex: Int, delta: Int, size: Int): Int {
        require(size > 0)
        return ((currentIndex + delta) % size + size) % size
    }
}
