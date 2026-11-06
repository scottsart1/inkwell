package com.inkwell.notes.export

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.export.backup.PackageImporter
import com.inkwell.notes.export.backup.PackageWriter
import com.inkwell.notes.export.format.AudioJoiner
import com.inkwell.notes.export.format.DocSource
import com.inkwell.notes.export.format.ExportErrors
import com.inkwell.notes.export.format.ExportException
import com.inkwell.notes.export.format.ExportNaming
import com.inkwell.notes.export.format.ImageExporter
import com.inkwell.notes.export.format.PageRaster
import com.inkwell.notes.export.format.PdfExporter
import com.inkwell.notes.export.format.SafStreams
import com.inkwell.notes.export.format.TextExporter
import com.inkwell.notes.export.format.TypedBlocks
import com.inkwell.notes.export.format.TypedLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Exports documents to PDF, PNG/JPEG, plain text, Markdown, Word and the native .inkwell
 * package; imports packages; joins recordings; renders pages for AI and thumbnails.
 * All work runs off the main thread; failures surface as [ExportException] with a message
 * ready for the user.
 */
class ExportServiceImpl(private val graph: AppGraph) : ExportService {
    private val source by lazy { DocSource(graph) }
    private val raster by lazy { PageRaster(source) }
    private val pdf by lazy { PdfExporter(graph, source) }
    private val images by lazy { ImageExporter(graph.context, source, raster) }
    private val text by lazy { TextExporter(graph.context, source, raster) }
    private val packages by lazy { PackageWriter(graph) }
    private val importer by lazy { PackageImporter(graph) }

    /** Progress callback: pages (or steps) done of total. */
    fun interface Progress { fun update(done: Int, total: Int) }

    // ------------------------------------------------------------------ Export

    override suspend fun export(docId: String, options: ExportOptions): File = exportFile(docId, options) { _, _ -> }

    /** [export] with progress reporting (used by the export dialog). */
    suspend fun exportFile(docId: String, options: ExportOptions, progress: Progress): File = guard {
        withContext(Dispatchers.IO) { cleanOldExports() }
        val doc = source.document(docId)
        val count = outputCount(doc, options)
        val dir = File(graph.files.exportsDir, UUID.randomUUID().toString())
        val file = File(dir, ExportNaming.fileName(doc.title, options.format, count))
        try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                BufferedOutputStream(file.outputStream(), BUFFER).use { write(doc, options, it, progress) }
            }
            file
        } catch (t: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
            throw t
        }
    }

    override suspend fun exportTo(docId: String, options: ExportOptions, target: Uri) = exportTo(docId, options, target) { _, _ -> }

    suspend fun exportTo(docId: String, options: ExportOptions, target: Uri, progress: Progress) = guard {
        val doc = source.document(docId)
        writeToUri(doc, options, target, progress)
    }

    override suspend fun exportMany(docIds: List<String>, options: ExportOptions, treeUri: Uri): Int = guard {
        val tree = withContext(Dispatchers.IO) { DocumentFile.fromTreeUri(graph.context, treeUri) }
        if (tree == null || !withContext(Dispatchers.IO) { tree.canWrite() }) throw ExportErrors.of(graph.context, R.string.export_err_folder)
        val taken = withContext(Dispatchers.IO) { tree.listFiles().mapNotNullTo(HashSet()) { it.name } }
        var ok = 0
        for (id in docIds) {
            coroutineContext.ensureActive()
            var created: DocumentFile? = null
            try {
                val doc = source.document(id)
                val count = outputCount(doc, options)
                val name = ExportNaming.unique(ExportNaming.fileName(doc.title, options.format, count), taken)
                created = withContext(Dispatchers.IO) { tree.createFile(ExportNaming.safMime(options.format, count), name) }
                    ?: throw ExportErrors.of(graph.context, R.string.export_err_folder)
                writeToUri(doc, options, created.uri, { _, _ -> }, deleteOnFailure = false)
                taken += created.name ?: name
                ok++
            } catch (c: CancellationException) {
                created?.let { f -> withContext(NonCancellable + Dispatchers.IO) { runCatching { f.delete() } } }
                throw c
            } catch (t: Throwable) {
                created?.let { f -> withContext(NonCancellable + Dispatchers.IO) { runCatching { f.delete() } } }
                Log.w(TAG, "Export of one document failed (${t.javaClass.simpleName})")
                if (ExportErrors.isNoSpace(t)) throw t
            }
        }
        ok
    }

    private suspend fun writeToUri(doc: DocumentMeta, options: ExportOptions, target: Uri, progress: Progress, deleteOnFailure: Boolean = true) {
        try {
            withContext(Dispatchers.IO) {
                val os = SafStreams.openForWrite(graph.context, target) ?: throw ExportErrors.of(graph.context, R.string.export_err_write)
                BufferedOutputStream(os, BUFFER).use { write(doc, options, it, progress) }
            }
        } catch (t: Throwable) {
            if (deleteOnFailure) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(graph.context.contentResolver, target) } }
            }
            throw t
        }
    }

    private suspend fun write(doc: DocumentMeta, options: ExportOptions, out: OutputStream, progress: Progress) {
        when (options.format) {
            ExportFormat.PDF -> pdf.export(doc, options, out) { d, t -> progress.update(d, t) }
            ExportFormat.PNG, ExportFormat.JPEG -> images.export(doc, options, out) { d, t -> progress.update(d, t) }
            ExportFormat.TEXT -> out.write(text.plainText(doc, options.pageIndices, options.includeTranscripts).toByteArray(Charsets.UTF_8))
            ExportFormat.MARKDOWN -> out.write(text.markdown(doc, options.pageIndices, options.includeTranscripts).toByteArray(Charsets.UTF_8))
            ExportFormat.DOCX -> text.docx(doc, options.pageIndices, options.includeTranscripts, out)
            ExportFormat.INKWELL -> packages.write(doc.id, out, options.includeAudio)
        }
        out.flush()
    }

    /** File name and documents-provider MIME type for saving this export ("Save to…"). */
    suspend fun targetName(docId: String, options: ExportOptions): Pair<String, String> = guard {
        val doc = source.document(docId)
        val count = outputCount(doc, options)
        ExportNaming.fileName(doc.title, options.format, count) to ExportNaming.safMime(options.format, count)
    }

    /** Pages in the output (decides between one image and a zip of images). */
    private suspend fun outputCount(doc: DocumentMeta, options: ExportOptions): Int = when (options.format) {
        ExportFormat.PNG, ExportFormat.JPEG -> images.imageCount(doc, options)
        else -> 1
    }

    // ------------------------------------------------------------------ Audio

    override suspend fun exportRecording(recordingId: String): File = guard {
        withContext(Dispatchers.IO) {
            cleanOldExports()
            val rec = graph.db.recordings().get(recordingId)?.toModel()
                ?: throw ExportErrors.of(graph.context, R.string.export_err_no_audio)
            // A segment still being recorded is not playable yet; only finished ones are exported.
            val live = rec.status == RecordingStatus.RECORDING || rec.status == RecordingStatus.PAUSED
            val finished = if (live) rec.segmentFiles.take(rec.segmentDurationsMs.size) else rec.segmentFiles
            val segments = finished.map(::File).filter { it.isFile && it.length() > 0 }
            if (segments.isEmpty()) throw ExportErrors.of(graph.context, R.string.export_err_no_audio)
            val docTitle = source.document(rec.documentId).title
            val base = ExportNaming.sanitize(rec.title.ifBlank { "$docTitle ${graph.context.getString(R.string.export_label_recording)}" })
            val dir = File(graph.files.exportsDir, UUID.randomUUID().toString()).apply { mkdirs() }
            try {
                AudioJoiner.export(segments, dir, base)
            } catch (t: Throwable) {
                dir.deleteRecursively()
                throw t
            }
        }
    }

    // ------------------------------------------------------------------ Share

    override fun shareIntent(file: File, mime: String): Intent {
        val context = graph.context
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        return Intent(Intent.ACTION_SEND).apply {
            // Recordings that cannot be joined arrive as a zip of segments, whatever the caller expected.
            type = if (file.extension.equals("zip", ignoreCase = true)) "application/zip" else mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.name)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ------------------------------------------------------------------ Import

    override suspend fun importPackage(uri: Uri, folderId: String?): DocumentMeta = guard {
        withContext(Dispatchers.IO) {
            val input = graph.context.contentResolver.openInputStream(uri)
                ?: throw ExportErrors.of(graph.context, R.string.export_err_pkg_unreadable)
            input.buffered(BUFFER).use { importer.import(it, folderId) }
        }
    }

    // ------------------------------------------------------------------ Rendering & text

    override suspend fun renderPage(docId: String, pageIndex: Int, widthPx: Int): Bitmap = guard {
        val doc = source.document(docId)
        if (doc.kind == DocKind.TYPED) {
            val typed = source.typed(docId)
            withContext(Dispatchers.Default) {
                TypedLayout(graph.context, doc.title, TypedBlocks.convert(typed), { source.assetFile(docId, it) })
                    .renderBitmap(pageIndex.coerceAtLeast(0), widthPx.coerceAtLeast(1))
            }
        } else {
            val pages = source.pages(docId)
            val page = pages.getOrNull(pageIndex) ?: throw ExportErrors.of(graph.context, R.string.export_err_no_pages)
            raster.render(docId, page, source.content(docId, page), widthPx.coerceAtLeast(1))
        }
    }

    override suspend fun documentMarkdown(docId: String, includeTranscripts: Boolean): String = guard {
        val doc = source.document(docId)
        withContext(Dispatchers.Default) { text.markdown(doc, null, includeTranscripts) }
    }

    // ------------------------------------------------------------------ Housekeeping

    /** Shared files older than a day are removed; recent ones may still be read by the receiving app. */
    private fun cleanOldExports() {
        val cutoff = System.currentTimeMillis() - EXPORT_TTL_MS
        graph.files.exportsDir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) runCatching { f.deleteRecursively() }
        }
    }

    /** Maps every failure to an [ExportException] with a user-facing message (cancellation passes through). */
    private suspend inline fun <T> guard(crossinline block: suspend () -> T): T = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        if (t !is ExportException) Log.w(TAG, "Export operation failed (${t.javaClass.simpleName})")
        throw ExportErrors.wrap(graph.context, t)
    }

    companion object {
        private const val TAG = "Export"
        private const val BUFFER = 256 * 1024
        private const val EXPORT_TTL_MS = 24L * 60 * 60 * 1000
    }
}
