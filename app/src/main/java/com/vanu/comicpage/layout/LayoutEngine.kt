package com.vanu.comicpage.layout

import com.vanu.comicpage.model.PageSettings
import com.vanu.comicpage.model.PageStyle
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** One panel's rectangle in page pixels (page width = [PageLayout.width]). */
data class Cell(val index: Int, val x: Float, val y: Float, val w: Float, val h: Float) {
    val bottom: Float get() = y + h
}

/** A horizontal band of the page holding one row of cells; bands tile the page vertically with no gaps. */
data class Band(val top: Float, val bottom: Float, val cells: List<Cell>) {
    val height: Float get() = bottom - top
}

data class PageLayout(val width: Int, val height: Float, val bands: List<Band>, val scale: Float) {
    val cells: List<Cell> get() = bands.flatMap { it.cells }
}

/**
 * Pure layout maths (no Android classes) so it can be unit tested on the JVM.
 * All of gutter / border / radius in [PageSettings] are given at 1080 px width and scaled by pageWidth / 1080.
 */
object LayoutEngine {
    const val BASE_WIDTH = 1080f

    /** Rows are kept between these heights (as a fraction of the page width). */
    const val MIN_ROW_H = 0.28f
    const val MAX_ROW_H = 1.30f
    private const val TARGET_ROW_H = 0.5f

    fun layout(aspects: List<Float>, s: PageSettings): PageLayout {
        val w = s.pageWidth.toFloat()
        val k = w / BASE_WIDTH
        val g = s.gutter * k
        if (aspects.isEmpty()) return PageLayout(s.pageWidth, 0f, emptyList(), k)
        val rows: List<List<Cell>> = when (s.style) {
            PageStyle.WEBTOON -> webtoon(aspects, w, g)
            PageStyle.CLASSIC -> classic(aspects, w, g, s.maxPerRow, rtl = false)
            PageStyle.MANGA -> classic(aspects, w, g, s.maxPerRow, rtl = true)
            PageStyle.GRID -> grid(aspects.size, w, g, s.gridCols, s.gridAspect)
        }
        val margin = if (s.style == PageStyle.WEBTOON) 0f else g
        return assemble(rows, w, g, margin, k)
    }

    /** Stacks the rows and turns them into contiguous bands (the gutter belongs to the band above/below). */
    private fun assemble(rows: List<List<Cell>>, w: Float, g: Float, margin: Float, k: Float): PageLayout {
        // rows are laid out with y relative to the row top (y = 0)
        val bands = ArrayList<Band>()
        var y = margin
        for ((i, row) in rows.withIndex()) {
            val h = row.maxOf { it.h }
            val placed = row.map { it.copy(y = y + it.y) }
            val bandTop = if (i == 0) 0f else y - g / 2f
            val gapBelow = if (i == rows.lastIndex) margin else g / 2f
            bands += Band(bandTop, y + h + gapBelow, placed)
            y += h + g
        }
        val total = bands.last().bottom
        return PageLayout(w.toInt(), total, bands, k)
    }

    private fun webtoon(a: List<Float>, w: Float, g: Float): List<List<Cell>> =
        a.mapIndexed { i, asp -> listOf(Cell(i, 0f, 0f, w, w / max(asp, 0.02f))) }

    private fun grid(n: Int, w: Float, g: Float, cols: Int, cellAspect: Float): List<List<Cell>> {
        val c = cols.coerceIn(1, 8)
        val cw = (w - 2 * g - (c - 1) * g) / c
        val ch = cw / cellAspect.coerceIn(0.3f, 3f)
        return (0 until n).chunked(c).map { idx ->
            idx.mapIndexed { col, i -> Cell(i, g + col * (cw + g), 0f, cw, ch) }
        }
    }

    /** Result of the row packing, exposed for tests: the number of panels in each row, in order. */
    fun rowCounts(aspects: List<Float>, s: PageSettings): List<Int> {
        if (s.style != PageStyle.CLASSIC && s.style != PageStyle.MANGA) return emptyList()
        val g = s.gutter * s.pageWidth / BASE_WIDTH
        return pack(aspects, s.pageWidth.toFloat(), g, s.maxPerRow)
    }

    /**
     * Order-preserving partition of the panels into rows of 1..maxPerRow panels that minimises the
     * sum of squared log(rowHeight / target). That balances the rows by aspect ratio: portrait panels
     * are grouped 2-3 per row, wide panels run alone or in pairs, and no row gets absurdly tall or flat.
     */
    private fun pack(a: List<Float>, w: Float, g: Float, maxPerRow: Int): List<Int> {
        val n = a.size
        val mx = maxPerRow.coerceIn(1, 3)
        val inner = w - 2 * g
        val target = TARGET_ROW_H * w
        val cost = DoubleArray(n + 1) { Double.MAX_VALUE }
        val take = IntArray(n + 1)
        cost[0] = 0.0
        for (i in 1..n) {
            var sum = 0f
            for (k in 1..min(mx, i)) {
                sum += max(a[i - k], 0.02f)
                val rowW = inner - (k - 1) * g
                val rawH = rowW / sum
                val h = rawH.coerceIn(MIN_ROW_H * w, MAX_ROW_H * w)
                val l = ln((h / target).toDouble())
                // extra penalty when the clamp had to crop or letterbox the panels
                val clampPen = abs(ln((rawH / h).toDouble())) * 2.0
                val c = cost[i - k] + l * l + clampPen * clampPen
                if (c < cost[i]) {
                    cost[i] = c
                    take[i] = k
                }
            }
        }
        val counts = ArrayList<Int>()
        var i = n
        while (i > 0) {
            counts += take[i]
            i -= take[i]
        }
        counts.reverse()
        return counts
    }

    private fun classic(a: List<Float>, w: Float, g: Float, maxPerRow: Int, rtl: Boolean): List<List<Cell>> {
        val counts = pack(a, w, g, maxPerRow)
        val inner = w - 2 * g
        val rows = ArrayList<List<Cell>>()
        var idx = 0
        for (k in counts) {
            val ids = (idx until idx + k).toList()
            idx += k
            val sum = ids.sumOf { max(a[it], 0.02f).toDouble() }.toFloat()
            val rowW = inner - (k - 1) * g
            val h = (rowW / sum).coerceIn(MIN_ROW_H * w, MAX_ROW_H * w)
            var x = g
            val cells = ids.map { i ->
                val cw = rowW * max(a[i], 0.02f) / sum
                val c = Cell(i, x, 0f, cw, h)
                x += cw + g
                c
            }
            rows += if (rtl) cells.map { it.copy(x = w - it.x - it.w) } else cells
        }
        return rows
    }

    /** Splits the page at band boundaries so that every part is at most [maxHeight] px tall (a single taller band stays whole). */
    fun splitParts(layout: PageLayout, maxHeight: Float): List<ClosedFloatingPointRange<Float>> {
        if (layout.bands.isEmpty()) return emptyList()
        val parts = ArrayList<ClosedFloatingPointRange<Float>>()
        var start = 0f
        var end = 0f
        for (b in layout.bands) {
            if (b.bottom - start > maxHeight && end > start) {
                parts += start..end
                start = end
            }
            end = b.bottom
        }
        parts += start..end
        return parts
    }

    /** Natural ("page2" < "page10") case-insensitive comparison for name sorting. */
    fun naturalCompare(x: String, y: String): Int {
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            val cx = x[i]
            val cy = y[j]
            if (cx.isDigit() && cy.isDigit()) {
                var i2 = i
                while (i2 < x.length && x[i2].isDigit()) i2++
                var j2 = j
                while (j2 < y.length && y[j2].isDigit()) j2++
                val nx = x.substring(i, i2).trimStart('0')
                val ny = y.substring(j, j2).trimStart('0')
                if (nx.length != ny.length) return nx.length - ny.length
                val c = nx.compareTo(ny)
                if (c != 0) return c
                i = i2
                j = j2
            } else {
                val c = cx.lowercaseChar().compareTo(cy.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (x.length - i) - (y.length - j)
    }
}
