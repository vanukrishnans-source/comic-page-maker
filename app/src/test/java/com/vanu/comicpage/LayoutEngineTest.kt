package com.vanu.comicpage

import com.vanu.comicpage.export.PngStreamWriter
import com.vanu.comicpage.layout.LayoutEngine
import com.vanu.comicpage.model.PageSettings
import com.vanu.comicpage.model.PageStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.math.abs

/** Minimal PNG reader (RGB8, filter 0/1) so the test does not need javax.imageio. Returns (w, h, packed rgb) */
private fun decodePng(b: ByteArray): Triple<Int, Int, IntArray> {
    var p = 8
    var w = 0; var h = 0
    val idat = ByteArrayOutputStream()
    var sawEnd = false
    fun i32(o: Int) = ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)
    while (p < b.size) {
        val len = i32(p)
        val type = String(b, p + 4, 4, Charsets.US_ASCII)
        val crc = CRC32().also { it.update(b, p + 4, 4 + len) }.value.toInt()
        assertEquals("crc of $type", crc, i32(p + 8 + len))
        when (type) {
            "IHDR" -> { w = i32(p + 8); h = i32(p + 12); assertEquals(8, b[p + 16].toInt()); assertEquals(2, b[p + 17].toInt()) }
            "IDAT" -> idat.write(b, p + 8, len)
            "IEND" -> sawEnd = true
        }
        p += 12 + len
    }
    assertTrue(sawEnd)
    val raw = ByteArray((1 + w * 3) * h)
    val inf = Inflater().also { it.setInput(idat.toByteArray()) }
    var got = 0
    while (got < raw.size) { val n = inf.inflate(raw, got, raw.size - got); if (n == 0) break; got += n }
    assertEquals(raw.size, got)
    val out = IntArray(w * h)
    for (y in 0 until h) {
        val base = y * (1 + w * 3)
        assertEquals(1, raw[base].toInt())
        var r = 0; var g = 0; var bl = 0
        for (x in 0 until w) {
            r = (r + raw[base + 1 + x * 3]) and 255; g = (g + raw[base + 2 + x * 3]) and 255; bl = (bl + raw[base + 3 + x * 3]) and 255
            out[y * w + x] = (r shl 16) or (g shl 8) or bl
        }
    }
    return Triple(w, h, out)
}

class LayoutEngineTest {
    private val mixed = listOf(1.5f, 0.67f, 0.67f, 1.0f, 1.78f, 0.75f, 0.75f, 0.75f, 1.33f, 0.56f, 2.0f, 1.0f)
    private fun s(style: PageStyle, w: Int = 1080, g: Int = 24) = PageSettings(style = style, pageWidth = w, gutter = g)

    @Test fun webtoonStacksFullWidthInOrder() {
        val l = LayoutEngine.layout(mixed, s(PageStyle.WEBTOON, g = 20))
        val cells = l.cells
        assertEquals(mixed.size, cells.size)
        var y = 0f
        for ((i, c) in cells.withIndex()) {
            assertEquals(i, c.index)
            assertEquals(0f, c.x, 0.001f); assertEquals(1080f, c.w, 0.001f)
            assertEquals(1080f / mixed[i], c.h, 0.01f)
            assertEquals(y, c.y, 0.01f)
            y = c.bottom + 20f
        }
        assertEquals(cells.last().bottom, l.height, 0.01f)
    }

    @Test fun classicKeepsOrderAndStaysInsideThePage() {
        for (style in listOf(PageStyle.CLASSIC, PageStyle.MANGA)) {
            val l = LayoutEngine.layout(mixed, s(style))
            assertEquals((0 until mixed.size).toList(), l.cells.map { it.index })
            for (c in l.cells) {
                assertTrue(c.x >= 24f - 0.01f && c.x + c.w <= 1080f - 24f + 0.01f)
                assertTrue(c.y >= 24f - 0.01f)
            }
            for (b in l.bands) assertTrue(b.cells.size in 1..3)
        }
    }

    @Test fun classicRowsAreFullWidthAndGuttersAreEqual() {
        val l = LayoutEngine.layout(mixed, s(PageStyle.CLASSIC))
        for (b in l.bands) {
            val sorted = b.cells.sortedBy { it.x }
            assertEquals(24f, sorted.first().x, 0.01f)
            assertEquals(1080f - 24f, sorted.last().x + sorted.last().w, 0.01f)
            for (i in 1 until sorted.size) assertEquals(24f, sorted[i].x - (sorted[i - 1].x + sorted[i - 1].w), 0.01f)
            for (c in b.cells) assertEquals(b.cells[0].h, c.h, 0.001f)
        }
    }

    @Test fun mangaIsMirroredClassic() {
        val a = LayoutEngine.layout(mixed, s(PageStyle.CLASSIC))
        val m = LayoutEngine.layout(mixed, s(PageStyle.MANGA))
        assertEquals(a.height, m.height, 0.01f)
        for ((ca, cm) in a.cells.zip(m.cells)) {
            assertEquals(ca.index, cm.index)
            assertEquals(ca.y, cm.y, 0.01f)
            assertEquals(1080f - ca.x - ca.w, cm.x, 0.01f)
        }
        // first panel of a multi-panel row sits at the right edge in manga
        val row = m.bands.first { it.cells.size > 1 }
        assertTrue(row.cells.first().x > row.cells.last().x)
    }

    @Test fun portraitsGroupAndLandscapesRunWide() {
        val portraits = List(9) { 0.67f }
        assertEquals(List(3) { 3 }, LayoutEngine.rowCounts(portraits, s(PageStyle.CLASSIC)))
        val wide = List(4) { 2.2f }
        assertTrue(LayoutEngine.rowCounts(wide, s(PageStyle.CLASSIC)).all { it <= 2 })
    }

    @Test fun maxPerRowIsRespected() {
        for (mx in 1..3) {
            val counts = LayoutEngine.rowCounts(mixed, s(PageStyle.CLASSIC).copy(maxPerRow = mx))
            assertTrue(counts.all { it <= mx })
            assertEquals(mixed.size, counts.sum())
        }
    }

    @Test fun rowsAreBalanced() {
        val l = LayoutEngine.layout(mixed, s(PageStyle.CLASSIC))
        for (b in l.bands) assertTrue("row height ${b.cells[0].h}", b.cells[0].h in 0.28f * 1080 - 1..1.3f * 1080 + 1)
    }

    @Test fun gridColumns() {
        val st = s(PageStyle.GRID).copy(gridCols = 3, gridAspect = 1f)
        val l = LayoutEngine.layout(mixed, st)
        assertEquals(4, l.bands.size)
        assertEquals(3, l.bands[0].cells.size)
        val c = l.cells[0]
        assertEquals(c.w, c.h, 0.01f)
        assertEquals(24f, c.x, 0.01f)
    }

    @Test fun scalesWithPageWidth() {
        val a = LayoutEngine.layout(mixed, s(PageStyle.CLASSIC, 1080))
        val b = LayoutEngine.layout(mixed, s(PageStyle.CLASSIC, 2160))
        assertEquals(a.height * 2, b.height, 0.5f)
    }

    @Test fun sixtyImagesAndSplit() {
        val r = Random(3)
        val many = List(60) { listOf(0.67f, 1.5f, 1.0f, 0.75f, 1.78f)[r.nextInt(5)] }
        for (style in PageStyle.entries) {
            val l = LayoutEngine.layout(many, s(style, 2160))
            assertEquals(60, l.cells.size)
            val parts = LayoutEngine.splitParts(l, 16000f)
            // contiguous, covers the page, none taller than the limit unless a single band
            assertEquals(0f, parts.first().start, 0.001f)
            assertEquals(l.height, parts.last().endInclusive, 0.5f)
            for (i in 1 until parts.size) assertEquals(parts[i - 1].endInclusive, parts[i].start, 0.001f)
            for (p in parts) assertTrue(p.endInclusive - p.start <= 16000f + 1 || l.bands.any { it.height > 16000f })
            println("$style: page ${l.width}x${l.height.toInt()} -> ${parts.size} part(s)")
        }
    }

    @Test fun emptyAndSingle() {
        assertEquals(0f, LayoutEngine.layout(emptyList(), s(PageStyle.CLASSIC)).height, 0f)
        val one = LayoutEngine.layout(listOf(1f), s(PageStyle.GRID))
        assertEquals(1, one.cells.size)
    }

    @Test fun naturalSort() {
        val names = listOf("page10.jpg", "Page2.jpg", "page1.jpg", "page01a.jpg", "img_9.png", "IMG_10.png")
        val sorted = names.sortedWith { a, b -> LayoutEngine.naturalCompare(a, b) }
        assertEquals(listOf("img_9.png", "IMG_10.png", "page01a.jpg", "page1.jpg", "Page2.jpg", "page10.jpg").size, sorted.size)
        assertTrue(sorted.indexOf("page1.jpg") < sorted.indexOf("Page2.jpg"))
        assertTrue(sorted.indexOf("Page2.jpg") < sorted.indexOf("page10.jpg"))
        assertTrue(sorted.indexOf("img_9.png") < sorted.indexOf("IMG_10.png"))
    }

    @Test fun streamingPngDecodesCorrectly() {
        val w = 37
        val h = 53
        val r = Random(1)
        val px = IntArray(w * h) { 0xFF000000.toInt() or r.nextInt(0xFFFFFF) }
        val bos = ByteArrayOutputStream()
        val png = PngStreamWriter(bos, w, h)
        // feed in uneven chunks
        png.writeRows(px, 10, 0)
        png.writeRows(px, 40, 10 * w)
        png.writeRows(px, 3, 50 * w)
        png.finish()
        val (dw, dh, dp) = decodePng(bos.toByteArray())
        assertEquals(w, dw); assertEquals(h, dh)
        for (i in px.indices) assertEquals(px[i] and 0xFFFFFF, dp[i])
    }

    @Test fun streamingPngLargeIsValid() {
        val w = 1080
        val h = 3000
        val row = IntArray(w) { 0xFF000000.toInt() or (it * 7919 and 0xFFFFFF) }
        val bos = ByteArrayOutputStream()
        val png = PngStreamWriter(bos, w, h)
        repeat(h) { png.writeRows(row, 1) }
        png.finish()
        val (_, dh, dp) = decodePng(bos.toByteArray())
        assertEquals(h, dh)
        assertEquals(row[500] and 0xFFFFFF, dp[2999 * w + 500])
    }
}
