package com.photodream.tv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.GregorianCalendar

class PhotoFilesTest {
    @Test
    fun listsSupportedImagesRecursivelyAndIgnoresHiddenEntries() {
        val root = createTempDirectory()
        try {
            File(root, "sub").mkdir()
            File(root, ".hidden").mkdir()
            listOf(
                "b.JPG",
                "sub/a.png",
                "photo.webp",
                "still.gif",
                "notes.txt",
                ".hidden/secret.jpg"
            ).forEach { path ->
                val file = File(root, path)
                file.parentFile?.mkdirs()
                file.writeText("test")
            }

            assertEquals(
                setOf("b.JPG", "photo.webp", "still.gif", "sub/a.png"),
                PhotoFiles.listImages(root).map {
                    it.relativeTo(root).path.replace(File.separatorChar, '/')
                }.toSet()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun returnsEmptyListForMissingFolder() {
        assertEquals(emptyList<File>(), PhotoFiles.listImages(File("missing-photo-folder")))
    }

    @Test
    fun timelineExposesOnlyAvailableYearsAndMonthsAndFiltersPhotos() {
        val photos = listOf(
            File("january-early.jpg"),
            File("march.jpg"),
            File("january-late.jpg"),
            File("previous-year.jpg")
        )
        val captureTimes = mapOf(
            "january-early.jpg" to timestamp(2024, 1, 2),
            "march.jpg" to timestamp(2024, 3, 10),
            "january-late.jpg" to timestamp(2024, 1, 20),
            "previous-year.jpg" to timestamp(2022, 8, 1)
        )
        val timeline = PhotoFiles.buildTimeline(photos) { captureTimes.getValue(it.name) }

        assertEquals(listOf(2024, 2022), timeline.years)
        assertEquals(listOf(3, 1), timeline.monthsByYear[2024])
        assertEquals(listOf(8), timeline.monthsByYear[2022])
        assertEquals(
            listOf("january-late.jpg", "january-early.jpg"),
            PhotoFiles.photosInTimeline(photos, timeline, 2024, 1, newestFirst = true).map { it.name }
        )
        assertEquals(
            listOf("january-early.jpg", "january-late.jpg", "march.jpg"),
            PhotoFiles.photosInTimeline(photos, timeline, 2024, null, newestFirst = false).map { it.name }
        )
    }

    @Test
    fun subsetTimelineContainsOnlyDatesPresentInTheSubset() {
        val photos = listOf(File("new-year.jpg"), File("old-year.jpg"))
        val captureTimes = mapOf(
            "new-year.jpg" to timestamp(2024, 3, 10),
            "old-year.jpg" to timestamp(2022, 8, 1)
        )
        val fullTimeline = PhotoFiles.buildTimeline(photos) { captureTimes.getValue(it.name) }

        val favoritesTimeline = PhotoFiles.subsetTimeline(photos.take(1), fullTimeline)

        assertEquals(listOf(2024), favoritesTimeline.years)
        assertEquals(listOf(3), favoritesTimeline.monthsByYear[2024])
        assertEquals(
            listOf("new-year.jpg"),
            PhotoFiles.photosInTimeline(
                photos,
                favoritesTimeline,
                year = null,
                month = null,
                newestFirst = true
            ).map { it.name }
        )
    }

    @Test
    fun wrapsNavigationAtBothEnds() {
        assertEquals(3, PhotoFiles.wrapIndex(0, -1, 4))
        assertEquals(0, PhotoFiles.wrapIndex(3, 1, 4))
    }

    @Test
    fun sortsByCaptureTimeInBothDirections() {
        val photos = listOf(File("middle.jpg"), File("old.jpg"), File("new.jpg"))
        val captureTimes = mapOf("old.jpg" to 10L, "middle.jpg" to 20L, "new.jpg" to 30L)
        val captureTime: (File) -> Long = { captureTimes.getValue(it.name) }

        assertEquals(
            listOf("new.jpg", "middle.jpg", "old.jpg"),
            PhotoFiles.sortImages(photos, PhotoSortOrder.DATE_TIME, true, captureTime).map { it.name }
        )
        assertEquals(
            listOf("old.jpg", "middle.jpg", "new.jpg"),
            PhotoFiles.sortImages(photos, PhotoSortOrder.DATE_TIME, false, captureTime).map { it.name }
        )
    }

    @Test
    fun randomOrderKeepsEveryPhotoExactlyOnce() {
        val photos = (0 until 100).map { File("photo-$it.jpg") }

        val shuffled = PhotoFiles.sortImages(
            photos,
            PhotoSortOrder.RANDOM,
            true,
            captureTime = { error("Random order must not read capture times") }
        )

        assertEquals(photos.toSet(), shuffled.toSet())
        assertEquals(photos.size, shuffled.size)
    }

    @Test
    fun listsTenThousandImages() {
        val root = createTempDirectory()
        try {
            repeat(10_000) { index ->
                File(root, "photo-${index.toString().padStart(5, '0')}.jpg").createNewFile()
            }

            assertEquals(10_000, PhotoFiles.listImages(root).size)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createTempDirectory(): File {
        val temporaryFile = File.createTempFile("photo-dream", "test")
        temporaryFile.delete()
        temporaryFile.mkdir()
        return temporaryFile
    }

    private fun timestamp(year: Int, month: Int, day: Int): Long =
        GregorianCalendar(year, month - 1, day, 12, 0).timeInMillis
}
