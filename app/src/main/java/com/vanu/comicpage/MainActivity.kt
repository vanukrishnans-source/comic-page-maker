package com.vanu.comicpage

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import com.vanu.comicpage.export.ExportFormat
import com.vanu.comicpage.export.ExportOptions
import com.vanu.comicpage.model.PageStyle
import com.vanu.comicpage.ui.ComicTheme
import com.vanu.comicpage.ui.MainScreen

class MainActivity : ComponentActivity() {

    private val vm: ComicViewModel by viewModels()

    private val maxPick = runCatching { MediaStore.getPickImagesMaxLimit().coerceIn(2, 500) }.getOrDefault(100)

    private val pickPhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxPick)) { uris ->
        vm.addUris(uris)
    }
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (u in uris) try {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
        vm.addUris(uris)
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) vm.addFolder(tree)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ComicTheme {
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                    MainScreen(
                        vm = vm,
                        onAddPhotos = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onAddFiles = { pickFiles.launch(arrayOf("image/*")) },
                        onAddFolder = { pickFolder.launch(null) },
                        onShare = { uris, mime -> share(uris, mime) },
                    )
                    vm.toast?.let { msg ->
                        LaunchedEffect(msg) {
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            vm.toast = null
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Debug-build-only test hook (used for the emulator trial): load images from a folder and optionally export.
        if (BuildConfig.DEMO_HOOK) {
            intent.getStringExtra("demo_dir")?.let { dir ->
                vm.loadDemoDir(dir)
                intent.getStringExtra("demo_style")?.let { s -> vm.update { it.copy(style = PageStyle.valueOf(s)) } }
                intent.getIntExtra("demo_width", 0).takeIf { it > 0 }?.let { w -> vm.update { it.copy(pageWidth = w) } }
                if (intent.getStringExtra("demo_tab") == "page") vm.tab = 1
                intent.getStringExtra("demo_export")?.let { f ->
                    val fmt = ExportFormat.valueOf(f.uppercase())
                    val single = intent.getBooleanExtra("demo_single", false)
                    vm.autoExport = ExportOptions(fmt, single)
                }
                return
            }
        }
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.addUris(uris)
    }

    private fun share(uris: List<Uri>, mime: String) {
        if (uris.isEmpty()) return
        val i = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newRawUri("comic", uris[0]).also { cd -> uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Share comic page"))
    }
}
