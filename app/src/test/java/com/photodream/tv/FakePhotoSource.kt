// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * In-memory test double for [PhotoSource] (fakes-over-mocks pattern) so offline fallbacks,
 * incremental refreshes, and unreadable items can be tested on the JVM without a server or TV.
 */
internal class FakePhotoSource(
    override val id: String = "fake:lan",
    var cached: List<PhotoRef> = emptyList(),
    var remote: List<PhotoRef> = emptyList(),
    var failRefresh: Boolean = false,
    var failingIds: Set<String> = emptySet(),
    var payloads: Map<String, ByteArray> = emptyMap()
) : PhotoSource {
    var refreshCalls = 0
        private set

    override fun loadCached(): List<PhotoRef> = cached

    override fun refresh(cached: List<PhotoRef>): PhotoSourceRefreshResult {
        refreshCalls++
        if (failRefresh) throw IOException("Server unreachable")
        val changed = remote != cached
        this.cached = remote
        return PhotoSourceRefreshResult(remote, changed)
    }

    override fun open(ref: PhotoRef, maxWidth: Int, maxHeight: Int): InputStream? {
        if (ref.id in failingIds) return null
        val bytes = payloads[ref.id] ?: ByteArray(0)
        return ByteArrayInputStream(bytes)
    }
}
