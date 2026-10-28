package com.vanu.comicpage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanu.comicpage.ComicViewModel
import com.vanu.comicpage.layout.LayoutEngine
import com.vanu.comicpage.layout.PageLayout
import com.vanu.comicpage.model.BubbleKind
import com.vanu.comicpage.model.PageSettings
import com.vanu.comicpage.model.PageStyle
import com.vanu.comicpage.model.PanelFit
import com.vanu.comicpage.render.PageRenderer
import kotlin.math.roundToInt

@Composable
fun PageTab(vm: ComicViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF16151C))) {
            PagePreview(vm)
        }
        if (vm.captionPanelId >= 0) CaptionBar(vm) else StyleBar(vm)
    }
}

@Composable
private fun PagePreview(vm: ComicViewModel) {
    val panels = vm.panels
    val settings = vm.settings
    val aspects = panels.map { it.aspect }
    val layout = remember(aspects, settings) { LayoutEngine.layout(aspects, settings) }
    val renderer = remember(layout, settings) { PageRenderer(settings, layout) }
    val panelsNow by rememberUpdatedState(panels)
    val selPanelIdx = panels.indexOfFirst { it.id == vm.captionPanelId }
    val selBubble = vm.captionBubbleId

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val scale = widthPx / layout.width
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(layout.bands, key = { i, _ -> i }) { bi, band ->
                val topPx = (band.top * scale).roundToInt()
                val botPx = (band.bottom * scale).roundToInt()
                val hPx = (botPx - topPx).coerceAtLeast(1)
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .layout { m, c ->
                            val p = m.measure(Constraints.fixed(c.maxWidth, hPx))
                            layout(c.maxWidth, hPx) { p.place(0, 0) }
                        }
                        .pointerInput(renderer, bi, scale) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val px = down.position.x / scale
                                val py = (down.position.y + topPx) / scale
                                val hit = renderer.hitBubble(panelsNow, bi, px, py)
                                if (hit != null) {
                                    val (pi, bid) = hit
                                    vm.selectCaptionPanel(panelsNow[pi].id, bid)
                                    val cell = layout.bands[bi].cells.first { it.index == pi }
                                    drag(down.id) { ch ->
                                        val d = ch.positionChange()
                                        ch.consume()
                                        vm.updateBubble(panelsNow[pi].id, bid) { b ->
                                            b.copy(
                                                cx = (b.cx + d.x / scale / cell.w).coerceIn(0.02f, 0.98f),
                                                cy = (b.cy + d.y / scale / cell.h).coerceIn(0.02f, 0.98f),
                                            )
                                        }
                                    }
                                } else {
                                    val up = waitForUpOrCancellation()
                                    if (up != null) {
                                        val idx = renderer.hitPanel(bi, px, py)
                                        if (idx != null) {
                                            val id = panelsNow[idx].id
                                            if (vm.captionPanelId == id) vm.selectCaptionPanel(-1L) else vm.selectCaptionPanel(id)
                                        } else vm.selectCaptionPanel(-1L)
                                    }
                                }
                            }
                        },
                ) {
                    vm.preview.version // redraw when decoded bitmaps arrive
                    drawIntoCanvas { c ->
                        val nc = c.nativeCanvas
                        nc.save()
                        nc.clipRect(0f, 0f, size.width, size.height)
                        nc.translate(0f, -topPx.toFloat())
                        nc.scale(scale, scale)
                        renderer.drawRegion(
                            nc, band.top, band.bottom, panelsNow,
                            { idx, cell ->
                                val p = panelsNow[idx]
                                vm.preview.get(p, (cell.w * scale).toInt(), (cell.h * scale).toInt())
                            },
                            selectedBubble = selBubble,
                            selectedPanel = selPanelIdx,
                        )
                        nc.restore()
                    }
                }
            }
        }
    }
}

// ---- bottom bars --------------------------------------------------------------------------------------

@Composable
private fun StyleBar(vm: ComicViewModel) {
    val s = vm.settings
    Column(Modifier.fillMaxWidth().background(CardDark).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (st in PageStyle.entries) {
                Chip(st.label, s.style == st, Modifier.weight(1f)) { vm.update { it.copy(style = st) } }
            }
            IconButton(onClick = { vm.tuneOpen = !vm.tuneOpen }, Modifier.size(40.dp)) {
                Icon(Icons.Filled.Tune, "Tune", tint = if (vm.tuneOpen) Amber else TextPrimary)
            }
        }
        if (vm.tuneOpen) TunePanel(vm) else
            Text("Tap a panel to add speech text. Tune changes spacing, borders and colours.", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
    }
}

@Composable
private fun TunePanel(vm: ComicViewModel) {
    val s = vm.settings
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(Modifier.fillMaxWidth()) {
            LabeledSlider("Gap ${s.gutter}", s.gutter.toFloat(), 0f..80f, 0, Modifier.weight(1f)) { v -> vm.update { it.copy(gutter = v.roundToInt()) } }
            LabeledSlider("Border ${s.borderWidth}", s.borderWidth.toFloat(), 0f..16f, 0, Modifier.weight(1f)) { v -> vm.update { it.copy(borderWidth = v.roundToInt()) } }
        }
        Row(Modifier.fillMaxWidth()) {
            LabeledSlider("Corners ${s.radius}", s.radius.toFloat(), 0f..80f, 0, Modifier.weight(1f)) { v -> vm.update { it.copy(radius = v.roundToInt()) } }
            when (s.style) {
                PageStyle.CLASSIC, PageStyle.MANGA ->
                    LabeledSlider("Max per row ${s.maxPerRow}", s.maxPerRow.toFloat(), 1f..3f, 1, Modifier.weight(1f)) { v -> vm.update { it.copy(maxPerRow = v.roundToInt()) } }
                PageStyle.GRID ->
                    LabeledSlider("Columns ${s.gridCols}", s.gridCols.toFloat(), 2f..5f, 2, Modifier.weight(1f)) { v -> vm.update { it.copy(gridCols = v.roundToInt()) } }
                PageStyle.WEBTOON -> Box(Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Page", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp).size(width = 44.dp, height = 20.dp))
            Swatches(PAGE_COLORS, s.background) { c -> vm.update { it.copy(background = c) } }
            Text("Border", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp, end = 4.dp))
            Swatches(BORDER_COLORS, s.borderColor) { c -> vm.update { it.copy(borderColor = c) } }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (f in PanelFit.entries) Chip(f.label, s.fit == f, Modifier.weight(1f)) { vm.update { it.copy(fit = f) } }
            for (w in listOf(1080, 1440, 2160)) Chip("${w}px", s.pageWidth == w, Modifier.weight(1.1f)) { vm.update { it.copy(pageWidth = w) } }
        }
        if (s.style == PageStyle.GRID) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Cell", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 10.dp).size(width = 40.dp, height = 28.dp))
                for ((label, a) in listOf("1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 0.75f, "16:9" to 16f / 9f)) {
                    Chip(label, kotlin.math.abs(s.gridAspect - a) < 0.01f, Modifier.weight(1f)) { vm.update { it.copy(gridAspect = a) } }
                }
            }
        }
    }
}

private val PAGE_COLORS = listOf(0xFFFFFFFF, 0xFFF6EBD3, 0xFFD8D8DE, 0xFF222222, 0xFF000000, 0xFF14213D, 0xFFFFC8DD).map { it.toInt() }
private val BORDER_COLORS = listOf(0xFF000000, 0xFFFFFFFF, 0xFF14213D, 0xFFB5171E, 0xFFFFC93C).map { it.toInt() }

@Composable
private fun Swatches(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (c in colors) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(Color(c))
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) Amber else Color(0x66FFFFFF), CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, minRange: Int, modifier: Modifier, onChange: (Float) -> Unit) {
    Column(modifier.padding(horizontal = 4.dp)) {
        Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        val steps = if (minRange > 0 || range.endInclusive - range.start <= 4f) (range.endInclusive - range.start).toInt() - 1 else 0
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps.coerceAtLeast(0),
            modifier = Modifier.height(26.dp),
            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber),
        )
    }
}

@Composable
fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(label, fontSize = 12.sp, maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
        modifier = modifier.height(34.dp),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Amber, selectedLabelColor = Color.Black),
    )
}

@Composable
private fun CaptionBar(vm: ComicViewModel) {
    val b = vm.captionBubble
    Column(Modifier.fillMaxWidth().background(CardDark).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallBtn("+ Speech", Modifier.weight(1f)) { vm.addBubble(BubbleKind.SPEECH) }
            SmallBtn("+ Caption box", Modifier.weight(1.3f)) { vm.addBubble(BubbleKind.BOX) }
            SmallBtn("Done", Modifier.weight(0.8f), primary = true) { vm.selectCaptionPanel(-1L) }
        }
        if (b == null) {
            Text("Add text to this panel, then drag it where you want it. Tap a text to edit it.", color = TextSecondary, fontSize = 12.sp)
        } else {
            val pid = vm.captionPanelId
            OutlinedTextField(
                value = b.text, onValueChange = { t -> vm.updateBubble(pid, b.id) { it.copy(text = t) } },
                modifier = Modifier.fillMaxWidth(), maxLines = 3, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
                label = { Text("Text") },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Size", color = TextSecondary, fontSize = 12.sp)
                IconButton({ vm.updateBubble(pid, b.id) { it.copy(size = (it.size - 0.06f).coerceAtLeast(0.15f)) } }, Modifier.size(36.dp)) { Icon(Icons.Filled.Remove, "Smaller") }
                IconButton({ vm.updateBubble(pid, b.id) { it.copy(size = (it.size + 0.06f).coerceAtMost(0.98f)) } }, Modifier.size(36.dp)) { Icon(Icons.Filled.Add, "Bigger") }
                Chip("Speech", b.kind == BubbleKind.SPEECH, Modifier.weight(1f)) { vm.updateBubble(pid, b.id) { it.copy(kind = BubbleKind.SPEECH) } }
                Chip("Box", b.kind == BubbleKind.BOX, Modifier.weight(0.8f)) { vm.updateBubble(pid, b.id) { it.copy(kind = BubbleKind.BOX) } }
                IconButton({ vm.deleteBubble() }, Modifier.size(36.dp)) { Icon(Icons.Filled.Delete, "Delete text", tint = Pink) }
            }
        }
    }
}

@Composable
private fun SmallBtn(label: String, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(if (primary) Amber else CardElevated).clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) Color.Black else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
