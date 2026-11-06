package com.inkwell.notes.ocr.work

import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.RecognitionResult
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.TypedDocument
import com.inkwell.notes.ocr.RecognitionServiceImpl
import com.inkwell.notes.ocr.RecognitionServiceImpl.InkOutcome
import com.inkwell.notes.ocr.SearchIndexImpl
import com.inkwell.notes.ocr.index.IndexEntry
import com.inkwell.notes.ocr.index.IndexWriter
import com.inkwell.notes.ocr.ink.InkGeometry
import com.inkwell.notes.ocr.mlkit.NetworkState
import com.inkwell.notes.ocr.text.ImagePlacement
import com.inkwell.notes.ocr.text.OcrScaling
import com.inkwell.notes.ocr.text.ReadingOrder
import com.inkwell.notes.ocr.text.TextSplitter
import com.inkwell.notes.ocr.text.TypedText
import com.inkwell.notes.pdf.PdfServiceImpl
import com.inkwell.notes.search.TranscriptSearchText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Extracts and stores the text of one page: typed boxes and sticky notes, handwriting
 * (ML Kit digital ink, highlighter excluded), text in placed images (OCR, cached per image),
 * and for PDF pages the embedded text layer or, for scans, OCR of the rendered page with
 * optional AI assistance for low-confidence pages. Runs inside background workers only.
 */
internal class PageIndexer(private val graph: AppGraph) {
    private val writer: IndexWriter by lazy { (graph.search as? SearchIndexImpl)?.writer ?: IndexWriter(graph.db) }
    internal val imageCache by lazy { ImageOcrCache(File(graph.context.noBackupFilesDir, ImageOcrCache.DIR)) }
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** What happened to a page. */
    data class Outcome(
        /** The page's text is current (it was marked indexed). */
        val indexed: Boolean,
        /** Handwriting could not be read because the language model is not on the device yet. */
        val handwritingPendingTag: String? = null,
    )

    /**
     * Indexes [pageId]. Unless [force], a page indexed after its last change is skipped; with
     * [skipIndexedSince] > 0 pages indexed at or after that time are skipped too (resuming a
     * re-index). ML Kit failures are thrown so the worker can retry.
     *
     * Runs of the same page are serialised across workers: otherwise an older run could write
     * its text after a newer one and still mark the page as current.
     */
    suspend fun indexPage(docId: String, pageId: String, force: Boolean, skipIndexedSince: Long = 0L): Outcome =
        pageLock(pageId).withLock { indexPageLocked(docId, pageId, force, skipIndexedSince) }

    private suspend fun indexPageLocked(docId: String, pageId: String, force: Boolean, skipIndexedSince: Long): Outcome {
        val db = graph.db
        val pageEntity = withContext(Dispatchers.IO) { db.pages().get(pageId) } ?: return Outcome(indexed = false)
        val realDocId = pageEntity.documentId
        val doc = withContext(Dispatchers.IO) { db.documents().get(realDocId) } ?: return Outcome(indexed = false)
        if (doc.trashedAt != null) return Outcome(indexed = false)
        if (!force && pageEntity.indexedAt > 0 && pageEntity.indexedAt >= pageEntity.modifiedAt) return Outcome(indexed = true)
        if (skipIndexedSince > 0 && pageEntity.indexedAt >= skipIndexedSince) return Outcome(indexed = true)
        if (realDocId != docId) Log.w(TAG, "Page belongs to another document; indexing it there")

        val started = System.currentTimeMillis()
        val page = pageEntity.toModel()
        val content = graph.pageStore.load(realDocId, pageId)
        val ocrPrefs = graph.settings.current.ocr

        val sources = HashMap<TextSource, List<IndexEntry>>()
        sources[TextSource.TYPED] = typedEntries(content)

        var pendingTag: String? = null
        val strokes = InkGeometry.writingStrokes(content.strokes)
        if (!ocrPrefs.recognizeHandwriting || strokes.isEmpty()) {
            sources[TextSource.HANDWRITING] = emptyList()
        } else {
            when (val r = recognizeHandwriting(content)) {
                is InkOutcome.Recognized -> sources[TextSource.HANDWRITING] = entries(r.result)
                InkOutcome.Unsupported -> sources[TextSource.HANDWRITING] = emptyList()
                InkOutcome.ModelMissing -> pendingTag = recognition()?.languageTag() // keep the previous rows until the model arrives
            }
        }

        val ocr = ArrayList<IndexEntry>()
        for (img in content.images.distinctBy { Triple(it.assetId, it.x, it.y) }) ocr += imageEntries(realDocId, img)
        var pdfText = emptyList<IndexEntry>()
        var pdfTextFailed = false
        // The text layer is given up on and nothing replaces it: leave the earlier PDF text rows.
        var keepPdfText = false
        val pdfIndex = page.pdfPageIndex
        if (pdfIndex != null) {
            val layer = try {
                val pdf = graph.pdf
                if (pdf is PdfServiceImpl) pdf.pageTextOrNull(realDocId, pdfIndex) else pdf.pageText(realDocId, pdfIndex)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "PDF text extraction failed: ${e.javaClass.simpleName}")
                null
            }
            if (layer == null) {
                // Not "no text layer": keep the page's PDF text and OCR rows and try again later,
                // unless there is nothing to keep or the file keeps running out of memory.
                val pdf = graph.pdf
                val givenUp = pdf is PdfServiceImpl && pdf.pageTextGivenUp(realDocId, pdfIndex)
                when (PdfTextFallback.plan(indexedBefore = pageEntity.indexedAt > 0, givenUp = givenUp, autoOcr = ocrPrefs.autoOcrImports)) {
                    PdfTextFallback.Plan.RETRY_LATER -> pdfTextFailed = true
                    PdfTextFallback.Plan.OCR -> ocr += scannedPageEntries(realDocId, page, pdfIndex)
                    PdfTextFallback.Plan.KEEP_PDF_TEXT -> keepPdfText = true
                    PdfTextFallback.Plan.NO_TEXT -> Unit
                }
            } else if (layer.trim().length >= MIN_PDF_TEXT_CHARS) {
                pdfText = TextSplitter.passages(layer).map { IndexEntry(it, null, null, ENGINE_PDF_TEXT) }
            } else if (ocrPrefs.autoOcrImports) {
                ocr += scannedPageEntries(realDocId, page, pdfIndex)
            }
        }
        if (!pdfTextFailed) {
            if (!keepPdfText) sources[TextSource.PDF_TEXT] = pdfText
            sources[TextSource.OCR] = ocr
        }

        writer.replacePageSources(realDocId, pageId, sources)
        if (pendingTag != null || pdfTextFailed) return Outcome(indexed = false, handwritingPendingTag = pendingTag)
        withContext(Dispatchers.IO) { db.pages().markIndexed(pageId, started) }
        return Outcome(indexed = true)
    }

    /** Title and tags of a document (and the body of typed documents). */
    suspend fun indexMeta(docId: String) {
        val doc = withContext(Dispatchers.IO) { graph.db.documents().get(docId) }?.toModel()
        if (doc == null) {
            writer.removeDocument(docId)
            imageCache.removeDocument(docId)
            return
        }
        writer.replaceMeta(doc)
    }

    /** Indexes typed.json of a typed document as TYPED rows on the "typed" pseudo-page. */
    suspend fun indexTyped(docId: String) {
        val doc = withContext(Dispatchers.IO) { graph.db.documents().get(docId) } ?: return
        if (doc.kind != DocKind.TYPED || doc.trashedAt != null) return
        val typed: TypedDocument? = withContext(Dispatchers.IO) {
            val f = graph.files.typedFile(docId)
            if (!f.exists()) return@withContext TypedDocument()
            try {
                json.decodeFromString(TypedDocument.serializer(), f.readText())
            } catch (e: Exception) {
                Log.w(TAG, "Unreadable typed document: ${e.javaClass.simpleName}")
                null
            }
        }
        // Keep the previous rows when the file cannot be read (e.g. mid-write); a later run fixes it.
        typed ?: return
        val entries = TypedText.passages(typed).map { IndexEntry(it, null, null, ENGINE_TYPED) }
        writer.replacePage(docId, ReadingOrder.TYPED_PAGE, TextSource.TYPED, entries)
    }

    /** Rebuilds the transcript rows of a document's recordings from the stored transcripts. */
    suspend fun indexTranscripts(docId: String) {
        val db = graph.db
        val recordings = withContext(Dispatchers.IO) { db.recordings().forDocument(docId) }
        for (r in recordings) {
            val segments = withContext(Dispatchers.IO) { db.transcripts().forRecording(r.id) }
            if (segments.isEmpty()) continue
            // Same text as SearchIndexImpl.replaceTranscript ("Name: text"), so a rebuild keeps people searchable.
            writer.replaceTranscript(docId, r.id, TranscriptSearchText.forIndex(segments.map { it.toModel() }), segments.first().source)
        }
    }

    /** True when typed.json changed after its text was last indexed. */
    suspend fun typedNeedsIndex(docId: String): Boolean = withContext(Dispatchers.IO) {
        val f = graph.files.typedFile(docId)
        if (!f.exists()) return@withContext false
        val last = graph.db.textIndex().lastUpdated(docId, TextSource.TYPED.name) ?: 0L
        f.lastModified() > last
    }

    private fun recognition(): RecognitionServiceImpl? = graph.recognition as? RecognitionServiceImpl

    private suspend fun recognizeHandwriting(content: PageContent): InkOutcome {
        val strokes = InkGeometry.writingStrokes(content.strokes)
        val impl = recognition()
        if (impl == null) {
            val r = graph.recognition.recognizeStrokes(strokes, graph.settings.current.ocr.handwritingLanguages.firstOrNull())
            return if (r != null) InkOutcome.Recognized(r) else InkOutcome.ModelMissing
        }
        // Models are ~20 MB: fetch them in the background only on unmetered networks.
        return impl.recognizeForIndex(strokes, impl.languageTag(), allowDownload = NetworkState.isUnmetered(graph.context))
    }

    private fun typedEntries(content: PageContent): List<IndexEntry> {
        val out = ArrayList<IndexEntry>()
        for (t in content.texts) {
            if (t.text.isBlank()) continue
            out += IndexEntry(t.text, floatArrayOf(t.x, t.y, t.x + t.width, t.y + t.height), null, ENGINE_TYPED)
        }
        for (s in content.stickies) {
            if (s.text.isBlank()) continue
            out += IndexEntry(s.text, floatArrayOf(s.x, s.y, s.x + s.width, s.y + s.height), null, ENGINE_TYPED)
        }
        for (c in content.connectors) {
            if (c.label.isBlank()) continue
            out += IndexEntry(c.label, floatArrayOf(minOf(c.fromX, c.toX), minOf(c.fromY, c.toY), maxOf(c.fromX, c.toX), maxOf(c.fromY, c.toY)), null, ENGINE_TYPED)
        }
        return out
    }

    private suspend fun imageEntries(docId: String, img: ImageItem): List<IndexEntry> {
        val impl = recognition() ?: return emptyList()
        val file = graph.files.assetFile(docId, img.assetId)
        val size = withContext(Dispatchers.IO) { if (file.exists()) file.length() else -1L }
        if (size <= 0) return emptyList()
        val cached = imageCache.get(docId, "$IMAGE_KEY${img.assetId}", size) ?: run {
            val bitmap = withContext(Dispatchers.IO) { ImageOcrCache.decodeForOcr(file) } ?: return emptyList()
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)
            val result = try {
                impl.recognizeBitmapOrThrow(bitmap, null, null)
            } finally {
                bitmap.recycle()
            }
            CachedImageOcr(result.engine, result.blocks.map { normalised(it.text, it.bounds, w, h, it.confidence) }, result.confidence)
                .also { imageCache.put(docId, "$IMAGE_KEY${img.assetId}", size, it) }
        }
        val item = floatArrayOf(img.x, img.y, img.width, img.height)
        val crop = floatArrayOf(img.cropLeft, img.cropTop, img.cropRight, img.cropBottom)
        return cached.blocks.mapNotNull { b ->
            if (b.box.size != 4) return@mapNotNull null
            val bounds = ImagePlacement.toPage(b.box.toFloatArray(), item, crop, img.rotation) ?: return@mapNotNull null
            IndexEntry(b.text, bounds, b.confidence, cached.engine)
        }
    }

    /**
     * OCR of a source-PDF page without a text layer, with optional AI assistance for pages the
     * local recogniser reads with low confidence. Results are cached per page of the source
     * PDF, so annotating a scanned page does not OCR (or pay for an AI transcription) again.
     */
    private suspend fun scannedPageEntries(docId: String, page: PageMeta, pdfIndex: Int): List<IndexEntry> {
        val impl = recognition() ?: return emptyList()
        if (page.isInfinite) return emptyList()
        val prefs = graph.settings.current.ocr
        val sourceSize = sourcePdfSize(docId)
        val key = "$PDF_PAGE_KEY$pdfIndex"
        fun wantsAi(c: CachedImageOcr): Boolean {
            val mean = c.confidence ?: return false
            return !c.isAi && !c.aiTried && prefs.aiAssistLowConfidence && mean < prefs.lowConfidenceThreshold && aiAvailable()
        }
        val cached = if (sourceSize > 0) imageCache.get(docId, key, sourceSize) else null
        if (cached != null && !wantsAi(cached)) return pageEntries(cached, page)

        val (w, h) = OcrScaling.renderSize(page.widthPt, page.heightPt)
        // The PDF service caches its renders: the bitmap is not ours to recycle.
        val bitmap = try {
            graph.pdf.renderPage(docId, pdfIndex, w, h)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "PDF page render failed: ${e.javaClass.simpleName}")
            null
        }
        if (bitmap == null || bitmap.isRecycled) return cached?.let { pageEntries(it, page) } ?: emptyList()

        val local = cached?.takeIf { !it.isAi } ?: run {
            // Recognised in page points, stored normalised to the page.
            val r = impl.recognizeBitmapOrThrow(bitmap, page.widthPt, page.heightPt)
            CachedImageOcr(r.engine, r.blocks.map { normalised(it.text, it.bounds, page.widthPt, page.heightPt, it.confidence) }, r.confidence)
        }
        var result = local
        if (wantsAi(local)) {
            val ai = try {
                if (bitmap.isRecycled) null else impl.recognizeWithAi(bitmap, null)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                null
            }
            if (ai != null && ai.text.isNotBlank()) {
                result = CachedImageOcr(ai.engine, ai.blocks.map { CachedImageOcr.CachedBlock(it.text, WHOLE, null) }, null)
            } else if (ai != null) {
                // The (billed) transcription found no text: keep the local result, but do not ask again.
                result = local.copy(aiTried = true)
            }
        }
        // The source may have been replaced meanwhile (pages inserted): storing under the old size
        // would delete the result carried over to the new file, so it would be OCRed (and paid
        // for) again. Store only while the file is still the one this page was read from, checked
        // under the cache lock that copySize also holds.
        if (sourceSize > 0) imageCache.put(docId, key, sourceSize, result) { sourceSize == sourcePdfSizeNow(docId) }
        return pageEntries(result, page)
    }

    /** Size of [docId]'s source PDF (0 when it has none), the version key of scanned-page results. */
    private suspend fun sourcePdfSize(docId: String): Long = withContext(Dispatchers.IO) { sourcePdfSizeNow(docId) }

    /** [sourcePdfSize] on the calling thread (already on IO). */
    private fun sourcePdfSizeNow(docId: String): Long = graph.files.sourcePdf(docId).let { if (it.exists()) it.length() else 0L }

    private fun normalised(text: String, b: FloatArray, w: Float, h: Float, confidence: Float?) =
        CachedImageOcr.CachedBlock(text, listOf(b[0] / w, b[1] / h, b[2] / w, b[3] / h), confidence)

    private fun pageEntries(c: CachedImageOcr, page: PageMeta): List<IndexEntry> = c.blocks.mapNotNull { b ->
        if (b.box.size != 4) return@mapNotNull null
        val bounds = floatArrayOf(b.box[0] * page.widthPt, b.box[1] * page.heightPt, b.box[2] * page.widthPt, b.box[3] * page.heightPt)
        IndexEntry(b.text, bounds, b.confidence, c.engine)
    }

    private fun aiAvailable(): Boolean = try {
        graph.ai.isAvailable(AiTask.OCR_ASSIST)
    } catch (e: Exception) {
        false
    }

    private fun entries(r: RecognitionResult): List<IndexEntry> = r.blocks.map { IndexEntry(it.text, it.bounds, it.confidence, r.engine) }


    companion object {
        private const val TAG = "PageIndexer"
        /** A PDF page with less embedded text than this is treated as scanned. */
        const val MIN_PDF_TEXT_CHARS = 20
        const val ENGINE_PDF_TEXT = "pdf-text"
        const val ENGINE_TYPED = "typed"
        private const val PDF_PAGE_KEY = ImageOcrCache.PDF_PAGE_KEY
        private const val IMAGE_KEY = "asset-"
        private val WHOLE = listOf(0f, 0f, 1f, 1f)

        /** Striped per-page locks shared by every indexer in the process (pages are indexed by several workers). */
        private val pageLocks = Array(32) { Mutex() }

        private fun pageLock(pageId: String): Mutex = pageLocks[(pageId.hashCode() and Int.MAX_VALUE) % pageLocks.size]
    }
}
