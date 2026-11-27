// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.LinkedHashMap

internal data class IndexedPhoto(
    val path: String,
    val lastModified: Long,
    val size: Long,
    val captureTimeMillis: Long
)

internal data class PhotoReconcileResult(
    val entries: List<IndexedPhoto>,
    val changed: Boolean
)

/**
 * Lightweight, zero-dependency disk index of photo capture times keyed by folder path.
 *
 * Uses a simple tab-separated file so reading and writing 10,000+ entries stays fast and low-memory
 * on older Android TVs without allocating JSON object trees.
 */
internal object PhotoIndex {
    private const val HEADER_VERSION = "v1"

    fun load(indexFile: File, expectedFolderPath: String): List<IndexedPhoto> {
        if (!indexFile.isFile) return emptyList()
        return try {
            BufferedReader(InputStreamReader(FileInputStream(indexFile), StandardCharsets.UTF_8)).use { reader ->
                val header = reader.readLine() ?: return emptyList()
                val headerParts = header.split('\t', limit = 2)
                if (headerParts.size != 2 || headerParts[0] != HEADER_VERSION) return emptyList()
                if (unescape(headerParts[1]) != expectedFolderPath) return emptyList()

                val entries = ArrayList<IndexedPhoto>()
                while (true) {
                    val line = reader.readLine() ?: break
                    parseEntry(line)?.let(entries::add)
                }
                entries
            }
        } catch (_: IOException) {
            emptyList()
        }
    }

    fun save(indexFile: File, folderPath: String, entries: List<IndexedPhoto>) {
        val parent = indexFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tempFile = File(parent ?: indexFile.absoluteFile.parentFile, "${indexFile.name}.tmp")
        try {
            BufferedWriter(OutputStreamWriter(FileOutputStream(tempFile), StandardCharsets.UTF_8)).use { writer ->
                writer.write(HEADER_VERSION)
                writer.write('\t'.code)
                writer.write(escape(folderPath))
                writer.newLine()
                entries.forEach { entry ->
                    writer.write(entry.lastModified.toString())
                    writer.write('\t'.code)
                    writer.write(entry.size.toString())
                    writer.write('\t'.code)
                    writer.write(entry.captureTimeMillis.toString())
                    writer.write('\t'.code)
                    writer.write(escape(entry.path))
                    writer.newLine()
                }
            }
            if (!tempFile.renameTo(indexFile)) {
                if (indexFile.exists()) indexFile.delete()
                if (!tempFile.renameTo(indexFile)) tempFile.delete()
            }
        } catch (_: IOException) {
            tempFile.delete()
        }
    }

    /**
     * Reconciles [currentFiles] against [cached] entries, calling [captureTime] only for new or
     * modified files.
     */
    fun reconcile(
        cached: List<IndexedPhoto>,
        currentFiles: List<File>,
        captureTime: (File) -> Long
    ): PhotoReconcileResult {
        val cachedByPath = HashMap<String, IndexedPhoto>(cached.size)
        cached.forEach { cachedByPath[it.path] = it }

        val updated = ArrayList<IndexedPhoto>(currentFiles.size)
        var changed = cached.size != currentFiles.size
        currentFiles.forEach { file ->
            val path = file.absolutePath
            val modified = file.lastModified()
            val length = file.length()
            val existing = cachedByPath[path]
            if (existing != null && existing.lastModified == modified && existing.size == length) {
                updated.add(existing)
            } else {
                changed = true
                updated.add(
                    IndexedPhoto(
                        path = path,
                        lastModified = modified,
                        size = length,
                        captureTimeMillis = captureTime(file)
                    )
                )
            }
        }
        return PhotoReconcileResult(updated, changed)
    }

    fun buildTimeline(entries: List<IndexedPhoto>): PhotoTimeline {
        val datesByPath = LinkedHashMap<String, PhotoDate>(entries.size)
        val captureTimesByPath = LinkedHashMap<String, Long>(entries.size)
        val calendar = Calendar.getInstance()

        entries.forEach { entry ->
            calendar.timeInMillis = entry.captureTimeMillis
            datesByPath[entry.path] = PhotoDate(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1
            )
            captureTimesByPath[entry.path] = entry.captureTimeMillis
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

    fun buildTimelineFromRefs(refs: List<PhotoRef>): PhotoTimeline {
        val datesById = LinkedHashMap<String, PhotoDate>(refs.size)
        val captureTimesById = LinkedHashMap<String, Long>(refs.size)
        val calendar = Calendar.getInstance()

        refs.forEach { ref ->
            calendar.timeInMillis = ref.captureTimeMillis
            datesById[ref.id] = PhotoDate(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1
            )
            captureTimesById[ref.id] = ref.captureTimeMillis
        }

        val monthsByYear = datesById.values
            .groupBy(PhotoDate::year, PhotoDate::month)
            .mapValues { (_, months) -> months.distinct().sortedDescending() }

        return PhotoTimeline(
            datesById,
            captureTimesById,
            monthsByYear.keys.sortedDescending(),
            monthsByYear
        )
    }

    private fun parseEntry(line: String): IndexedPhoto? {
        val parts = line.split('\t', limit = 4)
        if (parts.size != 4) return null
        val modified = parts[0].toLongOrNull() ?: return null
        val size = parts[1].toLongOrNull() ?: return null
        val captureTime = parts[2].toLongOrNull() ?: return null
        val path = unescape(parts[3])
        if (path.isEmpty()) return null
        return IndexedPhoto(path, modified, size, captureTime)
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    private fun unescape(value: String): String {
        if ('\\' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> out.append('\\')
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    else -> {
                        out.append('\\')
                        out.append(value[i + 1])
                    }
                }
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }
}
