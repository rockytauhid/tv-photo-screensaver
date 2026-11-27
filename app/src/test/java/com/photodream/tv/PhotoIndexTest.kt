// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhotoIndexTest {
    @Test
    fun savesAndLoadsEntriesForMatchingFolderOnly() {
        val dir = createTempDirectory()
        try {
            val indexFile = File(dir, "photo_index.tsv")
            val entries = listOf(
                IndexedPhoto("/storage/emulated/0/Pictures/a.jpg", 100L, 1234L, 1700000000000L),
                IndexedPhoto("/storage/emulated/0/Pictures/sub/b\t1.jpg", 200L, 5678L, 1700000100000L)
            )

            PhotoIndex.save(indexFile, "/storage/emulated/0/Pictures", entries)

            assertEquals(entries, PhotoIndex.load(indexFile, "/storage/emulated/0/Pictures"))
            assertEquals(emptyList<IndexedPhoto>(), PhotoIndex.load(indexFile, "/storage/other"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun reconcileReusesCachedCaptureTimeForUnchangedFilesAndReadsOnlyNewOrModified() {
        val dir = createTempDirectory()
        try {
            val existing = File(dir, "existing.jpg").apply { writeText("same") }
            val added = File(dir, "added.jpg").apply { writeText("new-photo") }
            val cached = listOf(
                IndexedPhoto(existing.absolutePath, existing.lastModified(), existing.length(), 1111L)
            )
            val readExifCalls = ArrayList<String>()

            val firstReconcile = PhotoIndex.reconcile(cached, listOf(existing)) { file ->
                readExifCalls.add(file.name)
                9999L
            }
            assertFalse(firstReconcile.changed)
            assertEquals(emptyList<String>(), readExifCalls)
            assertEquals(1111L, firstReconcile.entries.single().captureTimeMillis)

            val secondReconcile = PhotoIndex.reconcile(cached, listOf(existing, added)) { file ->
                readExifCalls.add(file.name)
                2222L
            }
            assertTrue(secondReconcile.changed)
            assertEquals(listOf("added.jpg"), readExifCalls)
            assertEquals(
                mapOf(existing.absolutePath to 1111L, added.absolutePath to 2222L),
                secondReconcile.entries.associate { it.path to it.captureTimeMillis }
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun localPhotoSourcePersistsIndexAndReusesItAcrossLoads() {
        val dir = createTempDirectory()
        try {
            val photosDir = File(dir, "photos").apply { mkdir() }
            val indexFile = File(dir, "index.tsv")
            File(photosDir, "one.jpg").writeText("1")
            File(photosDir, "two.png").writeText("2")
            var exifReads = 0

            val source = LocalPhotoSource(photosDir, indexFile) {
                exifReads++
                1700000000000L
            }
            assertEquals(emptyList<IndexedPhoto>(), source.loadCached())

            val first = source.refresh(emptyList())
            assertTrue(first.changed)
            assertEquals(2, first.entries.size)
            assertEquals(2, exifReads)

            val cached = source.loadCached()
            assertEquals(2, cached.size)

            val second = source.refresh(cached)
            assertFalse(second.changed)
            assertEquals(2, exifReads)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun slideshowFailureCapStopsAfterConfiguredLimitOrCollectionSize() {
        assertTrue(SlideshowStage.shouldStopAfterFailure(1, 0))
        assertFalse(SlideshowStage.shouldStopAfterFailure(1, 3))
        assertTrue(SlideshowStage.shouldStopAfterFailure(3, 3))
        assertFalse(SlideshowStage.shouldStopAfterFailure(4, 100))
        assertTrue(SlideshowStage.shouldStopAfterFailure(5, 100))
    }

    private fun createTempDirectory(): File {
        val temporaryFile = File.createTempFile("photo-index", "test")
        temporaryFile.delete()
        temporaryFile.mkdir()
        return temporaryFile
    }
}
