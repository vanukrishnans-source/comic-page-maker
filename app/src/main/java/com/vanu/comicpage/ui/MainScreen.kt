package com.vanu.comicpage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanu.comicpage.ComicViewModel
import com.vanu.comicpage.ExportUi
import com.vanu.comicpage.export.ExportFormat
import com.vanu.comicpage.layout.LayoutEngine

@Composable
fun MainScreen(
    vm: ComicViewModel,
    onAddPhotos: () -> Unit,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onShare: (List<android.net.Uri>, String) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(SurfaceDark)) {
        TopBar(vm, onAddPhotos, onAddFiles, onAddFolder)
        if (vm.panels.isEmpty()) {
            EmptyState(vm.loading, onAddPhotos, onAddFiles, onAddFolder)
        } else {
            Tabs(vm)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (vm.tab == 0) ArrangeTab(vm) else PageTab(vm)
                vm.loading?.let {
                    Box(Modifier.align(Alignment.TopCenter).padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xEE000000)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(it, color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }
    }
    ExportDialogs(vm, onShare)
}

@Composable
private fun TopBar(vm: ComicViewModel, onAddPhotos: () -> Unit, onAddFiles: () -> Unit, onAddFolder: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Comic Page Maker", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Amber)
            val n = vm.panels.size
            if (n > 0) {
                val layout = remember(vm.panels.map { it.aspect }, vm.settings) { LayoutEngine.layout(vm.panels.map { it.aspect }, vm.settings) }
                Text("$n images · ${layout.width} × ${layout.height.toInt()} px", color = TextSecondary, fontSize = 12.sp)
            } else Text("Turn your pictures into a comic page", color = TextSecondary, fontSize = 12.sp)
        }
        Box {
            PillButton("Add", Icons.Filled.AddPhotoAlternate, false) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Photos (photo picker)") }, leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) }, onClick = { menu = false; onAddPhotos() })
                DropdownMenuItem(text = { Text("Files (many at once)") }, leadingIcon = { Icon(Icons.Filled.FileUpload, null) }, onClick = { menu = false; onAddFiles() })
                DropdownMenuItem(text = { Text("A whole folder") }, leadingIcon = { Icon(Icons.Filled.Folder, null) }, onClick = { menu = false; onAddFolder() })
            }
        }
        Box(Modifier.padding(start = 8.dp)) {
            PillButton("Export", Icons.Filled.IosShare, true, enabled = vm.panels.isNotEmpty()) { vm.export = ExportUi.Choosing }
        }
    }
}

@Composable
private fun PillButton(label: String, icon: ImageVector, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (!enabled) CardElevated else if (primary) Amber else CardElevated
    val fg = if (!enabled) TextSecondary else if (primary) Color.Black else TextPrimary
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(bg).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.padding(end = 0.dp))
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Tabs(vm: ComicViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((i, label) in listOf("1  Arrange", "2  Page preview").withIndex()) {
            val sel = vm.tab == i
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) Amber else CardElevated)
                    .clickable { vm.tab = i }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (sel) Color.Black else TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun EmptyState(loading: String?, onPhotos: () -> Unit, onFiles: () -> Unit, onFolder: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Add your pictures", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Pick the images in any order, drag them into the right order, choose a comic style and export one long page.\nEverything stays on your phone.",
            color = TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 14.dp), fontSize = 14.sp,
        )
        if (loading != null) {
            Text(loading, color = Amber)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth(0.8f)) {
                BigBtn("Choose photos", Icons.Filled.PhotoLibrary, true, onPhotos)
                BigBtn("Choose files", Icons.Filled.FileUpload, false, onFiles)
                BigBtn("Choose a folder", Icons.Filled.Folder, false, onFolder)
            }
        }
    }
}

@Composable
private fun BigBtn(label: String, icon: ImageVector, primary: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (primary) Amber else CardElevated).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (primary) Color.Black else TextPrimary)
        Text("  $label", color = if (primary) Color.Black else TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun ExportDialogs(vm: ComicViewModel, onShare: (List<android.net.Uri>, String) -> Unit) {
    when (val e = vm.export) {
        ExportUi.Idle -> {}
        ExportUi.Choosing -> {
            val o = vm.exportOptions
            val layout = remember(vm.panels.map { it.aspect }, vm.settings, o) {
                val st = if (o.format == ExportFormat.PDF) vm.settings.copy(pageWidth = minOf(vm.settings.pageWidth, com.vanu.comicpage.export.Exporter.PDF_MAX_WIDTH)) else vm.settings
                LayoutEngine.layout(vm.panels.map { it.aspect }, st)
            }
            val (parts, note) = remember(layout, o) { vm.exporter.plan(layout, o) }
            AlertDialog(
                onDismissRequest = { vm.export = ExportUi.Idle },
                title = { Text("Export page") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            for (f in ExportFormat.entries) Chip(f.label, o.format == f, Modifier.weight(1f)) { vm.exportOptions = o.copy(format = f) }
                        }
                        if (o.format != ExportFormat.PDF) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                Chip("Auto split", !o.singleImage, Modifier.weight(1f)) { vm.exportOptions = o.copy(singleImage = false) }
                                Chip("One image", o.singleImage, Modifier.weight(1f)) { vm.exportOptions = o.copy(singleImage = true) }
                            }
                        }
                        Text(
                            "Page ${layout.width} × ${layout.height.toInt()} px.\n" + when {
                                o.format == ExportFormat.PDF -> "PDF with ${parts.size} page(s), saved in Downloads/ComicPage."
                                parts.size == 1 -> "One ${o.format.label} file, saved in Pictures/ComicPage."
                                else -> "${parts.size} numbered ${o.format.label} parts (cut between panel rows), saved in Pictures/ComicPage."
                            },
                            fontSize = 13.sp, color = TextSecondary,
                        )
                        note?.let { Text(it, fontSize = 12.sp, color = Amber) }
                        if (o.format == ExportFormat.PNG && o.singleImage && layout.height > 20000f)
                            Text("A very tall single PNG may not open in some gallery apps.", fontSize = 12.sp, color = Amber)
                    }
                },
                confirmButton = { TextButton({ vm.startExport() }) { Text("Export", color = Amber, fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton({ vm.export = ExportUi.Idle }) { Text("Cancel") } },
            )
        }
        is ExportUi.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Saving…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { e.progress }, Modifier.fillMaxWidth(), color = Amber)
                    Text("${e.label} · ${(e.progress * 100).toInt()}%", fontSize = 13.sp, color = TextSecondary)
                }
            },
            confirmButton = { TextButton({ vm.cancelExport() }) { Text("Cancel") } },
        )
        is ExportUi.Done -> AlertDialog(
            onDismissRequest = { vm.export = ExportUi.Idle },
            title = { Text("Saved") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        (if (e.format == ExportFormat.PDF) "Downloads/ComicPage" else "Pictures/ComicPage") + "\n" + e.result.names.joinToString("\n"),
                        fontSize = 13.sp,
                    )
                    Text("${e.result.width} × ${e.result.height} px", fontSize = 12.sp, color = TextSecondary)
                    e.result.note?.let { Text(it, fontSize = 12.sp, color = Amber) }
                }
            },
            confirmButton = { TextButton({ onShare(e.result.uris, e.format.mime) }) { Text("Share", color = Amber, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton({ vm.export = ExportUi.Idle }) { Text("Close") } },
        )
        is ExportUi.Failed -> AlertDialog(
            onDismissRequest = { vm.export = ExportUi.Idle },
            title = { Text("Export failed") },
            text = { Text(e.message, fontSize = 13.sp) },
            confirmButton = { TextButton({ vm.export = ExportUi.Idle }) { Text("OK") } },
        )
    }
}
