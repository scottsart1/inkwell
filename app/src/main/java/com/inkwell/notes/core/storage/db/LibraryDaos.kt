package com.inkwell.notes.core.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.inkwell.notes.core.model.AutoNameStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE trashedAt IS NULL ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun getAllIncludingTrashed(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun get(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE parentId IS :parentId AND trashedAt IS NULL ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun children(parentId: String?): List<FolderEntity>

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents")
    suspend fun getAll(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observe(id: String): Flow<DocumentEntity?>

    @Query("SELECT title FROM documents WHERE title LIKE :prefix || '%'")
    suspend fun titlesWithPrefix(prefix: String): List<String>

    @Query("SELECT * FROM documents WHERE autoNameStatus IN (:statuses) AND trashedAt IS NULL")
    suspend fun withAutoNameStatus(statuses: List<AutoNameStatus>): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE trashedAt IS NOT NULL AND trashedAt < :before")
    suspend fun trashedBefore(before: Long): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(doc: DocumentEntity)

    @Upsert
    suspend fun upsert(doc: DocumentEntity)

    @Update
    suspend fun update(doc: DocumentEntity)

    @Query("UPDATE documents SET modifiedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("UPDATE documents SET lastPageIndex = :page, lastZoom = :zoom, lastScrollX = :sx, lastScrollY = :sy, openedAt = :at WHERE id = :id")
    suspend fun updateViewState(id: String, page: Int, zoom: Float, sx: Float, sy: Float, at: Long)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: String)

    // ---- Added by core/storage

    @Query("SELECT title FROM documents WHERE id != :excludeId")
    suspend fun titlesExcept(excludeId: String): List<String>

    @Query("SELECT * FROM documents WHERE trashedAt IS NOT NULL")
    suspend fun trashed(): List<DocumentEntity>

    @Query("SELECT COUNT(*) FROM documents WHERE trashedAt IS NULL")
    suspend fun countLive(): Int

    /** Changes the naming state only while it still is one of [expected] (never overrides a user rename). */
    @Query("UPDATE documents SET autoNameStatus = :status, autoNameAttempts = :attempts WHERE id = :id AND autoNameStatus IN (:expected)")
    suspend fun updateAutoNameState(id: String, status: AutoNameStatus, attempts: Int, expected: List<AutoNameStatus>): Int

    @Query("UPDATE documents SET sizeBytes = :sizeBytes WHERE id = :id")
    suspend fun setSize(id: String, sizeBytes: Long)

    @Query("UPDATE documents SET thumbnailPath = :path WHERE id = :id")
    suspend fun setThumbnail(id: String, path: String?)

    // ---- Added by export/sync

    /** Records a copy to the sync destinations; touches nothing else and never re-creates a deleted row. */
    @Query("UPDATE documents SET lastSyncedAt = :at WHERE id = :id")
    suspend fun setLastSyncedAt(id: String, at: Long)
}

@Dao
interface PageDao {
    @Query("SELECT * FROM pages WHERE documentId = :docId ORDER BY pageIndex")
    fun observeForDocument(docId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :docId ORDER BY pageIndex")
    suspend fun forDocument(docId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun get(id: String): PageEntity?

    @Query("SELECT COUNT(*) FROM pages WHERE documentId = :docId")
    suspend fun count(docId: String): Int

    @Query("SELECT * FROM pages WHERE indexedAt < modifiedAt")
    suspend fun needingIndex(): List<PageEntity>

    @Upsert
    suspend fun upsert(page: PageEntity)

    @Upsert
    suspend fun upsertAll(pages: List<PageEntity>)

    @Query("UPDATE pages SET indexedAt = :at WHERE id = :id")
    suspend fun markIndexed(id: String, at: Long)

    @Query("UPDATE pages SET modifiedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun delete(id: String)

    @Transaction
    suspend fun replaceOrder(pages: List<PageEntity>) {
        upsertAll(pages.mapIndexed { i, p -> p.copy(pageIndex = i) })
    }
}

@Dao
interface OutlineDao {
    @Query("SELECT * FROM outline WHERE documentId = :docId ORDER BY sortOrder")
    fun observe(docId: String): Flow<List<OutlineEntity>>

    @Query("SELECT * FROM outline WHERE documentId = :docId ORDER BY sortOrder")
    suspend fun forDocument(docId: String): List<OutlineEntity>

    @Upsert
    suspend fun upsert(entry: OutlineEntity)

    @Query("DELETE FROM outline WHERE id = :id")
    suspend fun delete(id: String)

    // ---- Added by core/storage

    @Query("SELECT * FROM outline WHERE id = :id")
    suspend fun get(id: String): OutlineEntity?
}
