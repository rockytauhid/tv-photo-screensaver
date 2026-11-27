// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class DiskPhotoCacheTest {
    @Test
    fun storesAndRetrievesStreamsByKey() {
        val dir = createTempDirectory()
        try {
            val cache = DiskPhotoCache(dir, maxBytes = 1024)
            val payload = ByteArray(128) { it.toByte() }

            val stored = cache.putStream("source|photo-1", ByteArrayInputStream(payload))
            assertNotNull(stored)
            assertEquals(128L, cache.totalBytes())

            val fetched = cache.get("source|photo-1")
            assertNotNull(fetched)
            assertArrayEquals(payload, fetched!!.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun evictsOldestEntriesWhenExceedingMaxBytes() {
        val dir = createTempDirectory()
        try {
            val cache = DiskPhotoCache(dir, maxBytes = 250)
            val first = cache.putStream("k1", ByteArrayInputStream(ByteArray(100) { 1 }))!!
            first.setLastModified(1_000L)
            val second = cache.putStream("k2", ByteArrayInputStream(ByteArray(100) { 2 }))!!
            second.setLastModified(2_000L)

            // Touch k1 so k2 becomes the least-recently-used entry.
            assertNotNull(cache.get("k1"))
            first.setLastModified(3_000L)

            // Adding k3 (100 bytes) pushes total to 300 > 250, evicting k2.
            val third = cache.putStream("k3", ByteArrayInputStream(ByteArray(100) { 3 }))
            assertNotNull(third)
            assertTrue(cache.totalBytes() <= 250L)
            assertNull(cache.get("k2"))
            assertNotNull(cache.get("k1"))
            assertNotNull(cache.get("k3"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ignoresEmptyWritesAndSupportsClear() {
        val dir = createTempDirectory()
        try {
            val cache = DiskPhotoCache(dir, maxBytes = 1024)
            assertNull(cache.put("empty") { /* writes nothing */ })
            assertEquals(0L, cache.totalBytes())

            assertNotNull(cache.putStream("a", ByteArrayInputStream(byteArrayOf(7, 8, 9))))
            assertEquals(3L, cache.totalBytes())

            cache.clear()
            assertEquals(0L, cache.totalBytes())
            assertNull(cache.get("a"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cleansUpOrphanedTempFilesOnPut() {
        val dir = createTempDirectory()
        try {
            val orphan = File(dir, "crashed-write.tmp").apply { writeBytes(ByteArray(50)) }
            assertTrue(orphan.exists())

            val cache = DiskPhotoCache(dir, maxBytes = 1024)
            assertNotNull(cache.putStream("fresh", ByteArrayInputStream(byteArrayOf(1, 2, 3))))
            assertTrue(!orphan.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun hashesDistinctKeysToDistinctFilenames() {
        assertNotEquals(DiskPhotoCache.hashKey("a/b"), DiskPhotoCache.hashKey("a_b"))
        assertEquals(64, DiskPhotoCache.hashKey("sample").length)
    }

    private fun createTempDirectory(): File {
        val temporaryFile = File.createTempFile("disk-cache", "test")
        temporaryFile.delete()
        temporaryFile.mkdir()
        return temporaryFile
    }
}
