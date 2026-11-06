package com.inkwell.notes

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.app.InkwellNavHost
import com.inkwell.notes.app.PendingNavigation
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.settings.AppLockGate
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.ui.theme.InkwellTheme
import com.inkwell.notes.ui.theme.isDarkTheme
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
            // enableEdgeToEdge() picks the bar icon colour once, from the system theme; follow the
            // app's theme instead, including a system dark-mode switch (uiMode doesn't recreate us).
            val dark = isDarkTheme(settings.theme)
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, window.decorView).run {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            InkwellTheme(settings.theme) {
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
            val graph = AppGraph.get(this)
            // Looked up here, not in the nav host's scope: the lock can dispose the host (and
            // cancel its lookups) right after this arrives. post() queues while it is locked.
            lifecycleScope.launch {
                val doc = graph.library.getDocument(docId)?.takeIf { it.trashedAt == null } ?: return@launch
                pendingNavigation.post { it.openDocument(doc) }
            }
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
        val app = applicationContext
        val sharedType = intent.type
        // The read grants that came with the intent belong to this activity and end when it
        // finishes (Back on a share-started task): give the app its own grant for each file
        // first, so the import below can still read the later ones, and drop it afterwards.
        val held = uris.filter { holdReadGrant(app, it) }
        // In the app scope, so leaving the screen mid-import doesn't cancel it. Images become one
        // document and each PDF its own, so one unreadable file doesn't lose the others.
        graph.appScope.launch(Dispatchers.IO) {
            try {
                importShared(graph, app, uris, sharedType)
            } finally {
                held.forEach { releaseReadGrant(app, it) }
            }
        }
    }

    /** Imports the shared [uris], then opens the first document and says what happened. */
    private suspend fun importShared(graph: AppGraph, app: Context, uris: List<Uri>, sharedType: String?) {
        val (images, pdfs) = SharedImport.split(uris) { SharedImport.mimeOf(app.contentResolver.getType(it), sharedType) }
        val docs = ArrayList<DocumentMeta>()
        var imported = 0
        var error: String? = null
        if (images.isNotEmpty()) {
            try {
                docs += graph.pdf.importImages(images)
                imported += images.size
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e("MainActivity", "Import failed", t)
                if (error == null) error = t.message
            }
        }
        for (uri in pdfs) {
            try {
                docs += graph.pdf.importPdf(uri)
                imported++
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e("MainActivity", "Import failed", t)
                if (error == null) error = t.message
            }
        }
        val outcome = SharedImport.outcome(imported, uris.size, docs.size, error)
        withContext(Dispatchers.Main) {
            docs.firstOrNull()?.let { doc -> pendingNavigation.post { it.openDocument(doc) } }
            val text = when (outcome) {
                null -> null
                is SharedImport.Outcome.Imported -> app.resources.getQuantityString(R.plurals.lib_msg_imported, outcome.count, outcome.count)
                is SharedImport.Outcome.Partial -> app.getString(R.string.lib_msg_import_partial, outcome.imported, outcome.total)
                is SharedImport.Outcome.Failed -> "Couldn't import: ${outcome.error ?: "unknown error"}"
            }
            if (text != null) Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Gives the app itself read access to [uri], beyond this activity's life; false when not possible or not needed.
     * The app holds one grant per URI, so imports of the same file share it: it is counted, and only the
     * last [releaseReadGrant] revokes it.
     */
    private fun holdReadGrant(app: Context, uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        return readGrants.acquire(uri) {
            try {
                app.grantUriPermission(app.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (e: Exception) {
                // Nothing to pass on (e.g. readable without a grant): it is read under the activity's grant.
                false
            }
        }
    }

    private fun releaseReadGrant(app: Context, uri: Uri) {
        readGrants.release(uri) {
            try {
                app.revokeUriPermission(app.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                Log.w("MainActivity", "Read grant not released (${e.javaClass.simpleName})")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableExtra(name: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, Uri::class.java) else getParcelableExtra(name)

    @Suppress("DEPRECATION")
    private fun Intent.parcelableArrayListExtra(name: String): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(name, Uri::class.java) else getParcelableArrayListExtra(name)) ?: emptyList()

    companion object {
        /** Opens the document whose id is in [EXTRA_DOC_ID]. */
        const val ACTION_OPEN_DOCUMENT = "com.inkwell.notes.OPEN_DOCUMENT"
        const val EXTRA_DOC_ID = "docId"

        /** In-flight imports holding the app's own read grant, per URI (process-wide: a re-share can reach another activity). */
        private val readGrants = RefCounts<Uri>()
    }
}

/**
 * Reference counts for a resource held once per key: [acquire] runs its action for the first
 * holder only and [release] runs its action for the last one, both under the lock so a grant
 * and a revoke for the same key cannot interleave.
 */
internal class RefCounts<K> {
    private val counts = HashMap<K, Int>()

    /** Takes a reference to [key]; [first] (run for the first one only) says whether the resource could be taken. False leaves nothing held. */
    @Synchronized
    fun acquire(key: K, first: () -> Boolean): Boolean {
        val n = counts[key] ?: 0
        if (n == 0 && !first()) return false
        counts[key] = n + 1
        return true
    }

    /** Drops a reference to [key]; [last] runs when it was the last one. Keys not held are ignored. */
    @Synchronized
    fun release(key: K, last: () -> Unit) {
        val n = counts[key] ?: return
        if (n <= 1) {
            counts.remove(key)
            last()
        } else {
            counts[key] = n - 1
        }
    }

    @Synchronized
    fun count(key: K): Int = counts[key] ?: 0
}

/** How files shared with or opened in the app are imported, and what the user is told after. */
internal object SharedImport {
    sealed interface Outcome {
        /** All [count] files imported, as several documents (the toast counts files, like [Partial]). */
        data class Imported(val count: Int) : Outcome
        data class Partial(val imported: Int, val total: Int) : Outcome
        data class Failed(val error: String?) : Outcome
    }

    /** The type to go by for one item: its own, else the one the sender gave the whole share. */
    fun mimeOf(own: String?, sharedType: String?): String = (own ?: sharedType).orEmpty()

    /** Splits [items] into (images, the rest). The rest go to the PDF importer, which reports what it can't read. */
    fun <T> split(items: List<T>, mime: (T) -> String): Pair<List<T>, List<T>> =
        items.partition { mime(it).startsWith("image/", ignoreCase = true) }

    /**
     * What to tell the user after [imported] of [total] files became [documents] documents
     * ([error]: the first failure's message). Null when the one document that opens says it all.
     */
    fun outcome(imported: Int, total: Int, documents: Int, error: String?): Outcome? = when {
        imported == 0 -> Outcome.Failed(error)
        imported < total -> Outcome.Partial(imported, total)
        documents == 1 -> null
        else -> Outcome.Imported(imported)
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
