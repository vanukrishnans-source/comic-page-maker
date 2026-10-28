package com.vanu.comicpage.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.vanu.comicpage.layout.Cell
import com.vanu.comicpage.layout.PageLayout
import com.vanu.comicpage.model.Bubble
import com.vanu.comicpage.model.BubbleKind
import com.vanu.comicpage.model.PageSettings
import com.vanu.comicpage.model.Panel
import com.vanu.comicpage.model.PanelFit
import kotlin.math.max
import kotlin.math.min

/** Everything that is drawn is drawn here, so the in-app preview and the exported file always match. */
class PageRenderer(private val settings: PageSettings, private val layout: PageLayout) {

    private val k = layout.scale
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bubbleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bubbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeJoin = Paint.Join.ROUND }
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val rect = RectF()
    private val path = Path()
    private val src = Rect()

    /**
     * Draws page rows that intersect [y0, y1) in page px. The canvas must already be translated/scaled
     * so that page coordinates map to the surface (e.g. translate(0, -y0) for an export tile).
     */
    fun drawRegion(
        canvas: Canvas,
        y0: Float,
        y1: Float,
        panels: List<Panel>,
        bitmapFor: (Int, Cell) -> Bitmap?,
        selectedBubble: Long = -1L,
        selectedPanel: Int = -1,
    ) {
        fillPaint.color = settings.background
        canvas.drawRect(-1f, y0 - 1f, layout.width + 1f, y1 + 1f, fillPaint)
        for (band in layout.bands) {
            if (band.bottom <= y0 || band.top >= y1) continue
            for (c in band.cells) drawPanel(canvas, c, bitmapFor(c.index, c))
        }
        for (band in layout.bands) {
            if (band.bottom <= y0 || band.top >= y1) continue
            for (c in band.cells) {
                val p = panels.getOrNull(c.index) ?: continue
                if (c.index == selectedPanel) {
                    borderPaint.color = 0xFFFFC93C.toInt()
                    borderPaint.strokeWidth = 5f * k.coerceAtLeast(1f)
                    rect.set(c.x, c.y, c.x + c.w, c.y + c.h)
                    canvas.drawRect(rect, borderPaint)
                }
                for (b in p.bubbles) drawBubble(canvas, c, b, b.id == selectedBubble)
            }
        }
    }

    private fun drawPanel(canvas: Canvas, c: Cell, bmp: Bitmap?) {
        val bw = settings.borderWidth * k
        val r = settings.radius * k
        val dst = RectF(c.x, c.y, c.x + c.w, c.y + c.h)
        if (bmp == null) {
            fillPaint.color = 0xFFD9D9DE.toInt()
            canvas.drawRoundRect(dst, r, r, fillPaint)
            return
        }
        val bAsp = bmp.width.toFloat() / bmp.height
        if (settings.fit == PanelFit.FIT) {
            val cAsp = c.w / c.h
            if (bAsp > cAsp) {
                val h = c.w / bAsp
                dst.set(c.x, c.y + (c.h - h) / 2f, c.x + c.w, c.y + (c.h + h) / 2f)
            } else {
                val w = c.h * bAsp
                dst.set(c.x + (c.w - w) / 2f, c.y, c.x + (c.w + w) / 2f, c.y + c.h)
            }
            src.set(0, 0, bmp.width, bmp.height)
        } else {
            val cAsp = c.w / c.h
            if (bAsp > cAsp) {
                val sw = (bmp.height * cAsp).toInt().coerceIn(1, bmp.width)
                val sx = ((bmp.width - sw) * 0.5f).toInt()
                src.set(sx, 0, sx + sw, bmp.height)
            } else {
                val sh = (bmp.width / cAsp).toInt().coerceIn(1, bmp.height)
                // slightly top-biased so faces survive the crop
                val sy = ((bmp.height - sh) * 0.35f).toInt()
                src.set(0, sy, bmp.width, sy + sh)
            }
        }
        val rr = if (r > 0f) min(r, min(dst.width(), dst.height()) / 2f) else 0f
        canvas.save()
        if (rr > 0f) {
            path.reset()
            path.addRoundRect(dst, rr, rr, Path.Direction.CW)
            canvas.clipPath(path)
        } else {
            canvas.clipRect(dst)
        }
        canvas.drawBitmap(bmp, src, dst, bitmapPaint)
        canvas.restore()
        if (bw > 0f) {
            borderPaint.color = settings.borderColor
            borderPaint.strokeWidth = bw
            val inset = bw / 2f
            val br = RectF(dst.left + inset, dst.top + inset, dst.right - inset, dst.bottom - inset)
            val brr = max(rr - inset, 0f)
            canvas.drawRoundRect(br, brr, brr, borderPaint)
        }
    }

    // ---- speech bubbles / caption boxes -------------------------------------------------------------

    class BubbleGeom(val rect: RectF, val textLayout: StaticLayout, val pad: Float)

    fun bubbleGeom(c: Cell, b: Bubble): BubbleGeom {
        val bw = (c.w * b.size).coerceAtLeast(40f)
        val ts = (bw * 0.115f).coerceAtLeast(8f)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = bold
            textSize = ts
        }
        val pad = ts * (if (b.kind == BubbleKind.SPEECH) 0.9f else 0.55f)
        val textW = (bw - 2 * pad).toInt().coerceAtLeast(10)
        val text = b.text.ifBlank { " " }
        val sl = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, textW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .build()
        val bh = sl.height + 2 * pad
        val cx = c.x + b.cx * c.w
        val cy = c.y + b.cy * c.h
        val r = RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
        // keep inside the panel
        val dx = when { r.left < c.x -> c.x - r.left; r.right > c.x + c.w -> c.x + c.w - r.right; else -> 0f }
        val dy = when { r.top < c.y -> c.y - r.top; r.bottom > c.y + c.h -> c.y + c.h - r.bottom; else -> 0f }
        r.offset(dx, dy)
        return BubbleGeom(r, sl, pad)
    }

    private fun drawBubble(canvas: Canvas, c: Cell, b: Bubble, selected: Boolean) {
        val g = bubbleGeom(c, b)
        val r = g.rect
        val stroke = max(2.5f * k, 2f)
        bubbleStroke.strokeWidth = stroke
        canvas.save()
        canvas.clipRect(c.x, c.y, c.x + c.w, c.y + c.h)
        if (b.kind == BubbleKind.SPEECH) {
            val rad = min(r.height() / 2f, r.width() / 2f)
            // tail: a small triangle at the bottom-left, merged with the body
            val tailW = r.width() * 0.14f
            val tailH = r.height() * 0.38f
            val tx = r.left + r.width() * 0.28f
            path.reset()
            path.moveTo(tx, r.bottom - stroke)
            path.lineTo(tx - tailW * 0.2f, r.bottom + tailH)
            path.lineTo(tx + tailW, r.bottom - stroke)
            path.close()
            canvas.drawPath(path, bubbleFill)
            canvas.drawPath(path, bubbleStroke)
            canvas.drawRoundRect(r, rad, rad, bubbleFill)
            canvas.drawRoundRect(r, rad, rad, bubbleStroke)
            // re-fill the tail root so the outline does not cross the join
            path.reset()
            path.moveTo(tx + stroke, r.bottom - stroke * 2f)
            path.lineTo(tx - tailW * 0.2f + stroke, r.bottom + stroke)
            path.lineTo(tx + tailW - stroke, r.bottom - stroke * 2f)
            path.close()
            canvas.drawPath(path, bubbleFill)
        } else {
            val prev = bubbleFill.color
            bubbleFill.color = 0xFFFFE680.toInt()
            canvas.drawRect(r, bubbleFill)
            canvas.drawRect(r, bubbleStroke)
            bubbleFill.color = prev
        }
        canvas.save()
        canvas.translate(r.left + g.pad, r.top + g.pad)
        g.textLayout.draw(canvas)
        canvas.restore()
        if (selected) {
            borderPaint.color = 0xFFFF5C7A.toInt()
            borderPaint.strokeWidth = max(3f * k, 2.5f)
            val e = RectF(r).apply { inset(-stroke * 2, -stroke * 2) }
            canvas.drawRect(e, borderPaint)
        }
        canvas.restore()
    }

    /** Hit test for a bubble at page coordinates; returns (panelIndex, bubbleId) or null. */
    fun hitBubble(panels: List<Panel>, band: Int, px: Float, py: Float): Pair<Int, Long>? {
        val bd = layout.bands.getOrNull(band) ?: return null
        for (c in bd.cells.asReversed()) {
            val p = panels.getOrNull(c.index) ?: continue
            for (b in p.bubbles.asReversed()) {
                val r = bubbleGeom(c, b).rect
                val slop = 18f * k
                if (px >= r.left - slop && px <= r.right + slop && py >= r.top - slop && py <= r.bottom + slop * 3) return c.index to b.id
            }
        }
        return null
    }

    fun hitPanel(band: Int, px: Float, py: Float): Int? {
        val bd = layout.bands.getOrNull(band) ?: return null
        return bd.cells.firstOrNull { px >= it.x && px <= it.x + it.w && py >= it.y && py <= it.y + it.h }?.index
    }
}
