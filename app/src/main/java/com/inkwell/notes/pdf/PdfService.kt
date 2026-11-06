package com.inkwell.notes.pdf

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * PDF import, rendering and page-level operations. The source PDF is copied into the
 * document folder and never modified; annotations live separately as page content, so
 * they stay editable (non-destructive). Exports burn them in only when asked.
 */
interface PdfService {
    /** Imports a PDF (keeps its original file name as the title) and schedules text extraction/OCR. */
    suspend fun importPdf(uri: Uri, folderId: String? = null): DocumentMeta

    /** Creates a PDF document from images (photos, scans, screenshots), one page per image. */
    suspend fun importImages(uris: List<Uri>, folderId: String? = null, title: String? = null): DocumentMeta

    /**
     * Renders one source-PDF page (white background) at the given pixel size. Cached.
     * The returned bitmap is shared with the cache (and other callers): draw it, never recycle it.
     * Its width may be rounded up a little (cache bucket); draw it into the destination rect.
     */
    suspend fun renderPage(docId: String, pdfPageIndex: Int, widthPx: Int, heightPx: Int): Bitmap?

    /** Page sizes of the source PDF in points. */
    suspend fun pageSizes(docId: String): List<Pair<Float, Float>>

    /** Embedded text of a source-PDF page in reading order ("" for scanned pages). */
    suspend fun pageText(docId: String, pdfPageIndex: Int): String

    /** Appends/inserts all pages of another PDF into [docId] (merge). */
    suspend fun insertPdf(docId: String, uri: Uri, atIndex: Int? = null)

    /** New document containing copies of the given pages (with their annotations). */
    suspend fun extractPages(docId: String, pageIndices: List<Int>, title: String? = null): DocumentMeta

    /** Splits [docId] before [atPageIndex] into a second document; returns it. */
    suspend fun splitDocument(docId: String, atPageIndex: Int): DocumentMeta

    /** Merges [otherDocIds] into a new document, in order. */
    suspend fun mergeDocuments(docIds: List<String>, title: String? = null): DocumentMeta

    /** Releases cached renderers for a document. */
    fun close(docId: String)
}

/**
 * The PDF implementation. Failures surface as [PdfException] with a user-facing message; no
 * operation leaves a half-created document behind. Rendering is serialised on one thread,
 * structural work (pdfbox) and file copies run on [Dispatchers.IO], so every entry point is
 * safe to call from the main thread.
 *
 * Rendered bitmaps are shared with an LRU cache: callers draw them but must not recycle them.
 */
class PdfServiceImpl(private val graph: AppGraph) : PdfService {
    private val context get() = graph.context
    private val files get() = graph.files
    private val library get() = graph.library

    private val workDir: File get() = File(files.cacheDir, WORK_DIR).apply { mkdirs() }

    private val engineLazy = lazy { PdfRenderEngine(sourceFile = { files.sourcePdf(it) }, scope = graph.appScope) }
    private val engine by engineLazy
    private val pdfBox by lazy { PdfBoxOps(context) { workDir } }
    private val text by lazy { PdfTextExtractor(pdfBox, sourceFile = { files.sourcePdf(it) }, scope = graph.appScope) }
    private val importer by lazy { PdfImporter(context, engine, pdfBox) { workDir } }
    private val images by lazy { ImagePdfWriter(context, pdfBox) }
    private val assembler by lazy { DocumentAssembler(graph, engine, pdfBox, importer) }

    /** Serialises changes to one document's source PDF. */
    private val sourceLocks = ConcurrentHashMap<String, Mutex>()

    init {
        graph.appScope.launch(Dispatchers.IO) { cleanWorkDir() }
        runCatching { context.registerComponentCallbacks(MemoryCallbacks()) }
    }

    // ------------------------------------------------------------------ import

    override suspend fun importPdf(uri: Uri, folderId: String?): DocumentMeta {
        // Scanner output carries no meaningful name: let it be auto-named from its text.
        val title = if (PdfNames.isScannerOutput(uri.toString())) null
        else PdfNames.titleFromDisplayName(importer.displayName(uri))
        return importUri(uri, folderId, title)
    }

    /** Imports a PDF written by the document scanner; it gets the standard date name. */
    internal suspend fun importScannedPdf(uri: Uri, folderId: String?): DocumentMeta = importUri(uri, folderId, title = null)

    override suspend fun importImages(uris: List<Uri>, folderId: String?, title: String?): DocumentMeta {
        if (uris.isEmpty()) throw PdfErrors.create(context, PdfError.NO_IMAGES)
        val out = importer.tempFile("images-")
        try {
            try {
                images.write(uris, out)
            } catch (e: PdfException) {
                throw e
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "Writing images to PDF failed (${e.javaClass.simpleName})")
                throw PdfErrors.forWrite(context, e)
            }
            return importPreparedFile(out, folderId, title?.let(PdfNames::clean))
        } finally {
            out.delete()
        }
    }

    private suspend fun importUri(uri: Uri, folderId: String?, title: String?): DocumentMeta {
        val tmp = importer.copyToTemp(uri)
        try {
            return importPreparedFile(tmp, folderId, title)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Validates [file] (decrypting or repairing it when possible), then creates the document,
     * moves the file in as its source PDF and adds one page per PDF page. [title] null gives
     * the standard "YYYYMMDD_Untitled" name.
     */
    private suspend fun importPreparedFile(file: File, folderId: String?, title: String?): DocumentMeta {
        val sizes = importer.prepare(file)
        // Created without cancellation so the id always comes back and a cancel is discarded below.
        val doc = try {
            // Not cancellable on its own: a document committed just as the import is cancelled
            // would otherwise stay in the library without pages. Cancellation is noticed by the
            // next step, which removes the document again.
            withContext(NonCancellable) { library.createDocument(DocKind.PDF, folderId, originalName = title) }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "Creating the document failed (${e.javaClass.simpleName})")
            throw PdfErrors.forWrite(context, e)
        }
        try {
            currentCoroutineContext().ensureActive()
            engine.replaceSource(doc.id, file)
            library.addPdfPages(doc.id, sizes)
        } catch (c: CancellationException) {
            withContext(NonCancellable) { discard(doc.id) }
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "Import failed after the document was created (${e.javaClass.simpleName})")
            withContext(NonCancellable) { discard(doc.id) }
            throw PdfErrors.forWrite(context, e)
        }
        afterPdfChanged(doc.id)
        return library.getDocument(doc.id) ?: doc.copy(pageCount = sizes.size)
    }

    // ------------------------------------------------------------------ rendering and text

    override suspend fun renderPage(docId: String, pdfPageIndex: Int, widthPx: Int, heightPx: Int): Bitmap? =
        engine.render(docId, pdfPageIndex, widthPx, heightPx)

    override suspend fun pageSizes(docId: String): List<Pair<Float, Float>> = engine.pageSizes(docId)

    override suspend fun pageText(docId: String, pdfPageIndex: Int): String = text.pageText(docId, pdfPageIndex)

    /** [pageText], but null when the text could not be read for now (see [PdfTextExtractor.pageTextOrNull]). */
    internal suspend fun pageTextOrNull(docId: String, pdfPageIndex: Int): String? = text.pageTextOrNull(docId, pdfPageIndex)

    /** True when the text layer of [docId]'s page [pdfPageIndex] keeps running out of memory (see [PdfTextExtractor.givenUp]). */
    internal suspend fun pageTextGivenUp(docId: String, pdfPageIndex: Int): Boolean = text.givenUp(docId, pdfPageIndex)

    // ------------------------------------------------------------------ page operations

    override suspend fun insertPdf(docId: String, uri: Uri, atIndex: Int?) {
        val doc = library.getDocument(docId) ?: throw PdfErrors.create(context, PdfError.DOCUMENT_MISSING)
        if (doc.kind != DocKind.NOTEBOOK && doc.kind != DocKind.PDF) {
            throw PdfErrors.create(context, PdfError.UNSUPPORTED_DOCUMENT)
        }
        val incoming = importer.copyToTemp(uri)
        var merged: File? = null
        try {
            val newSizes = importer.prepare(incoming)
            sourceLocks.getOrPut(docId) { Mutex() }.withLock {
                val source = files.sourcePdf(docId)
                val existingCount = existingPdfPageCount(docId, source)
                val oldSize = source.length()
                val replacement = if (existingCount > 0) {
                    appendPdf(source, incoming, existingCount, newSizes.size).also { merged = it }
                } else {
                    // A notebook without a PDF yet: the inserted file becomes its source PDF.
                    incoming
                }
                // Once the file is swapped, the pages must follow: no cancellation in between.
                withContext(NonCancellable) {
                    val newSize = replacement.length()
                    engine.replaceSource(docId, replacement)
                    if (existingCount > 0) {
                        // Existing pages keep their indices: their OCR stays valid for the new file.
                        // Carried over after the swap, so a result an indexer stores for the old
                        // size meanwhile is either copied too or dropped (ImageOcrCache.put).
                        try {
                            graph.indexing.sourcePdfAppending(docId, oldSize, newSize)
                        } catch (e: Exception) {
                            Log.w(TAG, "Keeping OCR results failed (${e.javaClass.simpleName})")
                        }
                    }
                    text.invalidate(docId)
                    val plan = PdfPageMapping.insertPlan(existingCount, newSizes.size, atIndex, library.getPages(docId).size)
                    library.addPdfPages(docId, newSizes, firstPdfIndex = plan.firstPdfIndex, atIndex = plan.atPageIndex)
                }
            }
        } catch (e: PdfException) {
            throw e
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "Inserting a PDF failed (${e.javaClass.simpleName})")
            throw PdfErrors.forWrite(context, e)
        } finally {
            incoming.delete()
            merged?.delete()
        }
        afterPdfChanged(docId)
    }

    /** Pages in the document's current source PDF (0 when it has none). */
    private suspend fun existingPdfPageCount(docId: String, source: File): Int {
        if (!source.isFile || source.length() == 0L) return 0
        val rendered = engine.pageCount(docId)
        if (rendered > 0) return rendered
        // The renderer could not open it; never replace a PDF that pages may still point into.
        val parsed = withContext(Dispatchers.IO) {
            try {
                pdfBox.open(source).use { it.numberOfPages }
            } catch (e: Exception) {
                -1
            }
        }
        if (parsed <= 0) throw PdfErrors.create(context, PdfError.SOURCE_UNREADABLE)
        return parsed
    }

    /** Writes source + incoming into a new temp file and checks that page numbering is preserved. */
    private suspend fun appendPdf(source: File, incoming: File, existingCount: Int, addedCount: Int): File {
        val out = importer.tempFile("merge-")
        try {
            val before = withContext(Dispatchers.IO) { pdfBox.append(source, incoming, out) }
            val probe = engine.probe(out)
            // Existing pages must keep their indices; otherwise annotations would move pages.
            if (before != existingCount || probe !is PdfRenderEngine.Probe.Ok || probe.sizes.size != existingCount + addedCount) {
                throw PdfErrors.create(context, PdfError.MERGE_FAILED)
            }
            return out
        } catch (e: PdfException) {
            out.delete()
            throw e
        } catch (e: IOException) {
            out.delete()
            throw if (PdfErrors.isOutOfSpace(e)) PdfErrors.create(context, PdfError.NO_SPACE, e)
            else PdfErrors.create(context, PdfError.MERGE_FAILED, e)
        } catch (oom: OutOfMemoryError) {
            out.delete()
            throw PdfErrors.create(context, PdfError.TOO_LARGE, oom)
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
    }

    override suspend fun extractPages(docId: String, pageIndices: List<Int>, title: String?): DocumentMeta =
        assembler.extract(docId, pageIndices, title)

    override suspend fun splitDocument(docId: String, atPageIndex: Int): DocumentMeta =
        assembler.split(docId, atPageIndex)

    override suspend fun mergeDocuments(docIds: List<String>, title: String?): DocumentMeta =
        assembler.merge(docIds, title)

    override fun close(docId: String) {
        engine.close(docId)
        text.invalidate(docId)
    }

    // ------------------------------------------------------------------ helpers

    private fun afterPdfChanged(docId: String) {
        runCatching { graph.indexing.documentImported(docId) }
            .onFailure { Log.w(TAG, "Indexing could not be scheduled (${it.javaClass.simpleName})") }
        runCatching { graph.thumbnails.refresh(docId) }
            .onFailure { Log.w(TAG, "Thumbnail refresh failed (${it.javaClass.simpleName})") }
    }

    private suspend fun discard(docId: String) {
        try {
            engine.close(docId)
            library.deletePermanently(docId)
        } catch (e: Exception) {
            Log.w(TAG, "Cleaning up a partial import failed (${e.javaClass.simpleName})")
        }
    }

    /** Temp files left by a process that died mid-import. */
    private fun cleanWorkDir() {
        val cutoff = System.currentTimeMillis() - STALE_TEMP_MS
        workDir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.deleteRecursively() }
    }

    private inner class MemoryCallbacks : ComponentCallbacks2 {
        @Suppress("DEPRECATION")
        override fun onTrimMemory(level: Int) {
            when {
                !engineLazy.isInitialized() -> Unit
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> engine.trimCache(0f)
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> engine.trimCache(0.25f)
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {
            if (engineLazy.isInitialized()) engine.trimCache(0f)
        }
    }

    companion object {
        private const val TAG = "PdfService"
        private const val WORK_DIR = "pdf-work"
        private const val STALE_TEMP_MS = 6L * 60 * 60 * 1000
    }
}
