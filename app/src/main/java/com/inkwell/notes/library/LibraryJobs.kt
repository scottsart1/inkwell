package com.inkwell.notes.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.export.ExportFormat
import com.inkwell.notes.export.ExportOptions
import com.inkwell.notes.pdf.importScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Imports and bulk exports started from the library. They run in [AppGraph.appScope] on the
 * IO dispatcher so they finish even when the user leaves the screen; one runs at a time.
 * Progress and results are published for whichever library screen is visible.
 */
object LibraryJobs {
    enum class Kind { IMPORT, EXPORT }

    /** [total] = 0 means indeterminate. */
    data class Progress(val kind: Kind, val done: Int, val total: Int)

    private const val TAG = "LibraryJobs"

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 16)

    /** Results for the visible screen; dropped when no library screen is showing. */
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    @Volatile private var current: Job? = null

    /** A cancelled job stays busy until it has actually stopped, so two never run at once. */
    val isBusy: Boolean get() = current?.isCompleted == false

    fun cancel() {
        current?.cancel()
    }

    fun importPdfs(graph: AppGraph, uris: List<Uri>, folderId: String?) {
        if (uris.isEmpty()) return
        launchJob(graph, Kind.IMPORT, uris.size) { step ->
            var ok = 0
            var last: DocumentMeta? = null
            var failedName: String? = null
            uris.forEachIndexed { i, uri ->
                coroutineContext.ensureActive()
                step(i)
                try {
                    last = graph.pdf.importPdf(uri, folderId)
                    ok++
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Log.w(TAG, "PDF import failed: ${t.javaClass.simpleName}")
                    failedName = displayName(graph.context, uri)
                }
            }
            step(uris.size)
            reportImport(ok, uris.size, last, failedName)
        }
    }

    fun importImages(graph: AppGraph, uris: List<Uri>, folderId: String?) {
        if (uris.isEmpty()) return
        launchJob(graph, Kind.IMPORT, 0) {
            val doc = try {
                graph.pdf.importImages(uris, folderId)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Image import failed: ${t.javaClass.simpleName}")
                null
            }
            if (doc != null) _events.tryEmit(LibraryEvent.Open(doc))
            else message(UiText.Res(R.string.lib_err_import))
        }
    }

    /** Imports the PDF produced by the document scanner, then removes the scanner's copy. */
    fun importScan(graph: AppGraph, pdfUri: Uri, folderId: String?) {
        launchJob(graph, Kind.IMPORT, 0) {
            val doc = try {
                graph.pdf.importScanResult(pdfUri, emptyList(), folderId)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Scan import failed: ${t.javaClass.simpleName}")
                null
            }
            if (doc != null) _events.tryEmit(LibraryEvent.Open(doc))
            else message(UiText.Res(R.string.lib_err_import))
        }
    }

    fun importPackage(graph: AppGraph, uri: Uri, folderId: String?) {
        launchJob(graph, Kind.IMPORT, 0) {
            val name = displayName(graph.context, uri)
            if (name != null && !PackageNames.looksLikePackage(name)) {
                message(UiText.Res(R.string.lib_err_not_package))
                return@launchJob
            }
            val doc = try {
                graph.export.importPackage(uri, folderId)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Package import failed: ${t.javaClass.simpleName}")
                null
            }
            if (doc != null) _events.tryEmit(LibraryEvent.Open(doc))
            else message(if (name != null) UiText.Res(R.string.lib_err_import_named, listOf(name)) else UiText.Res(R.string.lib_err_import))
        }
    }

    fun exportMany(graph: AppGraph, docIds: List<String>, format: ExportFormat, treeUri: Uri) {
        if (docIds.isEmpty()) return
        launchJob(graph, Kind.EXPORT, docIds.size) { step ->
            val options = ExportOptions(format = format)
            var ok = 0
            docIds.forEachIndexed { i, id ->
                coroutineContext.ensureActive()
                step(i)
                ok += try {
                    graph.export.exportMany(listOf(id), options, treeUri)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Log.w(TAG, "Export failed: ${t.javaClass.simpleName}")
                    0
                }
            }
            step(docIds.size)
            message(
                if (ok == 0) UiText.Res(R.string.lib_err_export)
                else UiText.Res(R.string.lib_msg_exported, listOf(ok, docIds.size)),
            )
        }
    }

    private fun reportImport(ok: Int, total: Int, last: DocumentMeta?, failedName: String?) {
        when {
            ok == 1 && total == 1 && last != null -> _events.tryEmit(LibraryEvent.Open(last))
            ok == total -> message(UiText.Plural(R.plurals.lib_msg_imported, ok))
            ok == 0 && total == 1 && failedName != null -> message(UiText.Res(R.string.lib_err_import_named, listOf(failedName)))
            ok == 0 -> message(UiText.Res(R.string.lib_err_import))
            else -> message(UiText.Res(R.string.lib_msg_import_partial, listOf(ok, total)))
        }
    }

    private fun message(text: UiText) {
        _events.tryEmit(LibraryEvent.Message(LibraryMessage(text, long = true)))
    }

    private fun launchJob(graph: AppGraph, kind: Kind, total: Int, block: suspend (step: (Int) -> Unit) -> Unit) {
        if (isBusy) {
            _events.tryEmit(LibraryEvent.Message(LibraryMessage(UiText.Res(R.string.lib_msg_busy))))
            return
        }
        _progress.value = Progress(kind, 0, total)
        // Started only after it is [current], so it can tell whether the progress is still its own.
        val job = graph.appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try {
                block { done -> if (current === self) _progress.value = Progress(kind, done.coerceIn(0, total), total) }
            } catch (c: CancellationException) {
                message(UiText.Res(R.string.lib_msg_cancelled))
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Library job failed: ${t.javaClass.simpleName}")
                message(UiText.Res(R.string.lib_err_generic))
            } finally {
                if (current === self) _progress.value = null
            }
        }
        current = job
        job.start()
    }

    /** The user-visible file name of a content Uri, if the provider reports one. Runs on IO. */
    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (t: Throwable) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')
}

/** File-name checks for Inkwell packages. */
object PackageNames {
    private val EXTENSIONS = listOf(".inkwell", ".zip")

    fun looksLikePackage(name: String): Boolean {
        val n = name.trim().lowercase()
        return EXTENSIONS.any { n.endsWith(it) }
    }
}
