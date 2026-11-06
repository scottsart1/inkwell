package com.inkwell.notes

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.app.InkwellNavHost
import com.inkwell.notes.app.PendingNavigation
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.settings.AppLockGate
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.ui.theme.InkwellTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single activity. AppCompatActivity (a FragmentActivity) so BiometricPrompt works for the
 * app lock. Handles "Open with" / "Share to" for PDFs and images.
 */
class MainActivity : AppCompatActivity() {
    private val pendingNavigation = PendingNavigation()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)

        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle()
            LaunchedEffect(settings.privacy.blockScreenshots) {
                if (settings.privacy.blockScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            InkwellTheme(settings.theme) {
                // System bar icons follow the app's theme, which can differ from the system's (and a
                // dark mode switch reaches this activity as a configuration change, not a restart).
                val dark = Inkwell.colors.isDark
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                        navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                    )
                    onDispose {}
                }
                Box(Modifier.fillMaxSize().background(Inkwell.colors.ground)) {
                    AppLockGate {
                        InkwellNavHost(graph, pendingNavigation)
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
        if (intent.action == ACTION_OPEN_DOCUMENT) {
            // From our own notifications ("voices to name"). The activity is exported, so the id
            // is validated; opening still waits behind the app lock like any navigation.
            val docId = OpenDocumentIntent.docId(intent.getStringExtra(EXTRA_DOC_ID)) ?: return
            pendingNavigation.post { it.openDocumentById(docId) }
            return
        }
        val graph = AppGraph.get(this)
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayListExtra(Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            try {
                val mime = withContext(Dispatchers.IO) { contentResolver.getType(uris.first()) } ?: intent.type.orEmpty()
                if (mime.startsWith("image/")) {
                    val doc = graph.pdf.importImages(uris)
                    pendingNavigation.post { it.openDocument(doc) }
                } else {
                    importPdfs(graph, uris)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e("MainActivity", "Import failed", t)
                Toast.makeText(this@MainActivity, "Couldn't import: ${t.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Every shared PDF becomes a document (sharing several files sends them all at once). A single
     * one opens; several stay in the library with a summary, as imports started there do.
     */
    private suspend fun importPdfs(graph: AppGraph, uris: List<Uri>) {
        val imported = ArrayList<DocumentMeta>(uris.size)
        var failure: Throwable? = null
        for (uri in uris) {
            try {
                imported += graph.pdf.importPdf(uri)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e("MainActivity", "Import failed", t)
                failure = t
            }
        }
        when {
            imported.isEmpty() -> throw failure ?: IllegalStateException("Nothing was imported")
            uris.size == 1 -> imported[0].let { doc -> pendingNavigation.post { it.openDocument(doc) } }
            imported.size == uris.size ->
                Toast.makeText(this, resources.getQuantityString(R.plurals.lib_msg_imported, imported.size, imported.size), Toast.LENGTH_LONG).show()
            else -> Toast.makeText(this, getString(R.string.lib_msg_import_partial, imported.size, uris.size), Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableExtra(name: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, Uri::class.java) else getParcelableExtra(name)

    @Suppress("DEPRECATION")
    private fun Intent.parcelableArrayListExtra(name: String): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(name, Uri::class.java) else getParcelableArrayListExtra(name)) ?: emptyList()

    companion object {
        /** The navigation bar scrims of [enableEdgeToEdge]'s defaults (only used below API 29). */
        private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

        /** Opens the document whose id is in [EXTRA_DOC_ID]. */
        const val ACTION_OPEN_DOCUMENT = "com.inkwell.notes.OPEN_DOCUMENT"
        const val EXTRA_DOC_ID = "docId"
    }
}

/**
 * Validation of the extras of [MainActivity.ACTION_OPEN_DOCUMENT]. The activity is exported
 * (launcher, share targets), so any app can send the action: the id is used only to look a
 * document up, never as a path or a query fragment, and must look like one of our ids.
 */
internal object OpenDocumentIntent {
    const val MAX_ID_LENGTH = 128

    /** The document id to open, or null when the extra is missing or is not an id this app creates. */
    fun docId(extra: String?): String? = extra?.takeIf(::isValidDocId)

    fun isValidDocId(id: String): Boolean =
        id.length in 1..MAX_ID_LENGTH && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
}
