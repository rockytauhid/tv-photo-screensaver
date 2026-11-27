// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoRepositoryTest {
    @Test
    fun offlineRefreshPreservesCachedPhotosWithoutThrowing() {
        val cached = listOf(photo("1", 1000L), photo("2", 2000L))
        val source = FakePhotoSource(cached = cached, remote = emptyList(), failRefresh = true)

        val result = PhotoRepository.safeRefresh(source, cached)

        assertFalse(result.changed)
        assertEquals(cached, result.items)
        assertEquals(1, source.refreshCalls)
    }

    @Test
    fun loadCachedOrRefreshUsesCacheImmediatelyWhenNonEmpty() {
        val cached = listOf(photo("1", 1000L))
        val remote = listOf(photo("1", 1000L), photo("2", 2000L))
        val source = FakePhotoSource(cached = cached, remote = remote)

        val loaded = PhotoRepository.loadCachedOrRefresh(source)

        assertEquals(cached, loaded)
        assertEquals(0, source.refreshCalls)
    }

    @Test
    fun loadCachedOrRefreshFetchesRemoteWhenCacheIsEmpty() {
        val remote = listOf(photo("1", 1000L), photo("2", 2000L))
        val source = FakePhotoSource(cached = emptyList(), remote = remote)

        val loaded = PhotoRepository.loadCachedOrRefresh(source)

        assertEquals(remote, loaded)
        assertEquals(1, source.refreshCalls)
    }

    @Test
    fun safeRefreshDetectsNewRemotePhotosAndReportsChange() {
        val cached = listOf(photo("1", 1000L))
        val remote = listOf(photo("1", 1000L), photo("2", 2000L))
        val source = FakePhotoSource(cached = cached, remote = remote)

        val updated = PhotoRepository.safeRefresh(source, cached)
        assertTrue(updated.changed)
        assertEquals(remote, updated.items)

        val unchanged = PhotoRepository.safeRefresh(source, updated.items)
        assertFalse(unchanged.changed)
        assertEquals(remote, unchanged.items)
    }

    @Test
    fun fakeSourceOpenReturnsNullForUnreadableItems() {
        val ok = photo("ok", 1000L)
        val bad = photo("bad", 2000L)
        val source = FakePhotoSource(
            failingIds = setOf("bad"),
            payloads = mapOf("ok" to byteArrayOf(1, 2, 3))
        )

        assertNotNull(source.open(ok, 1920, 1080))
        assertNull(source.open(bad, 1920, 1080))
    }

    private fun photo(id: String, time: Long): PhotoRef =
        PhotoRef(id = id, sourceId = "fake:lan", displayName = "$id.jpg", captureTimeMillis = time)
}
