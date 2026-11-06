package com.inkwell.notes.ocr.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.ocr.IndexingSchedulerImpl
import com.inkwell.notes.ocr.SearchIndexImpl
import com.inkwell.notes.ocr.index.IndexWriter
import com.inkwell.notes.ocr.mlkit.NetworkState
import com.inkwell.notes.ocr.text.ReadingOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Base for indexing workers: resolves the app graph, runs the work, and turns failures into
 * a bounded number of retries. Nothing here ever reaches the UI thread.
 */
internal abstract class IndexingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    protected lateinit var graph: AppGraph
    protected val indexer: PageIndexer by lazy { PageIndexer(graph) }
    /** Language tags whose handwriting model was missing while this worker ran. */
    protected val pendingModels = LinkedHashSet<String>()
    /** Pages of a batch whose indexing failed; the batch went on and is retried as a whole. */
    private var pageFailures = 0

    final override suspend fun doWork(): Result {
        graph = AppGraph.get(applicationContext)
        return try {
            work()
            pendingModels.forEach { scheduler()?.scheduleModelDownload(it) }
            if (pageFailures == 0) {
                Result.success()
            } else {
                Log.w(javaClass.simpleName, "$pageFailures page(s) failed (attempt ${runAttemptCount + 1})")
                if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(javaClass.simpleName, "Indexing failed (attempt ${runAttemptCount + 1}): ${e.javaClass.simpleName}")
            if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    protected abstract suspend fun work()

    protected fun scheduler(): IndexingSchedulerImpl? = graph.indexing as? IndexingSchedulerImpl

    protected suspend fun page(docId: String, pageId: String, force: Boolean, since: Long = 0L) {
        val outcome = indexer.indexPage(docId, pageId, force, since)
        outcome.handwritingPendingTag?.let { pendingModels += it }
    }

    /**
     * [page] for batch workers: a page whose recognition keeps failing must not stop every page
     * after it from being indexed, so the failure is counted and the batch goes on.
     */
    protected suspend fun batchPage(docId: String, pageId: String, force: Boolean, since: Long = 0L) {
        try {
            page(docId, pageId, force, since)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            pageFailures++
            Log.w(javaClass.simpleName, "Page indexing failed: ${e.javaClass.simpleName}")
        }
    }

    /** Indexes every page of [docId] (meta and typed body too). Stops early when the worker is stopped. */
    protected suspend fun document(docId: String, force: Boolean, since: Long = 0L) {
        indexer.indexMeta(docId)
        val doc = withContext(Dispatchers.IO) { graph.db.documents().get(docId) } ?: return
        if (doc.trashedAt != null) return
        if (doc.kind == DocKind.TYPED) indexer.indexTyped(docId)
        val pages = withContext(Dispatchers.IO) { graph.db.pages().forDocument(docId) }
        for (p in pages) {
            if (isStopped) return
            batchPage(docId, p.id, force, since)
        }
    }

    /** Computes missing embeddings when semantic search is on and the device is online. */
    protected suspend fun embeddings(maxBatches: Int) {
        if (isStopped || !NetworkState.isOnline(applicationContext)) return
        (graph.search as? SearchIndexImpl)?.semanticIndex?.backfill(maxBatches)
    }

    companion object {
        const val MAX_ATTEMPTS = 4
        const val KEY_DOC = "docId"
        const val KEY_PAGE = "pageId"
        const val KEY_SINCE = "since"
        const val KEY_TAG = "languageTag"
    }
}

/** Re-recognises one page after edits settle (unique per page, replaced on every edit). */
internal class IndexPageWorker(context: Context, params: WorkerParameters) : IndexingWorker(context, params) {
    override suspend fun work() {
        val docId = inputData.getString(KEY_DOC) ?: return
        val pageId = inputData.getString(KEY_PAGE) ?: return
        val doc = withContext(Dispatchers.IO) { graph.db.documents().get(docId) }
        if (pageId == ReadingOrder.TYPED_PAGE || doc?.kind == DocKind.TYPED) {
            indexer.indexTyped(docId)
        } else {
            page(docId, pageId, force = true)
        }
        embeddings(maxBatches = 2)
    }
}

/** Extracts text layers and OCRs every page of an imported document. */
internal class IndexDocumentWorker(context: Context, params: WorkerParameters) : IndexingWorker(context, params) {
    override suspend fun work() {
        val docId = inputData.getString(KEY_DOC) ?: return
        document(docId, force = false)
        embeddings(maxBatches = 4)
    }
}

/** Rebuilds the whole index (settings action). Resumable: pages done since the start are skipped. */
internal class ReindexAllWorker(context: Context, params: WorkerParameters) : IndexingWorker(context, params) {
    override suspend fun work() {
        val since = inputData.getLong(KEY_SINCE, System.currentTimeMillis())
        val writer = (graph.search as? SearchIndexImpl)?.writer ?: IndexWriter(graph.db)
        writer.deleteOrphans()
        val docs = withContext(Dispatchers.IO) { graph.db.documents().getAll() }
            .filter { it.trashedAt == null }
            .sortedByDescending { it.modifiedAt }
        for (d in docs) {
            if (isStopped) return
            document(d.id, force = true, since = since)
            indexer.indexTranscripts(d.id)
        }
        embeddings(maxBatches = 16)
    }
}

/**
 * Periodic (and on-demand) catch-up: pages changed but not indexed (app closed during the
 * debounce, model missing earlier), titles never indexed, typed documents edited since their
 * last indexing, stale rows of deleted content, and pending embeddings.
 */
internal class CatchUpWorker(context: Context, params: WorkerParameters) : IndexingWorker(context, params) {
    override suspend fun work() {
        val db = graph.db
        val writer = (graph.search as? SearchIndexImpl)?.writer ?: IndexWriter(graph.db)
        writer.deleteOrphans()
        for (docId in withContext(Dispatchers.IO) { db.textIndex().documentsMissingTitle() }) {
            if (isStopped) return
            indexer.indexMeta(docId)
        }
        val all = withContext(Dispatchers.IO) { db.documents().getAll() }
        // Cached OCR of deleted documents (documents in the trash keep theirs until emptied).
        indexer.imageCache.prune(all.mapTo(HashSet()) { it.id })
        val docs = all.filter { it.trashedAt == null }.associateBy { it.id }
        val stale = withContext(Dispatchers.IO) { db.pages().needingIndex() + db.textIndex().pagesNeverIndexed(NEVER_INDEXED_BATCH) }
            .filter { it.documentId in docs }
            .distinctBy { it.id }
            .sortedByDescending { it.modifiedAt }
        for (p in stale) {
            if (isStopped) return
            batchPage(p.documentId, p.id, force = false)
        }
        for (d in docs.values.filter { it.kind == DocKind.TYPED }) {
            if (isStopped) return
            if (indexer.typedNeedsIndex(d.id)) indexer.indexTyped(d.id)
        }
        embeddings(maxBatches = 16)
    }

    private companion object {
        const val NEVER_INDEXED_BATCH = 500
    }
}

/** Downloads a handwriting model once an unmetered network is available, then catches up. */
internal class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val tag = inputData.getString(IndexingWorker.KEY_TAG) ?: return Result.failure()
        val graph = AppGraph.get(applicationContext)
        return try {
            if (graph.recognition.downloadHandwritingModel(tag)) {
                (graph.indexing as? IndexingSchedulerImpl)?.catchUpSoon()
                Result.success()
            } else if (runAttemptCount + 1 < IndexingWorker.MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w("ModelDownloadWorker", "Download failed: ${e.javaClass.simpleName}")
            if (runAttemptCount + 1 < IndexingWorker.MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }
}
