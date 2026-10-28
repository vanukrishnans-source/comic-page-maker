package com.vanu.comicpage.render

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

/** Size info of an image after EXIF rotation. */
data class ImageInfo(val width: Int, val height: Int, val exifDateMs: Long)

/** Decodes images downsampled; the full-size bitmap is never created. */
object ImageLoader {

    private fun orientationOf(cr: ContentResolver, uri: Uri): Int = try {
        cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Throwable) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun swapsAxes(o: Int) = o == ExifInterface.ORIENTATION_ROTATE_90 || o == ExifInterface.ORIENTATION_ROTATE_270 ||
        o == ExifInterface.ORIENTATION_TRANSPOSE || o == ExifInterface.ORIENTATION_TRANSVERSE

    fun probe(cr: ContentResolver, uri: Uri): ImageInfo? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Throwable) {
            return null
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var date = 0L
        var o = ExifInterface.ORIENTATION_NORMAL
        try {
            cr.openInputStream(uri)?.use {
                val exif = ExifInterface(it)
                o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                date = exif.dateTimeOriginal ?: exif.dateTime ?: 0L
            }
        } catch (_: Throwable) {
        }
        return if (swapsAxes(o)) ImageInfo(opts.outHeight, opts.outWidth, date) else ImageInfo(opts.outWidth, opts.outHeight, date)
    }

    /**
     * Decodes the image so that it is at least [targetW] x [targetH] when the source allows
     * (sample size is a power of two, so the result is at most 2x too large) and applies the EXIF rotation.
     */
    fun decode(cr: ContentResolver, uri: Uri, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: Throwable) {
            return null
        }
        if (bounds.outWidth <= 0) return null
        val orient = orientationOf(cr, uri)
        val swap = swapsAxes(orient)
        val srcW = if (swap) bounds.outHeight else bounds.outWidth
        val srcH = if (swap) bounds.outWidth else bounds.outHeight
        var sample = 1
        val tw = max(targetW, 1)
        val th = max(targetH, 1)
        while (srcW / (sample * 2) >= tw && srcH / (sample * 2) >= th) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = try {
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: OutOfMemoryError) {
            // retry coarser
            opts.inSampleSize = sample * 2
            try {
                cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (_: Throwable) {
                null
            }
        } catch (_: Throwable) {
            null
        } ?: return null
        return rotate(bmp, orient)
    }

    private fun rotate(b: Bitmap, o: Int): Bitmap {
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return b
        }
        return try {
            val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            if (r !== b) b.recycle()
            r
        } catch (_: Throwable) {
            b
        }
    }
}
