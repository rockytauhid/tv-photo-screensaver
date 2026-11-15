package com.photodream.tv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

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
                listOf("b.JPG", "photo.webp", "still.gif", "sub/a.png"),
                PhotoFiles.listImages(root).map {
                    it.relativeTo(root).path.replace(File.separatorChar, '/')
                }
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
    fun sortsByNameWithoutCaseSensitivity() {
        val photos = listOf(File("zeta.jpg"), File("Alpha.jpg"), File("beta.jpg"))

        assertEquals(
            listOf("Alpha.jpg", "beta.jpg", "zeta.jpg"),
            PhotoFiles.sortImages(
                photos,
                PhotoSortOrder.NAME,
                true,
                captureTime = { 0L }
            ).map { it.name }
        )
        assertEquals(
            listOf("zeta.jpg", "beta.jpg", "Alpha.jpg"),
            PhotoFiles.sortImages(
                photos,
                PhotoSortOrder.NAME,
                true,
                captureTime = { 0L },
                namesAscending = false
            )
                .map { it.name }
        )
    }

    @Test
    fun galleryDateSortSupportsOldestAndNewestFirst() {
        val photos = listOf(File("middle.jpg"), File("old.jpg"), File("new.jpg"))
        val captureTimes = mapOf("old.jpg" to 10L, "middle.jpg" to 20L, "new.jpg" to 30L)
        val captureTime: (File) -> Long = { captureTimes.getValue(it.name) }

        assertEquals(
            listOf("old.jpg", "middle.jpg", "new.jpg"),
            PhotoFiles.sortGalleryImages(photos, GallerySortOrder.DATE_OLDEST_FIRST, captureTime)
                .map { it.name }
        )
        assertEquals(
            listOf("new.jpg", "middle.jpg", "old.jpg"),
            PhotoFiles.sortGalleryImages(photos, GallerySortOrder.DATE_NEWEST_FIRST, captureTime)
                .map { it.name }
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
}
