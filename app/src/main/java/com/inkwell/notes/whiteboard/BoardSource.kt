package com.inkwell.notes.whiteboard

import android.graphics.Bitmap
import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.ink.InkDocumentSource
import com.inkwell.notes.whiteboard.kit.DocAssets
import com.inkwell.notes.whiteboard.logic.DebouncedSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** The newest content of a board page that still has to reach the disk. */
class PendingBoardPage(val page: PageMeta, val content: PageContent)

/**
 * Autosave for one whiteboard document, shared by every screen that shows it. A board
 * reopened while its previous screen is still saving reads the unsaved content from here,
 * so no edit is lost or overwritten by a stale file.
 */
class BoardSaver private constructor(private val graph: AppGraph, val docId: String) {
    private val errorListeners = CopyOnWriteArraySet<(Throwable, Boolean) -> Unit>()

    val saver = DebouncedSaver<String, PendingBoardPage>(
        scope = graph.appScope,
        delayMs = SAVE_DEBOUNCE_MS,
        save = { _, p -> persist(p) },
        onResult = { pageId, error, willRetry ->
            if (error != null) {
                Log.e(TAG, "Saving board page $pageId failed (retry=$willRetry): ${error.javaClass.simpleName}")
                errorListeners.forEach { it(error, willRetry) }
            }
        },
    )

    fun addErrorListener(l: (Throwable, Boolean) -> Unit) { errorListeners += l }
    fun removeErrorListener(l: (Throwable, Boolean) -> Unit) { errorListeners -= l }

    private suspend fun persist(p: PendingBoardPage) {
        // A board deleted while its save was pending must not be recreated on disk.
        val exists = runCatching { graph.library.getPage(p.page.id) != null }.getOrDefault(true)
        if (!exists) return
        graph.pageStore.save(docId, p.page.id, p.content)
        runCatching { graph.library.pageContentChanged(docId, p.page.id) }.onFailure { Log.w(TAG, "pageContentChanged failed", it) }
        runCatching { graph.indexing.pageChanged(docId, p.page.id) }.onFailure { Log.w(TAG, "indexing notify failed", it) }
        runCatching { graph.sync.onDocumentChanged(docId) }.onFailure { Log.w(TAG, "sync notify failed", it) }
    }

    companion object {
        private const val TAG = "BoardSaver"
        const val SAVE_DEBOUNCE_MS = 400L
        private val savers = ConcurrentHashMap<String, BoardSaver>()

        fun forDocument(graph: AppGraph, docId: String): BoardSaver = savers.getOrPut(docId) { BoardSaver(graph, docId) }
    }
}

/**
 * Feeds the ink canvas with the board's single infinite page and receives its edits.
 * [onLoaded] and [onChanged] let the screen keep a live copy for the minimap, export and AI.
 */
class BoardSource(
    private val graph: AppGraph,
    override val docId: String,
    private val saver: BoardSaver,
    private val onLoaded: (PageMeta, PageContent) -> Unit,
    private val onChanged: (PageMeta, PageContent) -> Unit,
) : InkDocumentSource {

    override suspend fun loadContent(page: PageMeta): PageContent {
        // Strict: an unreadable board fails to load (the canvas then refuses edits) instead of
        // showing empty and having the next autosave replace the damaged file and its backup.
        val content = saver.saver.pending(page.id)?.content ?: graph.pageStore.load(docId, page.id, strict = true)
        onLoaded(page, content)
        return content
    }

    override fun onContentChanged(page: PageMeta, content: PageContent) {
        saver.saver.submit(page.id, PendingBoardPage(page, content))
        onChanged(page, content)
    }

    override suspend fun renderBackground(page: PageMeta, widthPx: Int, heightPx: Int): Bitmap? = null

    override suspend fun loadImage(assetId: String): Bitmap? = withContext(Dispatchers.IO) {
        DocAssets.decodeAsset(graph, docId, assetId, maxSide = 4096)
    }
}
