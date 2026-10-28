package com.vanu.comicpage.export

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream

/** A MediaStore entry that is being written (IS_PENDING until [commit]). */
class SaveTarget(private val cr: ContentResolver, val uri: Uri, val name: String, val out: OutputStream) {
    fun commit() {
        out.close()
        val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        cr.update(uri, v, null, null)
    }

    fun abort() {
        try { out.close() } catch (_: Throwable) {}
        try { cr.delete(uri, null, null) } catch (_: Throwable) {}
    }
}

object MediaSaver {
    const val ALBUM = "ComicPage"

    /** Images go to Pictures/ComicPage, PDFs to Download/ComicPage. */
    fun create(cr: ContentResolver, name: String, mime: String): SaveTarget {
        val isPdf = mime == "application/pdf"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isPdf) Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_PICTURES) + "/" + ALBUM)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (isPdf) MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = cr.insert(collection, values) ?: throw IllegalStateException("MediaStore insert failed")
        val os = cr.openOutputStream(uri) ?: run {
            cr.delete(uri, null, null)
            throw IllegalStateException("cannot open output stream")
        }
        return SaveTarget(cr, uri, name, os)
    }
}
