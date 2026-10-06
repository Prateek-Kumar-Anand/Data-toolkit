package com.prateek.datatoolkit.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Memory-safe image decoding: downsamples oversized photos (a 48 MP camera shot would
 * otherwise OOM-crash the app) and applies the EXIF orientation (phone photos are often
 * stored sideways, which silently breaks OCR).
 */
object SafeBitmap {

    fun decode(context: Context, uri: Uri, maxDim: Int = 4096): Bitmap? =
        decodeFrom({ context.contentResolver.openInputStream(uri) }, maxDim)

    fun decodeFile(file: File, maxDim: Int = 8192): Bitmap? =
        decodeFrom({ FileInputStream(file) }, maxDim)

    private fun decodeFrom(open: () -> InputStream?, maxDim: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
            val raw = open()?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val degrees = try {
                open()?.use { readRotation(it) } ?: 0
            } catch (_: Exception) {
                0
            }
            rotate(raw, degrees)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun readRotation(stream: InputStream): Int =
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        return try {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            if (out !== bitmap) bitmap.recycle()
            out
        } catch (_: OutOfMemoryError) {
            bitmap
        }
    }
}
