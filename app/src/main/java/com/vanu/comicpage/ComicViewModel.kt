package com.vanu.comicpage

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanu.comicpage.export.ExportFormat
import com.vanu.comicpage.export.ExportOptions
import com.vanu.comicpage.export.ExportResult
import com.vanu.comicpage.export.Exporter
import com.vanu.comicpage.layout.LayoutEngine
import com.vanu.comicpage.model.Bubble
import com.vanu.comicpage.model.BubbleKind
import com.vanu.comicpage.model.PageSettings
import com.vanu.comicpage.model.PageStyle
import com.vanu.comicpage.model.Panel
import com.vanu.comicpage.model.PanelFit
import com.vanu.comicpage.render.ImageLoader
import com.vanu.comicpage.render.PanelImageCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ExportUi {
    data object Idle : ExportUi
    data object Choosing : ExportUi
    data class Running(val progress: Float, val label: String) : ExportUi
    data class Done(val result: ExportResult, val format: ExportFormat) : ExportUi
    data class Failed(val message: String) : ExportUi
}

class ComicViewModel(app: Application) : AndroidViewModel(app) {
    private val cr: ContentResolver = app.contentResolver
    private val prefs = app.getSharedPreferences("comic_settings", 0)
    private var nextId = System.nanoTime()

    var panels by mutableStateOf<List<Panel>>(emptyList()); private set
    var settings by mutableStateOf(loadSettings()); private set
    var loading by mutableStateOf<String?>(null); private set
    var tab by mutableIntStateOf(0)
    var selectedId by mutableLongStateOf(-1L)
    var captionPanelId by mutableLongStateOf(-1L)
    var captionBubbleId by mutableLongStateOf(-1L)
    var tuneOpen by mutableStateOf(false)
    var export by mutableStateOf<ExportUi>(ExportUi.Idle)
    var exportOptions by mutableStateOf(ExportOptions())
    var toast by mutableStateOf<String?>(null)

    val thumbs = PanelImageCache(cr, 24 * 1024 * 1024)
    val preview = PanelImageCache(cr, 96 * 1024 * 1024)
    val exporter = Exporter(app)
    private var exportJob: Job? = null

    // ---- adding images ---------------------------------------------------------------------------

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = ArrayList<Panel>()
            var skipped = 0
            withContext(Dispatchers.IO) {
                for ((i, u) in uris.withIndex()) {
                    loading = "Loading ${i + 1} / ${uris.size}"
                    val info = ImageLoader.probe(cr, u)
                    if (info == null) { skipped++; continue }
                    val meta = queryMeta(u)
                    val date = if (info.exifDateMs > 0) info.exifDateMs else meta.second
                    added += Panel(nextId++, u, meta.first, date, info.width, info.height)
                }
            }
            loading = null
            panels = panels + added
            autoExport?.let { autoExport = null; startExport(it) }
            if (skipped > 0) toast = "$skipped file(s) could not be read as images"
        }
    }

    fun addFolder(tree: Uri) {
        viewModelScope.launch {
            loading = "Reading folder"
            val list = withContext(Dispatchers.IO) { listFolder(tree) }
            loading = null
            if (list.isEmpty()) toast = "No images found in that folder" else addUris(list)
        }
    }

    private fun listFolder(tree: Uri): List<Uri> {
        val out = ArrayList<Pair<String, Uri>>()
        try {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            cr.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(2) ?: ""
                    if (mime.startsWith("image/") && mime != "image/svg+xml") {
                        out += c.getString(1) to DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w("ComicPage", "folder listing failed", e)
        }
        return out.sortedWith { a, b -> LayoutEngine.naturalCompare(a.first, b.first) }.map { it.second }
    }

    /** (display name, best-effort date in ms) */
    private fun queryMeta(u: Uri): Pair<String, Long> {
        if (u.scheme == "file") {
            val f = File(u.path ?: "")
            return f.name to f.lastModified()
        }
        var name = u.lastPathSegment ?: "image"
        var date = 0L
        try {
            cr.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    fun long(col: String): Long {
                        val i = c.getColumnIndex(col)
                        return if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L
                    }
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    date = long("datetaken").takeIf { it > 0 }
                        ?: long("date_modified").takeIf { it > 0 }?.times(1000)
                        ?: long("last_modified").takeIf { it > 0 }
                        ?: long("date_added").takeIf { it > 0 }?.times(1000)
                        ?: 0L
                }
            }
        } catch (_: Throwable) {
        }
        return name to date
    }

    // ---- ordering ---------------------------------------------------------------------------------

    fun move(from: Int, to: Int) {
        if (from == to || from !in panels.indices || to !in panels.indices) return
        val l = panels.toMutableList()
        l.add(to, l.removeAt(from))
        panels = l
    }

    fun moveSelected(delta: Int) {
        val i = panels.indexOfFirst { it.id == selectedId }
        if (i >= 0) move(i, (i + delta).coerceIn(0, panels.lastIndex))
    }

    fun removeSelected() {
        val i = panels.indexOfFirst { it.id == selectedId }
        if (i < 0) return
        panels = panels.toMutableList().also { it.removeAt(i) }
        selectedId = panels.getOrNull(i.coerceAtMost(panels.lastIndex))?.id ?: -1L
        if (captionPanelId !in panels.map { it.id }) { captionPanelId = -1L; captionBubbleId = -1L }
    }

    fun sortByName() { panels = panels.sortedWith { a, b -> LayoutEngine.naturalCompare(a.name, b.name) } }
    fun sortByDate() { panels = panels.sortedWith(compareBy<Panel> { it.dateMs }.thenComparing { a, b -> LayoutEngine.naturalCompare(a.name, b.name) }) }
    fun reverse() { panels = panels.reversed() }
    fun clearAll() {
        panels = emptyList(); selectedId = -1L; captionPanelId = -1L; captionBubbleId = -1L
        thumbs.clear(); preview.clear()
    }

    // ---- settings -----------------------------------------------------------------------------------

    fun update(f: (PageSettings) -> PageSettings) {
        settings = f(settings)
        saveSettings(settings)
    }

    private fun loadSettings(): PageSettings {
        val d = PageSettings()
        return try {
            PageSettings(
                style = PageStyle.valueOf(prefs.getString("style", d.style.name)!!),
                gutter = prefs.getInt("gutter", d.gutter),
                borderWidth = prefs.getInt("borderWidth", d.borderWidth),
                borderColor = prefs.getInt("borderColor", d.borderColor),
                radius = prefs.getInt("radius", d.radius),
                background = prefs.getInt("background", d.background),
                fit = PanelFit.valueOf(prefs.getString("fit", d.fit.name)!!),
                pageWidth = prefs.getInt("pageWidth", d.pageWidth),
                maxPerRow = prefs.getInt("maxPerRow", d.maxPerRow),
                gridCols = prefs.getInt("gridCols", d.gridCols),
                gridAspect = prefs.getFloat("gridAspect", d.gridAspect),
            )
        } catch (_: Throwable) {
            d
        }
    }

    private fun saveSettings(s: PageSettings) {
        prefs.edit()
            .putString("style", s.style.name).putInt("gutter", s.gutter).putInt("borderWidth", s.borderWidth)
            .putInt("borderColor", s.borderColor).putInt("radius", s.radius).putInt("background", s.background)
            .putString("fit", s.fit.name).putInt("pageWidth", s.pageWidth).putInt("maxPerRow", s.maxPerRow)
            .putInt("gridCols", s.gridCols).putFloat("gridAspect", s.gridAspect).apply()
    }

    // ---- captions / speech bubbles ------------------------------------------------------------------

    private fun editPanel(id: Long, f: (Panel) -> Panel) {
        panels = panels.map { if (it.id == id) f(it) else it }
    }

    fun addBubble(kind: BubbleKind) {
        val pid = captionPanelId
        if (pid < 0) return
        val b = Bubble(nextId++, "Text", cx = 0.5f, cy = if (kind == BubbleKind.SPEECH) 0.22f else 0.12f,
            size = if (kind == BubbleKind.SPEECH) 0.5f else 0.8f, kind = kind)
        editPanel(pid) { it.copy(bubbles = it.bubbles + b) }
        captionBubbleId = b.id
    }

    fun updateBubble(panelId: Long, bubbleId: Long, f: (Bubble) -> Bubble) {
        editPanel(panelId) { p -> p.copy(bubbles = p.bubbles.map { if (it.id == bubbleId) f(it) else it }) }
    }

    fun deleteBubble() {
        val pid = captionPanelId
        val bid = captionBubbleId
        if (pid < 0 || bid < 0) return
        editPanel(pid) { p -> p.copy(bubbles = p.bubbles.filter { it.id != bid }) }
        captionBubbleId = -1L
    }

    val captionBubble: Bubble?
        get() = panels.firstOrNull { it.id == captionPanelId }?.bubbles?.firstOrNull { it.id == captionBubbleId }

    fun selectCaptionPanel(id: Long, bubbleId: Long = -1L) {
        captionPanelId = id
        captionBubbleId = bubbleId
    }

    // ---- export -------------------------------------------------------------------------------------

    fun startExport(opts: ExportOptions = exportOptions) {
        if (panels.isEmpty()) return
        exportOptions = opts
        export = ExportUi.Running(0f, "Starting")
        val snapshotPanels = panels
        val snapshotSettings = settings
        exportJob = viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val r = exporter.export(snapshotPanels, snapshotSettings, opts) { p, l -> export = ExportUi.Running(p, l) }
                export = ExportUi.Done(r, opts.format)
                val rt = Runtime.getRuntime()
                Log.i(
                    "ComicPage",
                    "EXPORT_DONE format=${opts.format} files=${r.names} size=${r.width}x${r.height} ms=${System.currentTimeMillis() - t0} " +
                        "javaHeapMB=${(rt.totalMemory() - rt.freeMemory()) shr 20} javaMaxMB=${rt.maxMemory() shr 20} " +
                        "nativeHeapMB=${android.os.Debug.getNativeHeapAllocatedSize() shr 20} tileBytesMB=${r.peakTileBytes shr 20} note=${r.note}",
                )
            } catch (e: CancellationException) {
                export = ExportUi.Idle
                toast = "Export cancelled"
                throw e
            } catch (e: Throwable) {
                Log.e("ComicPage", "EXPORT_FAILED", e)
                export = ExportUi.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelExport() { exportJob?.cancel() }

    // ---- debug-only test hook -------------------------------------------------------------------------

    /** Debug hook: export automatically once the demo images are loaded. */
    var autoExport: ExportOptions? = null

    fun loadDemoDir(path: String) {
        val files = File(path).listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.sortedWith { a, b -> LayoutEngine.naturalCompare(a.name, b.name) } ?: return
        addUris(files.map { Uri.fromFile(it) })
    }
}
