// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Bounded, zero-dependency LRU disk cache stored in app-private cache space.
 *
 * Used for pre-sized gallery thumbnails (fast repeat opens) and remote LAN/cloud photos
 * (offline screensaver fallback) while keeping disk usage strictly under [maxBytes].
 */
internal class DiskPhotoCache(
    private val directory: File,
    private val maxBytes: Long
) {
    private val lock = Any()

    fun get(key: String): File? = synchronized(lock) {
        val file = entryFile(key)
        if (!file.isFile || file.length() <= 0L) return null
        file.setLastModified(System.currentTimeMillis())
        file
    }

    fun put(key: String, writer: (OutputStream) -> Unit): File? = synchronized(lock) {
        if (maxBytes <= 0L) return null
        if (!directory.exists() && !directory.mkdirs()) return null
        val target = entryFile(key)
        val temp = File(directory, "${target.name}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                writer(output)
                output.flush()
            }
            if (temp.length() <= 0L) {
                temp.delete()
                return null
            }
            if (!temp.renameTo(target)) {
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return null
                }
            }
            target.setLastModified(System.currentTimeMillis())
            evictIfNeededLocked()
            target
        } catch (_: IOException) {
            temp.delete()
            null
        }
    }

    fun putStream(key: String, input: InputStream): File? =
        put(key) { output ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) output.write(buffer, 0, read)
            }
        }

    fun totalBytes(): Long = synchronized(lock) {
        cacheFilesLocked().sumOf(File::length)
    }

    fun clear() = synchronized(lock) {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun evictIfNeededLocked() {
        val files = cacheFilesLocked()
        var total = files.sumOf(File::length)
        if (total <= maxBytes) return
        val oldestFirst = files.sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))
        for (file in oldestFirst) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) {
                total -= size
            }
        }
    }

    private fun cacheFilesLocked(): List<File> =
        directory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            .orEmpty()

    private fun entryFile(key: String): File = File(directory, hashKey(key))

    companion object {
        const val THUMBNAIL_CACHE_BYTES = 32L * 1024 * 1024
        const val REMOTE_PHOTO_CACHE_BYTES = 128L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 16 * 1024

        internal fun hashKey(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(StandardCharsets.UTF_8))
            val hex = StringBuilder(digest.size * 2)
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                hex.append(HEX_CHARS[value ushr 4])
                hex.append(HEX_CHARS[value and 0x0F])
            }
            return hex.toString()
        }

        private const val HEX_CHARS = "0123456789abcdef"
    }
}
