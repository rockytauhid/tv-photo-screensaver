// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.IOException

internal object PhotoImageDecoder {
    fun decodeForDisplay(
        context: Context,
        file: File,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888
    ): Bitmap? {
        val metrics = context.resources.displayMetrics
        val bytesPerPixel = if (config == Bitmap.Config.RGB_565) 2L else 4L
        val bitmapBudgetBytes = (Runtime.getRuntime().maxMemory() / 8)
            .coerceIn(8L * 1024 * 1024, 256L * 1024 * 1024)
        return decode(
            file,
            metrics.widthPixels.coerceAtLeast(1),
            metrics.heightPixels.coerceAtLeast(1),
            config,
            (bitmapBudgetBytes / bytesPerPixel).toInt()
        )
    }

    /**
     * Decodes a [PhotoRef] from any [PhotoSource] (local, Immich, SMB, cloud), using [diskCache]
     * for remote sources so EXIF orientation and two-pass sampled decoding work without buffering
     * the full file in RAM and previously fetched photos remain playable offline.
     */
    fun decodeForDisplay(
        context: Context,
        source: PhotoSource,
        ref: PhotoRef,
        diskCache: DiskPhotoCache? = null,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888
    ): Bitmap? {
        val metrics = context.resources.displayMetrics
        val bytesPerPixel = if (config == Bitmap.Config.RGB_565) 2L else 4L
        val bitmapBudgetBytes = (Runtime.getRuntime().maxMemory() / 8)
            .coerceIn(8L * 1024 * 1024, 256L * 1024 * 1024)
        return decodeFromSource(
            source,
            ref,
            metrics.widthPixels.coerceAtLeast(1),
            metrics.heightPixels.coerceAtLeast(1),
            diskCache,
            config,
            (bitmapBudgetBytes / bytesPerPixel).toInt()
        )
    }

    fun decodeFromSource(
        source: PhotoSource,
        ref: PhotoRef,
        maxWidth: Int,
        maxHeight: Int,
        diskCache: DiskPhotoCache? = null,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
        maxPixels: Int = Int.MAX_VALUE
    ): Bitmap? {
        if (source is LocalPhotoSource) {
            return decode(File(ref.id), maxWidth, maxHeight, config, maxPixels)
        }
        val cacheKey = "${ref.sourceId}|${ref.id}|${ref.lastModified}|${maxWidth}x${maxHeight}"
        val cachedFile = diskCache?.get(cacheKey)
        if (cachedFile != null) {
            return decode(cachedFile, maxWidth, maxHeight, config, maxPixels)
        }
        val stream = source.open(ref, maxWidth, maxHeight) ?: return null
        return stream.use { input ->
            if (diskCache != null) {
                val saved = diskCache.putStream(cacheKey, input) ?: return@use null
                decode(saved, maxWidth, maxHeight, config, maxPixels)
            } else {
                val temp = try {
                    File.createTempFile("photo-stream-", ".img")
                } catch (_: IOException) {
                    return@use null
                }
                try {
                    temp.outputStream().use { input.copyTo(it, 16 * 1024) }
                    decode(temp, maxWidth, maxHeight, config, maxPixels)
                } catch (_: IOException) {
                    null
                } finally {
                    temp.delete()
                }
            }
        }
    }

    internal data class EmbeddedThumbnailResult(
        val bitmap: Bitmap?,
        val orientation: Int,
        val sharpEnough: Boolean
    )

    /**
     * Decodes only the tiny embedded EXIF thumbnail (~10–30 KB) with orientation applied, rejecting
     * stale/cropped or mismatched-orientation thumbnails by checking their aspect ratio against
     * the main image. Also returns the parsed EXIF [orientation] so fallback [decode] skips a
     * second EXIF parse.
     */
    fun decodeEmbeddedThumbnail(
        file: File,
        targetPx: Int,
        config: Bitmap.Config = Bitmap.Config.RGB_565
    ): EmbeddedThumbnailResult {
        val exif = try {
            ExifInterface(file.absolutePath)
        } catch (_: IOException) {
            null
        } ?: return EmbeddedThumbnailResult(null, ExifInterface.ORIENTATION_NORMAL, false)

        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val embedded = exif.thumbnailBytes
        if (embedded == null || embedded.isEmpty()) {
            return EmbeddedThumbnailResult(null, orientation, false)
        }

        val thumb = try {
            BitmapFactory.decodeByteArray(
                embedded,
                0,
                embedded.size,
                BitmapFactory.Options().apply { inPreferredConfig = config }
            )
        } catch (_: OutOfMemoryError) {
            null
        } ?: return EmbeddedThumbnailResult(null, orientation, false)

        var mainW = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
        var mainH = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
        if (mainW <= 0 || mainH <= 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            mainW = bounds.outWidth
            mainH = bounds.outHeight
        }

        if (!embeddedAspectMatchesMain(thumb.width, thumb.height, mainW, mainH, orientation)) {
            thumb.recycle()
            return EmbeddedThumbnailResult(null, orientation, false)
        }

        val oriented = applyOrientation(thumb, orientation)
            ?: return EmbeddedThumbnailResult(null, orientation, false)
        val sharpEnough = isThumbnailSharpEnough(oriented.width, oriented.height, targetPx)
        return EmbeddedThumbnailResult(oriented, orientation, sharpEnough)
    }

    internal fun isThumbnailSharpEnough(width: Int, height: Int, targetPx: Int): Boolean =
        width > 0 && height > 0 && minOf(width, height) >= targetPx

    /**
     * Checks that the embedded thumbnail (after applying [orientation]) matches the main image's
     * oriented aspect ratio within 2%. Rejects stale cropped thumbnails and pre-rotated thumbnails
     * that would be double-rotated.
     */
    internal fun embeddedAspectMatchesMain(
        thumbW: Int,
        thumbH: Int,
        imageW: Int,
        imageH: Int,
        orientation: Int
    ): Boolean {
        if (thumbW <= 0 || thumbH <= 0 || imageW <= 0 || imageH <= 0) return false
        val swapped = swapsDimensions(orientation)
        val orientedThumbW = if (swapped) thumbH else thumbW
        val orientedThumbH = if (swapped) thumbW else thumbH
        val orientedImageW = if (swapped) imageH else imageW
        val orientedImageH = if (swapped) imageW else imageH
        val thumbRatio = orientedThumbW.toDouble() / orientedThumbH.toDouble()
        val imageRatio = orientedImageW.toDouble() / orientedImageH.toDouble()
        return kotlin.math.abs(thumbRatio - imageRatio) / imageRatio <= 0.02
    }

    internal fun isUsableEmbeddedThumbnail(
        thumbW: Int,
        thumbH: Int,
        imageW: Int,
        imageH: Int,
        orientation: Int,
        minShortSidePx: Int
    ): Boolean =
        embeddedAspectMatchesMain(thumbW, thumbH, imageW, imageH, orientation) &&
            isThumbnailSharpEnough(thumbW, thumbH, minShortSidePx)

    fun decode(
        file: File,
        maxWidth: Int,
        maxHeight: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
        maxPixels: Int = Int.MAX_VALUE,
        knownOrientation: Int? = null
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val orientation = knownOrientation ?: try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val swapsDimensions = swapsDimensions(orientation)
        val width = if (swapsDimensions) bounds.outHeight else bounds.outWidth
        val height = if (swapsDimensions) bounds.outWidth else bounds.outHeight
        val sampleSize = chooseSampleSize(width, height, maxWidth, maxHeight, maxPixels)
        val bitmap = try {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = config
                }
            )
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null

        return applyOrientation(bitmap, orientation)
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap? {
        val transform = orientationTransform(orientation) ?: return bitmap
        val matrix = Matrix().apply {
            if (transform.rotationDegrees != 0f) postRotate(transform.rotationDegrees)
            if (transform.flipHorizontal) postScale(-1f, 1f)
            if (transform.flipVertical) postScale(1f, -1f)
        }
        val transformed = try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
            return null
        } catch (_: OutOfMemoryError) {
            bitmap.recycle()
            return null
        }
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
    }

    internal data class OrientationTransform(
        val rotationDegrees: Float = 0f,
        val flipHorizontal: Boolean = false,
        val flipVertical: Boolean = false
    )

    internal fun swapsDimensions(orientation: Int): Boolean =
        orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE

    internal fun orientationTransform(orientation: Int): OrientationTransform? = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> OrientationTransform(rotationDegrees = 90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> OrientationTransform(rotationDegrees = 180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> OrientationTransform(rotationDegrees = 270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> OrientationTransform(flipHorizontal = true)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> OrientationTransform(flipVertical = true)
        ExifInterface.ORIENTATION_TRANSPOSE ->
            OrientationTransform(rotationDegrees = 90f, flipHorizontal = true)
        ExifInterface.ORIENTATION_TRANSVERSE ->
            OrientationTransform(rotationDegrees = 270f, flipHorizontal = true)
        else -> null
    }

    /**
     * Pure point transform matching the matrix order applied in [decode] (rotate clockwise, then
     * flip). Used by JVM unit tests to verify all 8 EXIF orientations.
     */
    internal fun applyOrientationPoint(
        x: Int,
        y: Int,
        transform: OrientationTransform?
    ): Pair<Int, Int> {
        if (transform == null) return x to y
        var rx = x
        var ry = y
        when (transform.rotationDegrees) {
            90f -> {
                val nx = -ry
                val ny = rx
                rx = nx
                ry = ny
            }
            180f -> {
                rx = -rx
                ry = -ry
            }
            270f -> {
                val nx = ry
                val ny = -rx
                rx = nx
                ry = ny
            }
        }
        if (transform.flipHorizontal) rx = -rx
        if (transform.flipVertical) ry = -ry
        return rx to ry
    }

    /**
     * The largest power-of-two sample size that still leaves the image at least as big as it is drawn
     * (scaled to fit [maxWidth] x [maxHeight]), so photos are never upscaled and look sharp. A bigger
     * size is used only when the decoded image would exceed [maxPixels].
     */
    fun chooseSampleSize(
        width: Int,
        height: Int,
        maxWidth: Int,
        maxHeight: Int,
        maxPixels: Int = Int.MAX_VALUE
    ): Int {
        val fitScale = minOf(maxWidth.toDouble() / width, maxHeight.toDouble() / height)
            .coerceAtMost(1.0)
        val targetWidth = Math.ceil(width * fitScale).toInt()
        val targetHeight = Math.ceil(height * fitScale).toInt()
        var sampleSize = 1
        while (width / (sampleSize * 2) >= targetWidth && height / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2
        }
        while ((width / sampleSize).toLong() * (height / sampleSize).toLong() > maxPixels &&
            (width / sampleSize > 1 || height / sampleSize > 1)
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
