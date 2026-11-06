package com.inkwell.notes.ocr

import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.RecognizedBlock
import com.inkwell.notes.core.model.SearchHit
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.ocr.index.DocumentTextLoader
import com.inkwell.notes.ocr.index.IndexEntry
import com.inkwell.notes.ocr.index.IndexWriter
import com.inkwell.notes.ocr.index.Retriever
import com.inkwell.notes.ocr.index.SearchEngine
import com.inkwell.notes.ocr.index.SearchOutcome
import com.inkwell.notes.ocr.index.SemanticIndex
import com.inkwell.notes.ocr.work.ImageOcrCache
import com.inkwell.notes.search.TranscriptSearchText
import java.io.File

/**
 * The local full-text index (Room FTS4 over `text_index`) of titles, tags, typed text,
 * recognised handwriting, OCR, PDF text and transcripts, with optional semantic search over
 * embeddings. Everything except semantic matching works offline.
 */
class SearchIndexImpl(private val graph: AppGraph) : SearchIndex {
    internal val writer by lazy { IndexWriter(graph.db) }
    internal val semanticIndex by lazy { SemanticIndex(graph) }
    private val loader by lazy { DocumentTextLoader(graph.db) }
    private val engine by lazy { SearchEngine(graph, semanticIndex) }
    private val retriever by lazy { Retriever(graph, loader, semanticIndex) }
    private val imageCache by lazy { ImageOcrCache(File(graph.context.noBackupFilesDir, ImageOcrCache.DIR)) }

    override suspend fun search(query: String, limit: Int, semantic: Boolean): List<SearchHit> =
        engine.search(query, limit, semantic).hits

    override suspend fun searchInDocument(docId: String, query: String, limit: Int): List<SearchHit> =
        engine.search(query, limit, wantSemantic = false, documentId = docId).hits

    /** [search] plus whether semantic matching was used or failed (for the search screen). */
    internal suspend fun searchDetailed(query: String, limit: Int, semantic: Boolean): SearchOutcome =
        engine.search(query, limit, semantic)

    /** True when semantic search can run (user setting + embedding model). */
    internal fun semanticAvailable(): Boolean = semanticIndex.enabled()

    override suspend fun indexDocumentMeta(doc: DocumentMeta) = writer.replaceMeta(doc)

    override suspend fun replacePageText(docId: String, pageId: String, source: TextSource, blocks: List<RecognizedBlock>, engine: String) =
        writer.replacePage(docId, pageId, source, blocks.map { IndexEntry(it.text, it.bounds, it.confidence, engine) })

    /** Blocks are stored as "Name: text" when a person is named, so searching a person finds where they speak. */
    override suspend fun replaceTranscript(docId: String, recordingId: String, segments: List<TranscriptSegment>, engine: String) =
        writer.replaceTranscript(docId, recordingId, TranscriptSearchText.forIndex(segments), engine)

    /** Also forgets the document's recognised image text, which lives outside its folder. */
    override suspend fun removeDocument(docId: String) {
        writer.removeDocument(docId)
        imageCache.removeDocument(docId)
    }

    override suspend fun documentText(docId: String): List<IndexedPassage> = loader.load(docId).map { it.passage }

    override suspend fun retrieve(docId: String?, query: String, maxChars: Int): List<IndexedPassage> =
        retriever.retrieve(docId, query, maxChars)
}
