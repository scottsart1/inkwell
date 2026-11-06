package com.inkwell.notes.core.storage

import androidx.room.InvalidationTracker
import com.inkwell.notes.core.model.AutoNameStatus
import com.inkwell.notes.core.storage.db.AiUsageDao
import com.inkwell.notes.core.storage.db.AppDatabase
import com.inkwell.notes.core.storage.db.ChatDao
import com.inkwell.notes.core.storage.db.ChatMessageEntity
import com.inkwell.notes.core.storage.db.DocumentDao
import com.inkwell.notes.core.storage.db.DocumentEntity
import com.inkwell.notes.core.storage.db.EmbeddingDao
import com.inkwell.notes.core.storage.db.EmbeddingEntity
import com.inkwell.notes.core.storage.db.FolderDao
import com.inkwell.notes.core.storage.db.FolderEntity
import com.inkwell.notes.core.storage.db.OutlineDao
import com.inkwell.notes.core.storage.db.OutlineEntity
import com.inkwell.notes.core.storage.db.PageDao
import com.inkwell.notes.core.storage.db.PageEntity
import com.inkwell.notes.core.storage.db.RecordingDao
import com.inkwell.notes.core.storage.db.RecordingEntity
import com.inkwell.notes.core.storage.db.ReusableElementDao
import com.inkwell.notes.core.storage.db.SyncQueueDao
import com.inkwell.notes.core.storage.db.TextIndexDao
import com.inkwell.notes.core.storage.db.TextIndexEntity
import com.inkwell.notes.core.storage.db.TextMatchRow
import com.inkwell.notes.core.storage.db.TextSearchRow
import com.inkwell.notes.core.storage.db.TranscriptDao
import com.inkwell.notes.core.storage.db.TranscriptSegmentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * An in-memory [AppDatabase] for JVM tests of the repository: plain maps behind the DAOs the
 * library uses, SQL-like semantics for the bits that matter (INSERT aborts on a duplicate key,
 * UPDATE touches only existing rows, document deletes cascade to pages/outline/recordings),
 * and `withTransaction` support with rollback on failure via snapshots.
 *
 * Room's `withTransaction` only needs an executor plus begin/success/end, all of which are
 * open in [androidx.room.RoomDatabase], so no SQLite is involved.
 */
class FakeAppDatabase : AppDatabase() {
    private val lock = Any()
    private val executor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-room-tx").apply { isDaemon = true } }

    class Table<T : Any>(private val key: (T) -> Any) {
        val rows = LinkedHashMap<Any, T>()
        val version = MutableStateFlow(0)
        fun bump() { version.value = version.value + 1 }
        fun all(): List<T> = rows.values.toList()
        fun get(k: Any): T? = rows[k]
        fun insert(row: T) {
            if (key(row) in rows) throw IllegalStateException("UNIQUE constraint failed: ${key(row)}")
            rows[key(row)] = row
            bump()
        }
        fun upsert(row: T) { rows[key(row)] = row; bump() }
        fun update(row: T): Int = if (key(row) in rows) { rows[key(row)] = row; bump(); 1 } else 0
        fun delete(k: Any): Int = if (rows.remove(k) != null) { bump(); 1 } else 0
        fun removeIf(pred: (T) -> Boolean): Int {
            val before = rows.size
            rows.values.removeIf(pred)
            if (rows.size != before) bump()
            return before - rows.size
        }
        fun replace(k: Any, transform: (T) -> T): Int { val cur = rows[k] ?: return 0; rows[k] = transform(cur); bump(); return 1 }
        fun snapshot(): LinkedHashMap<Any, T> = LinkedHashMap(rows)
        fun restore(s: LinkedHashMap<Any, T>) { rows.clear(); rows.putAll(s); bump() }
        fun <R> observe(query: () -> R): Flow<R> = version.map { query() }.distinctUntilChanged()
    }

    val folderTable = Table<FolderEntity> { it.id }
    val documentTable = Table<DocumentEntity> { it.id }
    val pageTable = Table<PageEntity> { it.id }
    val outlineTable = Table<OutlineEntity> { it.id }
    val recordingTable = Table<RecordingEntity> { it.id }
    val transcriptTable = Table<TranscriptSegmentEntity> { it.rowId }
    val textIndexTable = Table<TextIndexEntity> { it.rowId }
    val embeddingTable = Table<EmbeddingEntity> { it.rowId }
    val chatTable = Table<ChatMessageEntity> { it.id }
    private var nextRowId = 1L

    /** Every table in one list so transactions can snapshot and restore them. */
    private val tables: List<Table<*>> = listOf(
        folderTable, documentTable, pageTable, outlineTable, recordingTable, transcriptTable, textIndexTable, embeddingTable, chatTable,
    )

    // ---- transactions

    private var depth = 0
    private var successful = false
    private var snapshots: List<LinkedHashMap<Any, Any>>? = null
    var transactionsBegun = 0
        private set
    var transactionsRolledBack = 0
        private set

    override val transactionExecutor: Executor get() = executor

    override fun beginTransaction() {
        synchronized(lock) {
            if (depth == 0) {
                @Suppress("UNCHECKED_CAST")
                snapshots = tables.map { it.snapshot() as LinkedHashMap<Any, Any> }
                successful = false
                transactionsBegun++
            }
            depth++
        }
    }

    override fun setTransactionSuccessful() {
        synchronized(lock) { successful = true }
    }

    override fun endTransaction() {
        synchronized(lock) {
            depth--
            if (depth == 0) {
                if (!successful) {
                    val s = snapshots!!
                    @Suppress("UNCHECKED_CAST")
                    tables.forEachIndexed { i, t -> (t as Table<Any>).restore(s[i]) }
                    transactionsRolledBack++
                }
                snapshots = null
            }
        }
    }

    override fun createInvalidationTracker(): InvalidationTracker = throw UnsupportedOperationException("not needed in tests")
    override fun clearAllTables() { synchronized(lock) { tables.forEach { (it as Table<Any>).restore(LinkedHashMap()) } } }

    private fun <R> tx(block: () -> R): R = synchronized(lock) { block() }

    // ---- SQL helpers

    /** SQLite LIKE: case-insensitive for ASCII, '%' any run, '_' one character. */
    private fun like(value: String, pattern: String): Boolean {
        val sb = StringBuilder("^")
        for (c in pattern) when (c) {
            '%' -> sb.append(".*")
            '_' -> sb.append('.')
            else -> sb.append(Regex.escape(c.toString()))
        }
        sb.append('$')
        return Regex(sb.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).matches(value)
    }

    private val nocase = compareBy<String> { it.lowercase() }

    // ---- DAOs

    private val folderDao = object : FolderDao {
        private fun live() = folderTable.all().filter { it.trashedAt == null }.sortedWith(compareBy<FolderEntity> { it.sortOrder }.thenBy(nocase) { it.name })
        override fun observeAll(): Flow<List<FolderEntity>> = folderTable.observe { tx { live() } }
        override suspend fun getAllIncludingTrashed(): List<FolderEntity> = tx { folderTable.all().sortedWith(compareBy<FolderEntity> { it.sortOrder }.thenBy(nocase) { it.name }) }
        override suspend fun get(id: String): FolderEntity? = tx { folderTable.get(id) }
        override suspend fun children(parentId: String?): List<FolderEntity> = tx { live().filter { it.parentId == parentId } }
        override suspend fun upsert(folder: FolderEntity) = tx { folderTable.upsert(folder) }
        override suspend fun delete(id: String) { tx { folderTable.delete(id) } }
    }

    private val documentDao = object : DocumentDao {
        override fun observeAll(): Flow<List<DocumentEntity>> = documentTable.observe { tx { documentTable.all() } }
        override suspend fun getAll(): List<DocumentEntity> = tx { documentTable.all() }
        override suspend fun get(id: String): DocumentEntity? = tx { documentTable.get(id) }
        override fun observe(id: String): Flow<DocumentEntity?> = documentTable.observe { tx { documentTable.get(id) } }
        override suspend fun titlesWithPrefix(prefix: String): List<String> = tx { documentTable.all().map { it.title }.filter { like(it, "$prefix%") } }
        override suspend fun withAutoNameStatus(statuses: List<AutoNameStatus>): List<DocumentEntity> =
            tx { documentTable.all().filter { it.autoNameStatus in statuses && it.trashedAt == null } }
        override suspend fun trashedBefore(before: Long): List<DocumentEntity> =
            tx { documentTable.all().filter { it.trashedAt != null && it.trashedAt < before } }
        override suspend fun insert(doc: DocumentEntity) = tx { documentTable.insert(doc) }
        override suspend fun upsert(doc: DocumentEntity) = tx { documentTable.upsert(doc) }
        override suspend fun update(doc: DocumentEntity) { tx { documentTable.update(doc) } }
        override suspend fun touch(id: String, at: Long) { tx { documentTable.replace(id) { it.copy(modifiedAt = at) } } }
        override suspend fun updateViewState(id: String, page: Int, zoom: Float, sx: Float, sy: Float, at: Long) {
            tx { documentTable.replace(id) { it.copy(lastPageIndex = page, lastZoom = zoom, lastScrollX = sx, lastScrollY = sy, openedAt = at) } }
        }
        override suspend fun delete(id: String) {
            tx {
                if (documentTable.delete(id) > 0) {
                    pageTable.removeIf { it.documentId == id }
                    outlineTable.removeIf { it.documentId == id }
                    val recs = recordingTable.all().filter { it.documentId == id }.map { it.id }.toSet()
                    recordingTable.removeIf { it.documentId == id }
                    transcriptTable.removeIf { it.recordingId in recs }
                }
            }
        }
        override suspend fun titlesExcept(excludeId: String): List<String> = tx { documentTable.all().filter { it.id != excludeId }.map { it.title } }
        override suspend fun trashed(): List<DocumentEntity> = tx { documentTable.all().filter { it.trashedAt != null } }
        override suspend fun countLive(): Int = tx { documentTable.all().count { it.trashedAt == null } }
        override suspend fun updateAutoNameState(id: String, status: AutoNameStatus, attempts: Int, expected: List<AutoNameStatus>): Int = tx {
            val cur = documentTable.get(id)
            if (cur != null && cur.autoNameStatus in expected) documentTable.update(cur.copy(autoNameStatus = status, autoNameAttempts = attempts)) else 0
        }
        override suspend fun setSize(id: String, sizeBytes: Long) { tx { documentTable.replace(id) { it.copy(sizeBytes = sizeBytes) } } }
        override suspend fun setThumbnail(id: String, path: String?) { tx { documentTable.replace(id) { it.copy(thumbnailPath = path) } } }
        override suspend fun setLastSyncedAt(id: String, at: Long) { tx { documentTable.replace(id) { it.copy(lastSyncedAt = at) } } }
    }

    private val pageDao = object : PageDao {
        private fun ofDoc(docId: String) = pageTable.all().filter { it.documentId == docId }.sortedBy { it.pageIndex }
        override fun observeForDocument(docId: String): Flow<List<PageEntity>> = pageTable.observe { tx { ofDoc(docId) } }
        override suspend fun forDocument(docId: String): List<PageEntity> = tx { ofDoc(docId) }
        override suspend fun get(id: String): PageEntity? = tx { pageTable.get(id) }
        override suspend fun count(docId: String): Int = tx { pageTable.all().count { it.documentId == docId } }
        override suspend fun needingIndex(): List<PageEntity> = tx { pageTable.all().filter { it.indexedAt < it.modifiedAt } }
        override suspend fun upsert(page: PageEntity) = tx {
            check(documentTable.get(page.documentId) != null) { "FOREIGN KEY constraint failed: page ${page.id} -> ${page.documentId}" }
            pageTable.upsert(page)
        }
        override suspend fun upsertAll(pages: List<PageEntity>) = tx { pages.forEach { p ->
            check(documentTable.get(p.documentId) != null) { "FOREIGN KEY constraint failed: page ${p.id} -> ${p.documentId}" }
            pageTable.upsert(p)
        } }
        override suspend fun markIndexed(id: String, at: Long) { tx { pageTable.replace(id) { it.copy(indexedAt = at) } } }
        override suspend fun touch(id: String, at: Long) { tx { pageTable.replace(id) { it.copy(modifiedAt = at) } } }
        override suspend fun delete(id: String) { tx { pageTable.delete(id) } }
    }

    private val outlineDao = object : OutlineDao {
        private fun ofDoc(docId: String) = outlineTable.all().filter { it.documentId == docId }.sortedBy { it.sortOrder }
        override fun observe(docId: String): Flow<List<OutlineEntity>> = outlineTable.observe { tx { ofDoc(docId) } }
        override suspend fun forDocument(docId: String): List<OutlineEntity> = tx { ofDoc(docId) }
        override suspend fun upsert(entry: OutlineEntity) = tx {
            check(documentTable.get(entry.documentId) != null) { "FOREIGN KEY constraint failed: outline ${entry.id}" }
            outlineTable.upsert(entry)
        }
        override suspend fun delete(id: String) { tx { outlineTable.delete(id) } }
        override suspend fun get(id: String): OutlineEntity? = tx { outlineTable.get(id) }
    }

    private val recordingDao = object : RecordingDao {
        override fun observeForDocument(docId: String): Flow<List<RecordingEntity>> = recordingTable.observe { tx { recordingTable.all().filter { it.documentId == docId }.sortedBy { it.startedAt } } }
        override suspend fun forDocument(docId: String): List<RecordingEntity> = tx { recordingTable.all().filter { it.documentId == docId }.sortedBy { it.startedAt } }
        override suspend fun get(id: String): RecordingEntity? = tx { recordingTable.get(id) }
        override suspend fun unfinished(): List<RecordingEntity> = tx { recordingTable.all().filter { it.status.name == "RECORDING" || it.status.name == "PAUSED" } }
        override suspend fun upsert(r: RecordingEntity) = tx {
            check(documentTable.get(r.documentId) != null) { "FOREIGN KEY constraint failed: recording ${r.id}" }
            recordingTable.upsert(r)
        }
        override suspend fun delete(id: String) { tx { if (recordingTable.delete(id) > 0) transcriptTable.removeIf { it.recordingId == id } } }
        override fun observe(id: String): Flow<RecordingEntity?> = recordingTable.observe { tx { recordingTable.get(id) } }
        override suspend fun countForDocument(docId: String): Int = tx { recordingTable.all().count { it.documentId == docId } }
        override suspend fun rename(id: String, title: String) { tx { recordingTable.replace(id) { it.copy(title = title) } } }
        override suspend fun setTranscriptStatus(id: String, status: String) = throw UnsupportedOperationException()
        override suspend fun setTranscriptStatusIf(id: String, from: List<String>, to: String): Int = throw UnsupportedOperationException()
    }

    private val transcriptDao = object : TranscriptDao {
        override fun observe(recordingId: String): Flow<List<TranscriptSegmentEntity>> = transcriptTable.observe { tx { transcriptTable.all().filter { it.recordingId == recordingId }.sortedBy { it.startMs } } }
        override suspend fun forRecording(recordingId: String): List<TranscriptSegmentEntity> = tx { transcriptTable.all().filter { it.recordingId == recordingId }.sortedBy { it.startMs } }
        override suspend fun insertAll(segments: List<TranscriptSegmentEntity>) = tx { segments.forEach { transcriptTable.insert(if (it.rowId == 0L) it.copy(rowId = nextRowId++) else it) } }
        override suspend fun deleteForRecording(recordingId: String) { tx { transcriptTable.removeIf { it.recordingId == recordingId } } }
    }

    private val textIndexDao = object : TextIndexDao {
        override suspend fun search(ftsQuery: String, limit: Int): List<TextMatchRow> = throw UnsupportedOperationException()
        override suspend fun forDocument(docId: String): List<TextIndexEntity> =
            tx { textIndexTable.all().filter { it.documentId == docId }.sortedWith(compareBy<TextIndexEntity> { it.pageId ?: "" }.thenBy { it.rowId }) }
        override suspend fun byIds(rowIds: List<Long>): List<TextIndexEntity> = tx { textIndexTable.all().filter { it.rowId in rowIds } }
        override suspend fun insertAll(rows: List<TextIndexEntity>): List<Long> = tx {
            rows.map { r -> val id = if (r.rowId == 0L) nextRowId++ else r.rowId; textIndexTable.insert(r.copy(rowId = id)); id }
        }
        override suspend fun deleteFor(docId: String, pageId: String?, source: String) {
            tx { textIndexTable.removeIf { it.documentId == docId && it.pageId == pageId && it.source.name == source } }
        }
        override suspend fun deleteForRecording(recordingId: String) { tx { textIndexTable.removeIf { it.recordingId == recordingId } } }
        override suspend fun deleteDocument(docId: String) { tx { textIndexTable.removeIf { it.documentId == docId } } }
        override suspend fun searchLive(ftsQuery: String, limit: Int): List<TextSearchRow> = throw UnsupportedOperationException()
        override suspend fun searchLiveLike(needle: String, limit: Int): List<TextSearchRow> = throw UnsupportedOperationException()
        override suspend fun searchLiveInDocument(ftsQuery: String, docId: String, limit: Int): List<TextSearchRow> = throw UnsupportedOperationException()
        override suspend fun searchLiveLikeInDocument(needle: String, docId: String, limit: Int): List<TextSearchRow> = throw UnsupportedOperationException()
        override suspend fun liveRowsByIds(rowIds: List<Long>): List<TextSearchRow> = throw UnsupportedOperationException()
        override suspend fun lastUpdated(docId: String, source: String): Long? =
            tx { textIndexTable.all().filter { it.documentId == docId && it.source.name == source }.maxOfOrNull { it.updatedAt } }
        override suspend fun withoutEmbedding(model: String, limit: Int): List<TextIndexEntity> = throw UnsupportedOperationException()
        override suspend fun documentsMissingTitle(): List<String> = throw UnsupportedOperationException()
        override suspend fun deleteOrphans(): Int = throw UnsupportedOperationException()
        override suspend fun count(): Int = tx { textIndexTable.rows.size }
        override suspend fun pagesNeverIndexed(limit: Int): List<PageEntity> = throw UnsupportedOperationException()
    }

    private val embeddingDao = object : EmbeddingDao {
        override suspend fun all(model: String): List<EmbeddingEntity> = tx { embeddingTable.all().filter { it.model == model } }
        override suspend fun insertAll(rows: List<EmbeddingEntity>) = tx { rows.forEach { embeddingTable.insert(if (it.rowId == 0L) it.copy(rowId = nextRowId++) else it) } }
        override suspend fun deleteDocument(docId: String) { tx { embeddingTable.removeIf { it.documentId == docId } } }
        override suspend fun embeddedRowIds(model: String): List<Long> = tx { embeddingTable.all().filter { it.model == model }.map { it.textRowId } }
        override suspend fun page(model: String, afterRowId: Long, limit: Int): List<EmbeddingEntity> = throw UnsupportedOperationException()
        override suspend fun forDocument(model: String, docId: String): List<EmbeddingEntity> = tx { embeddingTable.all().filter { it.model == model && it.documentId == docId } }
        override suspend fun deleteStaleForDocument(docId: String) = throw UnsupportedOperationException()
        override suspend fun deleteOrphans(): Int = throw UnsupportedOperationException()
    }

    private val chatDao = object : ChatDao {
        override fun observeThread(threadId: String): Flow<List<ChatMessageEntity>> = chatTable.observe { tx { chatTable.all().filter { it.threadId == threadId }.sortedBy { it.createdAt } } }
        override suspend fun threadsForDocument(docId: String): List<String> =
            tx { chatTable.all().filter { it.documentId == docId }.sortedByDescending { it.createdAt }.map { it.threadId }.distinct() }
        override suspend fun upsert(msg: ChatMessageEntity) = tx { chatTable.upsert(msg) }
        override suspend fun deleteThread(threadId: String) { tx { chatTable.removeIf { it.threadId == threadId } } }
        override suspend fun deleteMessage(id: String) { tx { chatTable.delete(id) } }
    }

    override fun folders(): FolderDao = folderDao
    override fun documents(): DocumentDao = documentDao
    override fun pages(): PageDao = pageDao
    override fun outline(): OutlineDao = outlineDao
    override fun recordings(): RecordingDao = recordingDao
    override fun transcripts(): TranscriptDao = transcriptDao
    override fun textIndex(): TextIndexDao = textIndexDao
    override fun embeddings(): EmbeddingDao = embeddingDao
    override fun aiUsage(): AiUsageDao = throw UnsupportedOperationException("not needed in tests")
    override fun chat(): ChatDao = chatDao
    override fun syncQueue(): SyncQueueDao = throw UnsupportedOperationException("not needed in tests")
    override fun reusableElements(): ReusableElementDao = throw UnsupportedOperationException("not needed in tests")
    override fun speakers(): com.inkwell.notes.core.storage.db.SpeakerDao = throw UnsupportedOperationException("not needed in tests")
}
