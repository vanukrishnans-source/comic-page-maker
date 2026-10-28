package com.vanu.comicpage.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.LruCache
import com.vanu.comicpage.layout.Cell
import com.vanu.comicpage.layout.LayoutEngine
import com.vanu.comicpage.layout.PageLayout
import com.vanu.comicpage.model.PageSettings
import com.vanu.comicpage.model.Panel
import com.vanu.comicpage.model.PanelFit
import com.vanu.comicpage.render.ImageLoader
import com.vanu.comicpage.render.PageRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    PNG("PNG", "png", "image/png"),
    JPG("JPG", "jpg", "image/jpeg"),
    PDF("PDF", "pdf", "application/pdf"),
}

data class ExportOptions(
    val format: ExportFormat = ExportFormat.PNG,
    /** true = keep the page in one image whenever the format can hold it (PNG: any height). */
    val singleImage: Boolean = false,
)

data class ExportResult(val uris: List<Uri>, val names: List<String>, val width: Int, val height: Int, val note: String?, val peakTileBytes: Long)

class Exporter(private val ctx: Context) {

    companion object {
        /** Gallery apps and GPUs struggle above this height, so "Auto" cuts parts at panel-row boundaries. */
        const val AUTO_MAX_HEIGHT = 16000f
        /** One JPEG needs the whole part as a bitmap: keep it to this many pixels (~96 MB). */
        const val JPG_MAX_PIXELS = 24_000_000L
        /** Longest PDF page, as a multiple of the page width (about 2 x A4). */
        const val PDF_MAX_PAGE_RATIO = 2.8f
        const val PDF_MAX_WIDTH = 1080
        private const val TILE_PIXELS = 3_000_000
        private const val DECODE_CACHE_BYTES = 96 * 1024 * 1024
        fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }

    private val cr = ctx.contentResolver

    /** Plans the parts (y ranges in page px) for the options, plus a note when the plan differs from what was asked. */
    fun plan(layout: PageLayout, opts: ExportOptions): Pair<List<ClosedFloatingPointRange<Float>>, String?> {
        val w = layout.width.toFloat()
        return when (opts.format) {
            ExportFormat.PNG -> {
                if (opts.singleImage) listOf(0f..layout.height) to null
                else LayoutEngine.splitParts(layout, AUTO_MAX_HEIGHT) to null
            }
            ExportFormat.JPG -> {
                val maxByPixels = JPG_MAX_PIXELS / w
                val limit = if (opts.singleImage) maxByPixels else min(AUTO_MAX_HEIGHT, maxByPixels)
                val parts = LayoutEngine.splitParts(layout, limit.toFloat())
                val note = if (opts.singleImage && parts.size > 1) "JPG cannot hold a page this tall in one file, so it was split into ${parts.size} parts. Use PNG for a single image." else null
                parts to note
            }
            ExportFormat.PDF -> LayoutEngine.splitParts(layout, w * PDF_MAX_PAGE_RATIO) to null
        }
    }

    suspend fun export(
        panels: List<Panel>,
        settings: PageSettings,
        opts: ExportOptions,
        onProgress: (Float, String) -> Unit,
    ): ExportResult = withContext(Dispatchers.Default) {
        val st = if (opts.format == ExportFormat.PDF) settings.copy(pageWidth = min(settings.pageWidth, PDF_MAX_WIDTH)) else settings
        val layout = LayoutEngine.layout(panels.map { it.aspect }, st)
        require(layout.bands.isNotEmpty()) { "No images" }
        val renderer = PageRenderer(st, layout)
        val (parts, note) = plan(layout, opts)
        val cache = DecodeCache(st)
        val stamp = timestamp()
        val uris = ArrayList<Uri>()
        val names = ArrayList<String>()
        val total = max(layout.height, 1f)
        var done = 0f
        var peak = 0L
        val written = ArrayList<SaveTarget>()
        try {
            if (opts.format == ExportFormat.PDF) {
                val name = "ComicPage_$stamp.pdf"
                val pdf = PdfDocument()
                val k = 595f / layout.width
                for ((pi, part) in parts.withIndex()) {
                    coroutineContext.ensureActive()
                    val hPt = max(((part.endInclusive - part.start) * k).roundToInt(), 1)
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, hPt, pi + 1).create())
                    val c = page.canvas
                    c.scale(k, k)
                    c.translate(0f, -part.start)
                    renderer.drawRegion(c, part.start, part.endInclusive, panels, { i, cell -> cache.get(panels[i], cell) })
                    pdf.finishPage(page)
                    cache.clear()
                    done += part.endInclusive - part.start
                    onProgress(done / total, "Page ${pi + 1} of ${parts.size}")
                }
                val t = MediaSaver.create(cr, name, ExportFormat.PDF.mime)
                written += t
                try {
                    pdf.writeTo(t.out)
                } finally {
                    pdf.close()
                }
                t.commit()
                uris += t.uri; names += t.name
            } else {
                val multi = parts.size > 1
                val rowBounds = IntArray(parts.size + 1)
                for ((i, p) in parts.withIndex()) rowBounds[i] = p.start.roundToInt()
                val pageH = ceil(layout.height).toInt()
                rowBounds[parts.size] = pageH
                for (i in parts.indices) {
                    coroutineContext.ensureActive()
                    val r0 = if (i == 0) 0 else rowBounds[i]
                    val r1 = if (i == parts.lastIndex) pageH else rowBounds[i + 1]
                    val ph = r1 - r0
                    val name = "ComicPage_$stamp" + (if (multi) "_" + String.format(Locale.US, "%02d", i + 1) else "") + "." + opts.format.ext
                    val label = if (multi) "Part ${i + 1} of ${parts.size}" else "Saving"
                    val t = MediaSaver.create(cr, name, opts.format.mime)
                    written += t
                    if (opts.format == ExportFormat.PNG) {
                        peak = max(peak, writePng(t, renderer, layout, panels, cache, r0, r1) { rows ->
                            done += rows
                            onProgress(min(done / total, 0.99f), label)
                        })
                    } else {
                        val bmp = Bitmap.createBitmap(layout.width, ph, Bitmap.Config.ARGB_8888)
                        peak = max(peak, bmp.byteCount.toLong())
                        try {
                            val c = Canvas(bmp)
                            c.translate(0f, -r0.toFloat())
                            renderer.drawRegion(c, r0.toFloat(), r1.toFloat(), panels, { idx, cell -> cache.get(panels[idx], cell) })
                            coroutineContext.ensureActive()
                            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, t.out)) throw IllegalStateException("JPEG encode failed")
                        } finally {
                            bmp.recycle()
                        }
                        done += ph
                        onProgress(min(done / total, 0.99f), label)
                    }
                    t.commit()
                    uris += t.uri; names += t.name
                    cache.clear()
                }
            }
            onProgress(1f, "Done")
            ExportResult(uris, names, layout.width, ceil(layout.height).toInt(), note, peak)
        } catch (e: Throwable) {
            for (t in written) t.abort()
            throw e
        }
    }

    /** Renders rows [r0, r1) tile by tile and streams them into the PNG. Returns the size of the tile bitmap used. */
    private suspend fun writePng(
        t: SaveTarget, renderer: PageRenderer, layout: PageLayout, panels: List<Panel>, cache: DecodeCache,
        r0: Int, r1: Int, progress: (Int) -> Unit,
    ): Long {
        val w = layout.width
        val tileH = (TILE_PIXELS / w).coerceIn(128, 2048)
        val tile = Bitmap.createBitmap(w, tileH, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * tileH)
        val writer = PngStreamWriter(t.out, w, r1 - r0)
        try {
            val canvas = Canvas(tile)
            var y = r0
            while (y < r1) {
                coroutineContext.ensureActive()
                val h = min(tileH, r1 - y)
                canvas.save()
                canvas.translate(0f, -y.toFloat())
                renderer.drawRegion(canvas, y.toFloat(), (y + h).toFloat(), panels, { idx, cell -> cache.get(panels[idx], cell) })
                canvas.restore()
                tile.getPixels(px, 0, w, 0, 0, w, h)
                writer.writeRows(px, h)
                progress(h)
                y += h
            }
            writer.finish()
        } finally {
            tile.recycle()
        }
        return w.toLong() * tileH * 4
    }

    /** Decodes panels at export resolution; keeps a small byte-bounded LRU so a panel spanning several tiles is decoded once. */
    private inner class DecodeCache(private val st: PageSettings) {
        private val lru = object : LruCache<Long, Bitmap>(DECODE_CACHE_BYTES) {
            override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
        }
        private val failed = HashSet<Long>()

        fun get(p: Panel, cell: Cell): Bitmap? {
            lru.get(p.id)?.let { return it }
            if (p.id in failed) return null
            val sx = cell.w / p.width
            val sy = cell.h / p.height
            val sc = if (st.fit == PanelFit.FILL) max(sx, sy) else min(sx, sy)
            val tw = ceil(p.width * sc).toInt()
            val th = ceil(p.height * sc).toInt()
            val b = ImageLoader.decode(cr, p.uri, tw, th)
            if (b == null) failed += p.id else lru.put(p.id, b)
            return b
        }

        fun clear() = lru.evictAll()
    }
}
