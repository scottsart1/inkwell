package com.inkwell.notes.ocr.index

import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.SearchHit
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.storage.db.DocumentEntity
import com.inkwell.notes.core.storage.db.TextSearchRow
import com.inkwell.notes.ocr.text.Candidate
import com.inkwell.notes.ocr.text.DocInfo
import com.inkwell.notes.ocr.text.FolderPaths
import com.inkwell.notes.ocr.text.FtsQuery
import com.inkwell.notes.ocr.text.HitAssembler
import com.inkwell.notes.ocr.text.ReadingOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of one search and what happened to its optional semantic part. */
data class SearchOutcome(
    val hits: List<SearchHit>,
    /** Semantic matches were included. */
    val semanticUsed: Boolean = false,
    /** Semantic matching was requested but failed (offline, provider error); keyword results only. */
    val semanticFailed: Boolean = false,
)

/**
 * Library-wide search: full-text matches from the FTS index, substring matches on titles,
 * tags and folder names, and (optionally) semantically similar passages, merged and ranked.
 * Documents in the trash never appear. With a [documentId] only that document is searched,
 * so its hits are never crowded out by other documents before the limit.
 */
internal class SearchEngine(private val graph: AppGraph, private val semantic: SemanticIndex) {

    suspend fun search(query: String, limit: Int, wantSemantic: Boolean, documentId: String? = null): SearchOutcome = withContext(Dispatchers.IO) {
        val raw = query.trim().take(MAX_QUERY_CHARS)
        val ftsTokens = FtsQuery.tokens(raw)
        val terms = FtsQuery.matchTokens(raw)
        if (ftsTokens.isEmpty() || limit <= 0) return@withContext SearchOutcome(emptyList())
        val now = System.currentTimeMillis()
        val docs = graph.db.documents().getAll().asSequence()
            .filter { it.trashedAt == null && (documentId == null || it.id == documentId) }
            .map { it.toInfo() }
            .associateBy { it.id }
        if (docs.isEmpty()) return@withContext SearchOutcome(emptyList())
        val folders = graph.db.folders().getAllIncludingTrashed().associate { it.id to FolderPaths.Node(it.id, it.name, it.parentId) }

        val fetch = (limit * 6).coerceIn(MIN_FETCH, MAX_FETCH)
        val keyword = ArrayList<Candidate>()
        keywordRows(ftsTokens, raw, fetch, documentId).forEach { r -> toCandidate(r, docs)?.let { keyword += it } }
        keyword += HitAssembler.metaMatches(docs.values, folders, terms, raw)

        var used = false
        var failed = false
        val similar = if (wantSemantic && semantic.enabled()) {
            try {
                semanticCandidates(raw, docs).also { used = true }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "Semantic search unavailable: ${e.javaClass.simpleName}")
                failed = true
                emptyList()
            }
        } else emptyList()

        val ranked = HitAssembler.rank(HitAssembler.merge(keyword, similar), docs, terms, now, limit)
        val paths = HashMap<String, String>()
        val hits = ranked.mapNotNull { r ->
            val doc = docs[r.candidate.documentId] ?: return@mapNotNull null
            HitAssembler.toHit(r, doc, folderPath(doc.folderId, folders, paths), terms)
        }
        SearchOutcome(hits, used, failed)
    }

    /**
     * Full-text rows for [tokens]: the union of every case variant of the MATCH expression (the
     * simple tokenizer folds ASCII only, see [FtsQuery.variants]), each row once.
     */
    private suspend fun keywordRows(tokens: List<String>, raw: String, limit: Int, documentId: String?): List<TextSearchRow> {
        val expressions = FtsQuery.variants(tokens)
        if (expressions.isEmpty()) return emptyList()
        val dao = graph.db.textIndex()
        val rows = LinkedHashMap<Long, TextSearchRow>()
        try {
            for (match in expressions) {
                val found = if (documentId == null) dao.searchLive(match, limit) else dao.searchLiveInDocument(match, documentId, limit)
                for (r in found) rows.putIfAbsent(r.rowId, r)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            // A MATCH expression the SQLite build rejects: fall back to a plain substring scan.
            Log.w(TAG, "Full-text query rejected, using substring search: ${e.javaClass.simpleName}")
            try {
                val needle = FtsQuery.escapeLike(raw)
                val found = if (documentId == null) dao.searchLiveLike(needle, limit) else dao.searchLiveLikeInDocument(needle, documentId, limit)
                for (r in found) rows.putIfAbsent(r.rowId, r)
            } catch (c: CancellationException) {
                throw c
            } catch (e2: Exception) {
                Log.w(TAG, "Substring search failed: ${e2.javaClass.simpleName}")
            }
        }
        return rows.values.toList()
    }

    private suspend fun semanticCandidates(raw: String, docs: Map<String, DocInfo>): List<Candidate> {
        val model = semantic.model() ?: return emptyList()
        val vector = semantic.embed(raw) ?: throw IllegalStateException("No embedding returned")
        val near = semantic.nearest(model, vector, SEMANTIC_K, MIN_SIMILARITY, docs.keys)
        if (near.isEmpty()) return emptyList()
        val sims = near.toMap()
        val rows = near.map { it.first }.chunked(SQL_IN_CHUNK).flatMap { graph.db.textIndex().liveRowsByIds(it) }
        return rows.mapNotNull { r -> toCandidate(r, docs)?.copy(semantic = sims[r.rowId]) }
    }

    private fun toCandidate(r: TextSearchRow, docs: Map<String, DocInfo>): Candidate? {
        if (r.documentId !in docs) return null
        val source = runCatching { TextSource.valueOf(r.source) }.getOrNull() ?: return null
        // Rows of pages that were deleted since they were indexed.
        if (r.pageId != null && r.pageId != ReadingOrder.TYPED_PAGE && r.pageIndex == null) return null
        return Candidate(
            key = Candidate.keyForRow(r.rowId, r.documentId, source, r.text),
            documentId = r.documentId,
            pageId = r.pageId,
            pageIndex = r.pageIndex,
            recordingId = r.recordingId,
            timestampMs = r.timestampMs,
            source = source,
            text = r.text,
            confidence = r.confidence,
            bounds = r.bounds,
        )
    }

    private suspend fun folderPath(folderId: String?, folders: Map<String, FolderPaths.Node>, cache: MutableMap<String, String>): String {
        if (folderId == null) return ""
        cache[folderId]?.let { return it }
        val fromLibrary = try {
            graph.library.folderPath(folderId)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            ""
        }
        val path = fromLibrary.ifBlank { FolderPaths.path(folders, folderId) }
        cache[folderId] = path
        return path
    }

    companion object {
        private const val TAG = "Search"
        private const val MAX_QUERY_CHARS = 256
        private const val MIN_FETCH = 120
        private const val MAX_FETCH = 600
        private const val SEMANTIC_K = 30
        private const val MIN_SIMILARITY = 0.25f
        private const val SQL_IN_CHUNK = 500

        fun DocumentEntity.toInfo(): DocInfo {
            val m = toModel()
            return DocInfo(m.id, m.title, m.kind, m.folderId, m.modifiedAt, m.thumbnailPath, m.tags)
        }
    }
}
