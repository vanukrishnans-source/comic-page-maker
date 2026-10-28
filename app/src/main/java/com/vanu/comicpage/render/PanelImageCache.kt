package com.vanu.comicpage.render

import android.content.ContentResolver
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.vanu.comicpage.model.Panel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Byte-bounded cache of downsampled panel bitmaps for the UI (preview + thumbnails).
 * Loads happen on background threads; [version] changes whenever something new arrives so that
 * Compose drawing code that read it redraws.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PanelImageCache(private val cr: ContentResolver, maxBytes: Int) {
    private val cache = object : LruCache<Long, Bitmap>(maxBytes) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(3))
    var version by mutableIntStateOf(0)
        private set

    /** Returns the best cached bitmap now (may be lower resolution) and schedules a better one when needed. */
    fun get(p: Panel, wantW: Int, wantH: Int): Bitmap? {
        val have = synchronized(cache) { cache.get(p.id) }
        if (have != null && have.width >= wantW * 0.9f && have.height >= wantH * 0.9f) return have
        // a source smaller than the wanted size can never satisfy the check, so also stop at source size
        if (have != null && have.width >= p.width && have.height >= p.height) return have
        val alreadyPending = synchronized(cache) { !pending.add(p.id) }
        if (!alreadyPending) {
            scope.launch {
                val bmp = ImageLoader.decode(cr, p.uri, wantW, wantH)
                synchronized(cache) {
                    pending.remove(p.id)
                    if (bmp != null) cache.put(p.id, bmp)
                }
                if (bmp != null) version++
            }
        }
        return have
    }

    fun clear() = synchronized(cache) { cache.evictAll() }
}
