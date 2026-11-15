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

    fun decode(
        file: File,
        maxWidth: Int,
        maxHeight: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
        maxPixels: Int = Int.MAX_VALUE
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val swapsDimensions = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        val width = if (swapsDimensions) bounds.outHeight else bounds.outWidth
        val height = if (swapsDimensions) bounds.outWidth else bounds.outHeight
        var sampleSize = 1
        while (width / sampleSize > maxWidth ||
            height / sampleSize > maxHeight ||
            (width / sampleSize).toLong() * (height / sampleSize).toLong() > maxPixels
        ) {
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = config
            }
        ) ?: return null

        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bitmap

        val rotated = try {
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                Matrix().apply { postRotate(rotation) },
                true
            )
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
            return null
        }
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
