package com.inkwell.notes.ocr.index

import androidx.room.withTransaction
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.storage.db.AppDatabase
import com.inkwell.notes.core.storage.db.TextIndexEntity
import com.inkwell.notes.ocr.text.Bounds
import com.inkwell.notes.ocr.text.IndexText
import com.inkwell.notes.ocr.text.ReadingOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One piece of text to store, with where it is and how it was obtained. */
internal data class IndexEntry(
    val text: String,
    val bounds: FloatArray? = null,
    val confidence: Float? = null,
    val engine: String,
    val timestampMs: Long? = null,
)

/**
 * Writes the text index. Every replacement is one transaction (old rows of that page and
 * source go, new rows come in, embeddings of replaced rows are dropped), so search never sees
 * a half-updated page.
 */
internal class IndexWriter(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    suspend fun replacePage(docId: String, pageId: String, source: TextSource, entries: List<IndexEntry>) =
        replacePageSources(docId, pageId, mapOf(source to entries))

    /** Replaces several sources of one page in a single transaction. */
    suspend fun replacePageSources(docId: String, pageId: String, sources: Map<TextSource, List<IndexEntry>>) = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext
        val now = clock()
        val rows = sources.mapValues { (source, entries) -> entries.mapNotNull { pageRow(docId, pageId, source, it, now) } }
        db.withTransaction {
            if (db.documents().get(docId) == null) {
                db.textIndex().deleteDocument(docId)
                return@withTransaction
            }
            // The page may have been deleted (or moved) while its text was being recognised.
            if (pageId != ReadingOrder.TYPED_PAGE && db.pages().get(pageId)?.documentId != docId) {
                for (source in TextSource.entries) db.textIndex().deleteFor(docId, pageId, source.name)
                db.embeddings().deleteStaleForDocument(docId)
                return@withTransaction
            }
            for ((source, list) in rows) db.textIndex().replace(docId, pageId, source.name, list)
            db.embeddings().deleteStaleForDocument(docId)
        }
    }

    private fun pageRow(docId: String, pageId: String, source: TextSource, e: IndexEntry, now: Long): TextIndexEntity? {
        val text = clean(e.text) ?: return null
        return TextIndexEntity(
            documentId = docId,
            pageId = pageId,
            recordingId = null,
            source = source,
            text = text,
            confidence = e.confidence?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
            timestampMs = e.timestampMs,
            bounds = Bounds.format(e.bounds),
            engine = e.engine,
            updatedAt = now,
        )
    }

    /**
     * Title and tags; folder names are matched live at search time. They are read again inside
     * the transaction, so a refresh that started before a quick second rename cannot write the
     * older title last.
     */
    suspend fun replaceMeta(doc: DocumentMeta) = withContext(Dispatchers.IO) {
        val now = clock()
        db.withTransaction {
            val current = db.documents().get(doc.id)?.toModel() ?: return@withTransaction
            val rows = ArrayList<TextIndexEntity>()
            clean(current.title)?.let { rows += metaRow(current.id, TextSource.TITLE, it, now) }
            current.tags.mapNotNull { clean(it) }.distinctBy { it.lowercase() }.forEach { rows += metaRow(current.id, TextSource.TAG, it, now) }
            db.textIndex().deleteFor(doc.id, null, TextSource.TITLE.name)
            db.textIndex().deleteFor(doc.id, null, TextSource.TAG.name)
            if (rows.isNotEmpty()) db.textIndex().insertAll(rows)
            db.embeddings().deleteStaleForDocument(doc.id)
        }
    }

    suspend fun replaceTranscript(docId: String, recordingId: String, segments: List<TranscriptSegment>, engine: String) =
        withContext(Dispatchers.IO) {
            val now = clock()
            val rows = segments.sortedBy { it.startMs }.mapNotNull { s ->
                val text = clean(s.text) ?: return@mapNotNull null
                TextIndexEntity(
                    documentId = docId,
                    pageId = null,
                    recordingId = recordingId,
                    source = TextSource.TRANSCRIPT,
                    text = text,
                    confidence = null,
                    timestampMs = s.startMs.coerceAtLeast(0),
                    bounds = null,
                    engine = engine,
                    updatedAt = now,
                )
            }
            db.withTransaction {
                db.textIndex().deleteForRecording(recordingId)
                // The recording may have been deleted while it was transcribed or renamed.
                if (rows.isNotEmpty() && db.documents().get(docId) != null && db.recordings().get(recordingId) != null) {
                    db.textIndex().insertAll(rows)
                }
                db.embeddings().deleteStaleForDocument(docId)
            }
        }

    suspend fun removeDocument(docId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.textIndex().deleteDocument(docId)
            db.embeddings().deleteDocument(docId)
        }
    }

    /** Drops rows and vectors of documents, pages and recordings that no longer exist. */
    suspend fun deleteOrphans() = withContext(Dispatchers.IO) {
        db.textIndex().deleteOrphans()
        db.embeddings().deleteOrphans()
    }

    private fun metaRow(docId: String, source: TextSource, text: String, now: Long) = TextIndexEntity(
        documentId = docId, pageId = null, recordingId = null, source = source, text = text, confidence = null,
        timestampMs = null, bounds = null, engine = "meta", updatedAt = now,
    )

    companion object {
        /** Longest text stored in one row; longer passages are split by the producers. */
        const val MAX_ROW_CHARS = 20_000

        /**
         * Text as stored: NULs removed, typographic punctuation and no-break spaces normalised
         * so the FTS tokenizer splits words where the query does (see [IndexText]), trimmed
         * and capped. Null when nothing is left.
         */
        fun clean(text: String): String? {
            val t = IndexText.normalize(text).trim()
            if (t.isEmpty()) return null
            return IndexText.truncate(t, MAX_ROW_CHARS)
        }
    }
}
