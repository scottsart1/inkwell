package com.inkwell.notes.ocr

import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.storage.FileStore
import com.inkwell.notes.ocr.text.ReadingOrder
import com.inkwell.notes.ocr.work.CatchUpWorker
import com.inkwell.notes.ocr.work.IndexDocumentWorker
import com.inkwell.notes.ocr.work.IndexPageWorker
import com.inkwell.notes.ocr.work.ImageOcrCache
import com.inkwell.notes.ocr.work.IndexingWorker
import com.inkwell.notes.ocr.work.ModelDownloadWorker
import com.inkwell.notes.ocr.work.PageIndexer
import com.inkwell.notes.ocr.work.ReindexAllWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Schedules recognition and indexing with WorkManager so it survives process death and never
 * runs on the UI thread: one debounced unique job per edited page ("index-<pageId>",
 * replaced on every edit), one per import ("import-<docId>", battery not low), a full rebuild
 * on request, and a periodic catch-up for anything missed.
 */
class IndexingSchedulerImpl(private val graph: AppGraph) : IndexingScheduler {
    private val periodicScheduled = AtomicBoolean(false)
    private val indexer by lazy { PageIndexer(graph) }

    private fun workManager(): WorkManager? = try {
        WorkManager.getInstance(graph.context)
    } catch (e: IllegalStateException) {
        Log.w(TAG, "WorkManager unavailable")
        null
    }

    private inline fun enqueue(what: String, block: (WorkManager) -> Unit) {
        val wm = workManager() ?: return
        try {
            block(wm)
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule $what: ${e.javaClass.simpleName}")
        }
        ensurePeriodic(wm)
    }

    override fun pageChanged(docId: String, pageId: String) {
        val name = if (pageId == ReadingOrder.TYPED_PAGE) "$WORK_INDEX$WORK_TYPED$docId" else "$WORK_INDEX$pageId"
        val request = OneTimeWorkRequestBuilder<IndexPageWorker>()
            .setInputData(workDataOf(IndexingWorker.KEY_DOC to docId, IndexingWorker.KEY_PAGE to pageId))
            .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG_WORK)
            .build()
        enqueue("page indexing") { it.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request) }
    }

    override fun documentImported(docId: String) {
        val request = OneTimeWorkRequestBuilder<IndexDocumentWorker>()
            .setInputData(workDataOf(IndexingWorker.KEY_DOC to docId))
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG_WORK)
            .build()
        enqueue("import indexing") { it.enqueueUniqueWork("$WORK_IMPORT$docId", ExistingWorkPolicy.REPLACE, request) }
        // The title is searchable right away; pages follow in the background.
        refreshMeta(docId)
    }

    override fun documentMetaChanged(docId: String) = refreshMeta(docId)

    private fun refreshMeta(docId: String) {
        graph.appScope.launch {
            try {
                indexer.indexMeta(docId)
                val kind = withContext(Dispatchers.IO) { graph.db.documents().get(docId)?.kind }
                // Typed documents keep their body in typed.json; re-read it once edits settle.
                if (kind == DocKind.TYPED) pageChanged(docId, ReadingOrder.TYPED_PAGE)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "Metadata indexing failed: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun reindexAll() {
        val request = OneTimeWorkRequestBuilder<ReindexAllWorker>()
            .setInputData(workDataOf(IndexingWorker.KEY_SINCE to System.currentTimeMillis()))
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG_WORK)
            .build()
        enqueue("re-index") { it.enqueueUniqueWork(WORK_REINDEX_ALL, ExistingWorkPolicy.REPLACE, request) }
    }

    override suspend fun sourcePdfAppending(docId: String, oldSize: Long, newSize: Long) {
        // Scanned-page results are keyed by the source file's size; carry them over to the new one.
        indexer.imageCache.copySize(docId, ImageOcrCache.PDF_PAGE_KEY, oldSize, newSize)
    }

    /** Runs the catch-up soon (e.g. after a handwriting model arrived or search was opened). */
    fun catchUpSoon() {
        val request: OneTimeWorkRequest = OneTimeWorkRequestBuilder<CatchUpWorker>()
            .setInitialDelay(CATCH_UP_DELAY_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG_WORK)
            .build()
        enqueue("catch-up") { it.enqueueUniqueWork(WORK_CATCH_UP_NOW, ExistingWorkPolicy.KEEP, request) }
    }

    /** Downloads [languageTag]'s handwriting model once on an unmetered network. */
    fun scheduleModelDownload(languageTag: String) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(IndexingWorker.KEY_TAG to languageTag))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .addTag(TAG_WORK)
            .build()
        enqueue("model download") {
            it.enqueueUniqueWork("$WORK_MODEL${FileStore.safe(languageTag)}", ExistingWorkPolicy.KEEP, request)
        }
    }

    private fun ensurePeriodic(wm: WorkManager) {
        if (!periodicScheduled.compareAndSet(false, true)) return
        try {
            val request = PeriodicWorkRequestBuilder<CatchUpWorker>(CATCH_UP_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .addTag(TAG_WORK)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_CATCH_UP, ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            periodicScheduled.set(false)
            Log.w(TAG, "Could not schedule catch-up: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "Indexing"
        const val TAG_WORK = "inkwell-indexing"
        const val WORK_INDEX = "index-"
        const val WORK_TYPED = "typed-"
        const val WORK_IMPORT = "import-"
        const val WORK_REINDEX_ALL = "index-all"
        const val WORK_CATCH_UP = "index-catch-up"
        const val WORK_CATCH_UP_NOW = "index-catch-up-now"
        const val WORK_MODEL = "hw-model-"
        const val DEBOUNCE_SECONDS = 3L
        const val BACKOFF_SECONDS = 30L
        const val CATCH_UP_DELAY_SECONDS = 5L
        const val CATCH_UP_HOURS = 6L
    }
}
