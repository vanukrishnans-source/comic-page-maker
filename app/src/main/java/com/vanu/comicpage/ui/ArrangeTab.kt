package com.vanu.comicpage.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vanu.comicpage.ComicViewModel
import com.vanu.comicpage.model.Panel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

@Composable
fun ArrangeTab(vm: ComicViewModel, modifier: Modifier = Modifier) {
    val panels = vm.panels
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var dragKey by remember { mutableLongStateOf(-1L) }
    var dragPointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var gridHeight by remember { mutableStateOf(0) }
    val panelsState = androidx.compose.runtime.rememberUpdatedState(panels)

    fun hit(off: Offset): LazyGridItemInfo? = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
        off.x >= it.offset.x && off.x < it.offset.x + it.size.width && off.y >= it.offset.y && off.y < it.offset.y + it.size.height
    }

    // auto-scroll while dragging near the top / bottom edge
    LaunchedEffect(dragKey) {
        while (dragKey >= 0) {
            val y = dragPointer.y
            val edge = 140f
            val dy = when {
                y < edge -> -((edge - y) / edge) * 38f - 6f
                y > gridHeight - edge -> ((y - (gridHeight - edge)) / edge) * 38f + 6f
                else -> 0f
            }
            if (dy != 0f) {
                gridState.scrollBy(dy)
                // the item under the pointer may have changed
                val dragged = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == dragKey }
                val target = hit(dragPointer)
                if (dragged != null && target != null && target.key != dragKey) vm.move(dragged.index, target.index)
            }
            delay(16)
        }
    }

    Column(modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { gridHeight = it.height }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val lp = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        val item = hit(lp.position) ?: return@awaitEachGesture
                        dragKey = item.key as Long
                        vm.selectedId = dragKey
                        grab = lp.position - Offset(item.offset.x.toFloat(), item.offset.y.toFloat())
                        dragPointer = lp.position
                        // From here on the finger belongs to the drag: consume at the Initial pass so the grid never scrolls.
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) { ch.consume(); break }
                            val d = ch.positionChange()
                            ch.consume()
                            dragPointer += d
                            val dragged = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == dragKey }
                            val target = hit(dragPointer)
                            if (dragged != null && target != null && target.key != dragKey) vm.move(dragged.index, target.index)
                        }
                        dragKey = -1L
                    }
                },
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(0.dp),
            ) {
                itemsIndexed(panels, key = { _, p -> p.id }) { i, p ->
                    val dragging = p.id == dragKey
                    Thumb(
                        vm = vm, p = p, index = i, selected = p.id == vm.selectedId, dragging = dragging,
                        modifier = Modifier
                            .then(if (dragging) Modifier else Modifier.animateItem())
                            .zIndex(if (dragging) 10f else 0f)
                            .graphicsLayer {
                                if (dragging) {
                                    val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == p.id }
                                    if (info != null) {
                                        translationX = dragPointer.x - grab.x - info.offset.x
                                        translationY = dragPointer.y - grab.y - info.offset.y
                                    }
                                    scaleX = 1.06f; scaleY = 1.06f
                                    shadowElevation = 24f
                                }
                            },
                    )
                }
            }
        }
        ActionBar(vm)
    }
}

@Composable
private fun Thumb(vm: ComicViewModel, p: Panel, index: Int, selected: Boolean, dragging: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .padding(3.dp)
            .aspectRatio(1f)
            .clip(shape)
            .background(CardElevated)
            .clickable { vm.selectedId = if (vm.selectedId == p.id) -1L else p.id }
            .then(if (selected) Modifier.border(3.dp, Amber, shape) else Modifier),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            vm.thumbs.version // redraw when new thumbnails arrive
            val bmp = vm.thumbs.get(p, size.width.toInt(), size.height.toInt())
            if (bmp != null) {
                drawIntoCanvas { c ->
                    val dst = android.graphics.RectF(0f, 0f, size.width, size.height)
                    val a = bmp.width.toFloat() / bmp.height
                    val src = if (a > 1f) {
                        val w = (bmp.height).toInt()
                        val x = (bmp.width - w) / 2
                        android.graphics.Rect(x, 0, x + w, bmp.height)
                    } else {
                        val h = bmp.width
                        val y = ((bmp.height - h) * 0.3f).toInt()
                        android.graphics.Rect(0, y, bmp.width, y + h)
                    }
                    c.nativeCanvas.drawBitmap(bmp, src, dst, thumbPaint)
                }
            }
        }
        Text(
            "${index + 1}",
            Modifier.align(Alignment.TopStart).padding(4.dp).background(Color(0xCC000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
            color = if (selected) Amber else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
    }
}

private val thumbPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

@Composable
private fun ActionBar(vm: ComicViewModel) {
    val hasSel = vm.selectedId >= 0
    Column(Modifier.fillMaxWidth().background(CardDark).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (hasSel) "Selected #${vm.panels.indexOfFirst { it.id == vm.selectedId } + 1}. Use the buttons or hold and drag a picture."
            else "Tap a picture to select it. Hold and drag to reorder.",
            color = TextSecondary, fontSize = 12.sp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Act("Earlier", Icons.Filled.ArrowBack, hasSel, Modifier.weight(1f)) { vm.moveSelected(-1) }
            Act("Later", Icons.Filled.ArrowForward, hasSel, Modifier.weight(1f)) { vm.moveSelected(1) }
            Act("Remove", Icons.Filled.Delete, hasSel, Modifier.weight(1f)) { vm.removeSelected() }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Act("Name", Icons.Filled.SortByAlpha, true, Modifier.weight(1f)) { vm.sortByName() }
            Act("Date", Icons.Filled.Schedule, true, Modifier.weight(1f)) { vm.sortByDate() }
            Act("Reverse", Icons.Filled.FlipToBack, true, Modifier.weight(1f)) { vm.reverse() }
            Act("Clear", Icons.Filled.DeleteSweep, true, Modifier.weight(1f)) { vm.clearAll() }
        }
    }
}

@Composable
private fun Act(label: String, icon: ImageVector, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = if (enabled) TextPrimary else TextSecondary.copy(alpha = 0.45f)
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(CardElevated)
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c, modifier = Modifier.padding(end = 4.dp))
        Text(label, color = c, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
