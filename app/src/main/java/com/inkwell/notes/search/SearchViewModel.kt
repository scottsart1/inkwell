package com.inkwell.notes.search

import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.SearchHit
import com.inkwell.notes.ocr.IndexingSchedulerImpl
import com.inkwell.notes.ocr.SearchIndexImpl
import com.inkwell.notes.ocr.index.SearchOutcome
import com.inkwell.notes.ocr.text.FtsQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Everything the search screen shows. */
data class SearchUiState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val semanticAvailable: Boolean = false,
    val semanticOn: Boolean = false,
    val loading: Boolean = false,
    /** The query the current [hits] belong to (trimmed); "" before the first search. */
    val searchedQuery: String = "",
    val hits: List<SearchHit> = emptyList(),
    val failed: Boolean = false,
    val semanticFailed: Boolean = false,
    val recent: List<String> = emptyList(),
    /** Pages whose text is not indexed yet (shown when nothing matches). */
    val pendingPages: Int = 0,
    /** One-off message (document gone, ...). */
    val message: SearchMessage? = null,
) {
    /** Query words for highlighting. */
    val terms: List<String> get() = FtsQuery.matchTokens(searchedQuery)
}

enum class SearchMessage { DOCUMENT_MISSING }

/** Debounced, cancellable search over the local index; recent searches; page previews. */
class SearchViewModel(private val graph: AppGraph, initialQuery: String) : ViewModel() {
    private val prefs = SearchPrefs(graph.context)
    private val queries = MutableStateFlow(initialQuery)
    private val semanticFlow = MutableStateFlow(false)
    private val _state = MutableStateFlow(SearchUiState(query = initialQuery))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val previews = LruCache<String, ImageBitmap>(PREVIEW_CACHE)
    private val previewPermits = Semaphore(2)
    private var searchRun = 0L

    init {
        viewModelScope.launch {
            val recent = runCatching { prefs.recent() }.getOrDefault(emptyList())
            val available = withContext(Dispatchers.IO) {
                runCatching { (graph.search as? SearchIndexImpl)?.semanticAvailable() == true }.getOrDefault(false)
            }
            val on = available && runCatching { prefs.semanticOn() }.getOrDefault(false)
            semanticFlow.value = on
            _state.update { it.copy(recent = recent, semanticAvailable = available, semanticOn = on) }
        }
        viewModelScope.launch {
            refreshPending()
            // Pages edited while indexing was interrupted are picked up shortly.
            if (_state.value.pendingPages > 0) runCatching { (graph.indexing as? IndexingSchedulerImpl)?.catchUpSoon() }
        }
        viewModelScope.launch {
            combine(queries, semanticFlow) { q, s -> q.trim() to s }
                .debounce(DEBOUNCE_MS)
                // Skip a repeat only while its results are still shown: clearing the field resets
                // them outside this pipeline, and a quick retype of the same text must search again.
                .distinctUntilChanged { old, new -> old == new && _state.value.searchedQuery == new.first }
                .collectLatest { (q, s) -> runSearch(q, s) }
        }
    }

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q) }
        queries.value = q
        if (q.isBlank()) {
            // Drop any search still running for the previous text.
            searchRun++
            _state.update { it.copy(hits = emptyList(), searchedQuery = "", loading = false, failed = false, semanticFailed = false) }
        }
    }

    fun setFilter(f: SearchFilter) = _state.update { it.copy(filter = f) }

    fun setSemantic(on: Boolean) {
        val value = on && _state.value.semanticAvailable
        semanticFlow.value = value
        _state.update { it.copy(semanticOn = value) }
        viewModelScope.launch { runCatching { prefs.setSemanticOn(value) } }
    }

    /** Explicit submit (keyboard action): remember the query. */
    fun submit() = remember(_state.value.query)

    fun useRecent(q: String) {
        onQueryChange(q)
        remember(q)
    }

    fun removeRecent(q: String) = updateRecent(RecentQueries.remove(_state.value.recent, q))

    fun clearRecent() = updateRecent(emptyList())

    fun retry() {
        val q = _state.value.query.trim()
        viewModelScope.launch { runSearch(q, semanticFlow.value) }
    }

    /** Re-runs the current search (e.g. back from a document, where text may have changed). */
    fun refresh() {
        val q = _state.value.query.trim()
        if (q.isEmpty() || _state.value.loading) return
        viewModelScope.launch {
            runSearch(q, semanticFlow.value)
            refreshPending()
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun remember(q: String) {
        if (q.isBlank()) return
        updateRecent(RecentQueries.add(_state.value.recent, q))
    }

    private fun updateRecent(list: List<String>) {
        _state.update { it.copy(recent = list) }
        viewModelScope.launch { runCatching { prefs.saveRecent(list) } }
    }

    private suspend fun runSearch(q: String, semantic: Boolean) {
        if (q.isEmpty()) {
            _state.update { it.copy(hits = emptyList(), searchedQuery = "", loading = false, failed = false, semanticFailed = false) }
            return
        }
        val run = ++searchRun
        _state.update { it.copy(loading = true, failed = false) }
        try {
            val outcome = withContext(Dispatchers.IO) {
                val impl = graph.search as? SearchIndexImpl
                impl?.searchDetailed(q, RESULT_LIMIT, semantic) ?: SearchOutcome(graph.search.search(q, RESULT_LIMIT, semantic))
            }
            if (run != searchRun) return
            _state.update {
                it.copy(
                    loading = false, searchedQuery = q, hits = outcome.hits, failed = false,
                    semanticFailed = semantic && outcome.semanticFailed,
                )
            }
            if (outcome.hits.isEmpty()) refreshPending()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "Search failed: ${e.javaClass.simpleName}")
            if (run == searchRun) _state.update { it.copy(loading = false, failed = true, searchedQuery = q, hits = emptyList()) }
        }
    }

    private suspend fun refreshPending() {
        val pending = withContext(Dispatchers.IO) {
            runCatching {
                val live = graph.db.documents().getAll().filter { it.trashedAt == null }.mapTo(HashSet()) { it.id }
                (graph.db.pages().needingIndex() + graph.db.textIndex().pagesNeverIndexed(PENDING_SCAN))
                    .filter { it.documentId in live }
                    .distinctBy { it.id }
                    .size
            }.getOrDefault(0)
        }
        _state.update { it.copy(pendingPages = pending) }
    }

    /** Opens [docId] at [pageIndex]; remembers the query that led there. */
    fun open(docId: String, pageIndex: Int?, onOpen: (DocumentMeta, Int?) -> Unit) {
        remember(_state.value.searchedQuery.ifBlank { _state.value.query })
        viewModelScope.launch {
            val doc = withContext(Dispatchers.IO) {
                val fromLibrary = try {
                    graph.library.getDocument(docId)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    null
                }
                fromLibrary ?: runCatching { graph.db.documents().get(docId)?.toModel() }.getOrNull()
            }
            if (doc == null || doc.trashedAt != null) {
                _state.update { it.copy(message = SearchMessage.DOCUMENT_MISSING) }
                return@launch
            }
            onOpen(doc, pageIndex)
        }
    }

    fun cachedPreview(pageId: String): ImageBitmap? = previews.get(pageId)

    /** Small rendering of a page for a result row; null when unavailable. */
    suspend fun preview(docId: String, pageId: String): ImageBitmap? {
        previews.get(pageId)?.let { return it }
        return previewPermits.withPermit {
            previews.get(pageId) ?: withContext(Dispatchers.IO) {
                try {
                    graph.thumbnails.pageThumbnail(docId, pageId, PREVIEW_WIDTH_PX)?.takeIf { !it.isRecycled }?.asImageBitmap()
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    null
                }
            }?.also { previews.put(pageId, it) }
        }
    }

    companion object {
        private const val TAG = "SearchScreen"
        const val DEBOUNCE_MS = 250L
        const val RESULT_LIMIT = 200
        private const val PREVIEW_WIDTH_PX = 160
        private const val PREVIEW_CACHE = 64
        private const val PENDING_SCAN = 1_000
    }
}
