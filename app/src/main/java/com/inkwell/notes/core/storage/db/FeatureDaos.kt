package com.inkwell.notes.core.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings WHERE documentId = :docId ORDER BY startedAt")
    fun observeForDocument(docId: String): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE documentId = :docId ORDER BY startedAt")
    suspend fun forDocument(docId: String): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: String): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE status IN ('RECORDING','PAUSED')")
    suspend fun unfinished(): List<RecordingEntity>

    @Upsert
    suspend fun upsert(r: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: String)

    // ---- audio feature additions

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observe(id: String): Flow<RecordingEntity?>

    @Query("SELECT COUNT(*) FROM recordings WHERE documentId = :docId")
    suspend fun countForDocument(docId: String): Int

    @Query("UPDATE recordings SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    /** Sets the transcript status; [status] is a TranscriptStatus name. */
    @Query("UPDATE recordings SET transcriptStatus = :status WHERE id = :id")
    suspend fun setTranscriptStatus(id: String, status: String)

    /** Compare-and-set of the transcript status (names); returns the number of rows changed. */
    @Query("UPDATE recordings SET transcriptStatus = :to WHERE id = :id AND transcriptStatus IN (:from)")
    suspend fun setTranscriptStatusIf(id: String, from: List<String>, to: String): Int

    /** Read-modify-write of one recording in a transaction, so concurrent renames are never lost. */
    @Transaction
    suspend fun mutate(id: String, transform: (RecordingEntity) -> RecordingEntity): RecordingEntity? {
        val current = get(id) ?: return null
        val next = transform(current)
        if (next != current) upsert(next)
        return next
    }
}

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY startMs")
    fun observe(recordingId: String): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY startMs")
    suspend fun forRecording(recordingId: String): List<TranscriptSegmentEntity>

    @Insert
    suspend fun insertAll(segments: List<TranscriptSegmentEntity>)

    @Query("DELETE FROM transcript_segments WHERE recordingId = :recordingId")
    suspend fun deleteForRecording(recordingId: String)

    @Transaction
    suspend fun replace(recordingId: String, segments: List<TranscriptSegmentEntity>) {
        deleteForRecording(recordingId)
        insertAll(segments)
    }
}

/** Row returned by full-text search. */
data class TextMatchRow(
    val rowId: Long,
    val documentId: String,
    val pageId: String?,
    val recordingId: String?,
    val source: String,
    val text: String,
    val confidence: Float?,
    val timestampMs: Long?,
    val bounds: String?,
)

/** Full-text match with the index of its page (null for transcripts, typed text and titles). */
data class TextSearchRow(
    val rowId: Long,
    val documentId: String,
    val pageId: String?,
    val recordingId: String?,
    val source: String,
    val text: String,
    val confidence: Float?,
    val timestampMs: Long?,
    val bounds: String?,
    val pageIndex: Int?,
)

@Dao
interface TextIndexDao {
    @Query(
        """SELECT t.rowId, t.documentId, t.pageId, t.recordingId, t.source, t.text, t.confidence, t.timestampMs, t.bounds
           FROM text_index t JOIN text_index_fts f ON t.rowId = f.rowid
           WHERE text_index_fts MATCH :ftsQuery LIMIT :limit"""
    )
    suspend fun search(ftsQuery: String, limit: Int): List<TextMatchRow>

    @Query("SELECT * FROM text_index WHERE documentId = :docId ORDER BY pageId, rowId")
    suspend fun forDocument(docId: String): List<TextIndexEntity>

    @Query("SELECT * FROM text_index WHERE rowId IN (:rowIds)")
    suspend fun byIds(rowIds: List<Long>): List<TextIndexEntity>

    @Insert
    suspend fun insertAll(rows: List<TextIndexEntity>): List<Long>

    @Query("DELETE FROM text_index WHERE documentId = :docId AND pageId IS :pageId AND source = :source")
    suspend fun deleteFor(docId: String, pageId: String?, source: String)

    @Query("DELETE FROM text_index WHERE recordingId = :recordingId")
    suspend fun deleteForRecording(recordingId: String)

    @Query("DELETE FROM text_index WHERE documentId = :docId")
    suspend fun deleteDocument(docId: String)

    @Transaction
    suspend fun replace(docId: String, pageId: String?, source: String, rows: List<TextIndexEntity>): List<Long> {
        deleteFor(docId, pageId, source)
        return insertAll(rows)
    }

    // ---- ocr/search additions

    /** Full-text matches with their page index, most recently edited documents first, excluding the trash. */
    @Query(
        """SELECT t.rowId AS rowId, t.documentId AS documentId, t.pageId AS pageId, t.recordingId AS recordingId,
                  t.source AS source, t.text AS text, t.confidence AS confidence, t.timestampMs AS timestampMs,
                  t.bounds AS bounds, p.pageIndex AS pageIndex
           FROM text_index t
           JOIN documents d ON d.id = t.documentId
           LEFT JOIN pages p ON p.id = t.pageId
           WHERE t.rowId IN (SELECT rowid FROM text_index_fts WHERE text_index_fts MATCH :ftsQuery)
             AND d.trashedAt IS NULL
           ORDER BY d.modifiedAt DESC, t.rowId
           LIMIT :limit"""
    )
    suspend fun searchLive(ftsQuery: String, limit: Int): List<TextSearchRow>

    /** Substring fallback when a MATCH expression is rejected; [needle] is LIKE-escaped with '\'. */
    @Query(
        """SELECT t.rowId AS rowId, t.documentId AS documentId, t.pageId AS pageId, t.recordingId AS recordingId,
                  t.source AS source, t.text AS text, t.confidence AS confidence, t.timestampMs AS timestampMs,
                  t.bounds AS bounds, p.pageIndex AS pageIndex
           FROM text_index t
           JOIN documents d ON d.id = t.documentId
           LEFT JOIN pages p ON p.id = t.pageId
           WHERE t.text LIKE '%' || :needle || '%' ESCAPE '\' AND d.trashedAt IS NULL
           ORDER BY d.modifiedAt DESC, t.rowId
           LIMIT :limit"""
    )
    suspend fun searchLiveLike(needle: String, limit: Int): List<TextSearchRow>

    /** [searchLive] within one document (in-document search: the filter applies before the limit). */
    @Query(
        """SELECT t.rowId AS rowId, t.documentId AS documentId, t.pageId AS pageId, t.recordingId AS recordingId,
                  t.source AS source, t.text AS text, t.confidence AS confidence, t.timestampMs AS timestampMs,
                  t.bounds AS bounds, p.pageIndex AS pageIndex
           FROM text_index t
           JOIN documents d ON d.id = t.documentId
           LEFT JOIN pages p ON p.id = t.pageId
           WHERE t.rowId IN (SELECT rowid FROM text_index_fts WHERE text_index_fts MATCH :ftsQuery)
             AND t.documentId = :docId AND d.trashedAt IS NULL
           ORDER BY t.rowId
           LIMIT :limit"""
    )
    suspend fun searchLiveInDocument(ftsQuery: String, docId: String, limit: Int): List<TextSearchRow>

    /** [searchLiveLike] within one document. */
    @Query(
        """SELECT t.rowId AS rowId, t.documentId AS documentId, t.pageId AS pageId, t.recordingId AS recordingId,
                  t.source AS source, t.text AS text, t.confidence AS confidence, t.timestampMs AS timestampMs,
                  t.bounds AS bounds, p.pageIndex AS pageIndex
           FROM text_index t
           JOIN documents d ON d.id = t.documentId
           LEFT JOIN pages p ON p.id = t.pageId
           WHERE t.text LIKE '%' || :needle || '%' ESCAPE '\' AND t.documentId = :docId AND d.trashedAt IS NULL
           ORDER BY t.rowId
           LIMIT :limit"""
    )
    suspend fun searchLiveLikeInDocument(needle: String, docId: String, limit: Int): List<TextSearchRow>

    /** Rows by id with their page index (semantic search results). */
    @Query(
        """SELECT t.rowId AS rowId, t.documentId AS documentId, t.pageId AS pageId, t.recordingId AS recordingId,
                  t.source AS source, t.text AS text, t.confidence AS confidence, t.timestampMs AS timestampMs,
                  t.bounds AS bounds, p.pageIndex AS pageIndex
           FROM text_index t
           JOIN documents d ON d.id = t.documentId
           LEFT JOIN pages p ON p.id = t.pageId
           WHERE t.rowId IN (:rowIds) AND d.trashedAt IS NULL"""
    )
    suspend fun liveRowsByIds(rowIds: List<Long>): List<TextSearchRow>

    /** Last time [source] text of a document was written (null = never). */
    @Query("SELECT MAX(updatedAt) FROM text_index WHERE documentId = :docId AND source = :source")
    suspend fun lastUpdated(docId: String, source: String): Long?

    /** Content rows of [model] that have no embedding yet, oldest first. */
    @Query(
        """SELECT * FROM text_index
           WHERE source NOT IN ('TITLE', 'TAG', 'FOLDER') AND length(text) >= 3
             AND documentId IN (SELECT id FROM documents WHERE trashedAt IS NULL)
             AND rowId NOT IN (SELECT textRowId FROM embeddings WHERE model = :model)
           ORDER BY rowId LIMIT :limit"""
    )
    suspend fun withoutEmbedding(model: String, limit: Int): List<TextIndexEntity>

    /** Ids of documents (not in the trash) whose title is not indexed yet. */
    @Query(
        """SELECT id FROM documents WHERE trashedAt IS NULL
           AND id NOT IN (SELECT documentId FROM text_index WHERE source = 'TITLE')"""
    )
    suspend fun documentsMissingTitle(): List<String>

    /** Removes rows of documents, pages and recordings that no longer exist. */
    @Query(
        """DELETE FROM text_index WHERE documentId NOT IN (SELECT id FROM documents)
           OR (recordingId IS NOT NULL AND recordingId NOT IN (SELECT id FROM recordings))
           OR (pageId IS NOT NULL AND pageId != 'typed' AND pageId NOT IN (SELECT id FROM pages))"""
    )
    suspend fun deleteOrphans(): Int

    @Query("SELECT COUNT(*) FROM text_index")
    suspend fun count(): Int

    /** Pages (of documents not in the trash) that were never indexed, e.g. imported without a modification time. */
    @Query(
        """SELECT p.* FROM pages p JOIN documents d ON d.id = p.documentId
           WHERE p.indexedAt = 0 AND d.trashedAt IS NULL ORDER BY p.modifiedAt DESC LIMIT :limit"""
    )
    suspend fun pagesNeverIndexed(limit: Int): List<PageEntity>
}

@Dao
interface EmbeddingDao {
    @Query("SELECT * FROM embeddings WHERE model = :model")
    suspend fun all(model: String): List<EmbeddingEntity>

    @Insert
    suspend fun insertAll(rows: List<EmbeddingEntity>)

    @Query("DELETE FROM embeddings WHERE documentId = :docId")
    suspend fun deleteDocument(docId: String)

    @Query("SELECT textRowId FROM embeddings WHERE model = :model")
    suspend fun embeddedRowIds(model: String): List<Long>

    // ---- ocr/search additions

    /** One page of [model]'s vectors after [afterRowId], for streaming similarity search. */
    @Query("SELECT * FROM embeddings WHERE model = :model AND rowId > :afterRowId ORDER BY rowId LIMIT :limit")
    suspend fun page(model: String, afterRowId: Long, limit: Int): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings WHERE model = :model AND documentId = :docId")
    suspend fun forDocument(model: String, docId: String): List<EmbeddingEntity>

    /** Drops vectors of a document's text rows that were replaced or removed. */
    @Query(
        """DELETE FROM embeddings WHERE documentId = :docId
           AND textRowId NOT IN (SELECT rowId FROM text_index WHERE documentId = :docId)"""
    )
    suspend fun deleteStaleForDocument(docId: String)

    @Query("DELETE FROM embeddings WHERE textRowId NOT IN (SELECT rowId FROM text_index)")
    suspend fun deleteOrphans(): Int
}

/** Monthly/total usage aggregates. */
data class UsageTotalRow(val provider: String, val model: String, val inputTokens: Long, val outputTokens: Long, val costUsd: Double?, val requests: Int)

@Dao
interface AiUsageDao {
    @Insert
    suspend fun insert(row: AiUsageEntity)

    @Query("SELECT * FROM ai_usage ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AiUsageEntity>>

    @Query(
        """SELECT provider, model, SUM(inputTokens) AS inputTokens, SUM(outputTokens) AS outputTokens,
           SUM(estimatedCostUsd) AS costUsd, COUNT(*) AS requests
           FROM ai_usage WHERE timestamp >= :since GROUP BY provider, model ORDER BY costUsd DESC"""
    )
    fun observeTotalsSince(since: Long): Flow<List<UsageTotalRow>>

    @Query("SELECT COALESCE(SUM(estimatedCostUsd), 0) FROM ai_usage WHERE timestamp >= :since")
    suspend fun costSince(since: Long): Double

    @Query("DELETE FROM ai_usage")
    suspend fun clear()
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE threadId = :threadId ORDER BY createdAt")
    fun observeThread(threadId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT DISTINCT threadId FROM chat_messages WHERE documentId = :docId ORDER BY createdAt DESC")
    suspend fun threadsForDocument(docId: String): List<String>

    @Upsert
    suspend fun upsert(msg: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE threadId = :threadId")
    suspend fun deleteThread(threadId: String)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteMessage(id: String)
}

@Dao
interface SyncQueueDao {
    @Insert
    suspend fun enqueue(item: SyncQueueEntity): Long

    @Query("SELECT * FROM sync_queue WHERE status = 'PENDING' ORDER BY enqueuedAt LIMIT :limit")
    suspend fun pending(limit: Int): List<SyncQueueEntity>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM sync_queue ORDER BY enqueuedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SyncQueueEntity>>

    @Upsert
    suspend fun update(item: SyncQueueEntity)

    @Query("DELETE FROM sync_queue WHERE status = 'DONE' AND enqueuedAt < :before")
    suspend fun pruneDone(before: Long)

    @Query("DELETE FROM sync_queue WHERE documentId = :docId AND kind = :kind AND target = :target AND status = 'PENDING'")
    suspend fun dropPendingDuplicates(docId: String, kind: String, target: String)

    // ---- Added by sync (export/sync)

    /** Like [dropPendingDuplicates] but per recording ([refId]) and also clears failed rows it supersedes. */
    @Query(
        """DELETE FROM sync_queue WHERE documentId = :docId AND kind = :kind AND target = :target
           AND refId IS :refId AND status IN ('PENDING', 'FAILED')"""
    )
    suspend fun dropOpenDuplicates(docId: String, kind: String, target: String, refId: String?)

    /** Enqueues [item], replacing any open item for the same document, kind, target and recording. */
    @Transaction
    suspend fun enqueueUnique(item: SyncQueueEntity): Long {
        if (item.refId == null) dropPendingDuplicates(item.documentId, item.kind, item.target)
        dropOpenDuplicates(item.documentId, item.kind, item.target, item.refId)
        return enqueue(item)
    }

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    /** Pending items in queue order (enqueuedAt, rowId) after the item ([afterAt], [afterRowId]): one page of the whole queue. */
    @Query(
        """SELECT * FROM sync_queue WHERE status = 'PENDING'
           AND (enqueuedAt > :afterAt OR (enqueuedAt = :afterAt AND rowId > :afterRowId))
           ORDER BY enqueuedAt, rowId LIMIT :limit"""
    )
    suspend fun pendingAfter(afterAt: Long, afterRowId: Long, limit: Int): List<SyncQueueEntity>

    /** One pending item per kind and target: tells which background runs are needed, however long the queue. */
    @Query(
        """SELECT * FROM sync_queue WHERE rowId IN
           (SELECT MIN(rowId) FROM sync_queue WHERE status = 'PENDING' GROUP BY kind, target)"""
    )
    suspend fun pendingKindsAndTargets(): List<SyncQueueEntity>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    /** Records the outcome of one attempt without resurrecting a row that was superseded meanwhile. */
    @Query("UPDATE sync_queue SET status = :status, attempts = :attempts, lastError = :lastError WHERE rowId = :rowId")
    suspend fun setOutcome(rowId: Long, status: String, attempts: Int, lastError: String?)

    @Query("DELETE FROM sync_queue WHERE documentId = :docId AND status IN ('PENDING', 'FAILED')")
    suspend fun dropOpenForDocument(docId: String)

    @Query("DELETE FROM sync_queue WHERE kind = :kind AND target = :target AND status IN ('PENDING', 'FAILED')")
    suspend fun dropOpenFor(kind: String, target: String)

    @Query("UPDATE sync_queue SET status = 'FAILED', lastError = :error WHERE target = :target AND status = 'PENDING'")
    suspend fun failPendingForTarget(target: String, error: String): Int

    /** "Sync now" gives failed items another chance. */
    @Query("UPDATE sync_queue SET status = 'PENDING', attempts = 0, lastError = NULL WHERE status = 'FAILED'")
    suspend fun retryFailed(): Int

    /** Failed items for one destination get another chance (e.g. Google Drive was connected again). */
    @Query("UPDATE sync_queue SET status = 'PENDING', attempts = 0, lastError = NULL WHERE status = 'FAILED' AND target = :target")
    suspend fun retryFailedForTarget(target: String): Int

    /** Removes every row for a destination that no longer exists (the notes vault). */
    @Query("DELETE FROM sync_queue WHERE target = :target")
    suspend fun dropAllForTarget(target: String): Int

    @Query("DELETE FROM sync_queue WHERE status = 'FAILED' AND enqueuedAt < :before")
    suspend fun pruneFailed(before: Long)
}

@Dao
interface ReusableElementDao {
    @Query("SELECT * FROM reusable_elements ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ReusableElementEntity>>

    @Upsert
    suspend fun upsert(e: ReusableElementEntity)

    @Query("DELETE FROM reusable_elements WHERE id = :id")
    suspend fun delete(id: String)
}
