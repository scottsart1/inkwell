package com.inkwell.notes.core.storage

import android.util.Log
import androidx.room.withTransaction
import com.inkwell.notes.core.model.AutoNameStatus
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Folder
import com.inkwell.notes.core.model.LibraryFilter
import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PageTemplate
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.StorageInfo
import com.inkwell.notes.core.naming.AutoNamingRules
import com.inkwell.notes.core.naming.DateNaming
import com.inkwell.notes.core.naming.TitleRules
import com.inkwell.notes.core.settings.SettingsRepository
import com.inkwell.notes.core.storage.db.AppDatabase
import com.inkwell.notes.core.storage.db.DocumentEntity
import com.inkwell.notes.core.storage.db.FolderEntity
import com.inkwell.notes.core.storage.db.OutlineEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Room + files implementation of the library.
 *
 * - Multi-row changes run in one `withTransaction`; reads used to compute a write happen in the
 *   same transaction, so concurrent writers (editor autosave, background naming) never lose updates.
 * - File work runs on [Dispatchers.IO] (see [DocumentFiles]); Room suspend DAOs are main-safe.
 * - modifiedAt changes when what a document *is* changes (content, pages, title, tags), not when
 *   it is filed or displayed differently (folder, favourite, pin, archive, trash, thumbnail, view
 *   state) or renamed automatically, so "Modified" ordering reflects the user's work.
 * - Deleted documents leave no orphans: rows cascade (pages, outline, recordings, transcripts),
 *   index rows, embeddings and chat threads are removed, then the document folder.
 * - Items in the trash for more than [TRASH_RETENTION_MS] are purged by [purgeExpiredTrash],
 *   which the hourly naming worker calls.
 *
 * Automatic naming is not scheduled from here: the repository has no graph or context. The
 * naming service's hourly worker finds PENDING documents itself and also books a precise
 * one-off check for the next naming deadline (see `AutoNamingServiceImpl.schedule`).
 */
class LibraryRepositoryImpl internal constructor(
    private val db: AppDatabase,
    private val files: FileStore,
    private val pages: PageContentStore,
    private val settings: SettingsRepository,
    private val hooks: LibraryHooks,
    private val clock: () -> Long,
) : LibraryRepository {

    constructor(db: AppDatabase, files: FileStore, pages: PageContentStore, settings: SettingsRepository) :
        this(db, files, pages, settings, GraphLibraryHooks, System::currentTimeMillis)

    private val docFiles = DocumentFiles(files, pages)
    private val pageOps = LibraryPages(db, docFiles, settings, hooks, clock)

    // ------------------------------------------------------------------ Folders

    override fun observeFolders(): Flow<List<Folder>> =
        db.folders().observeAll().map { list -> list.map { it.toModel() } }.distinctUntilChanged()

    override suspend fun getFolder(id: String): Folder? = db.folders().get(id)?.toModel()

    override suspend fun createFolder(name: String, parentId: String?, color: Int, icon: String): Folder {
        val now = clock()
        return db.withTransaction {
            val parent = parentId?.let { db.folders().get(it) }?.takeIf { it.trashedAt == null }
            val siblings = db.folders().children(parent?.id).map { it.name }
            val folder = Folder(
                id = UUID.randomUUID().toString(),
                name = DateNaming.unique(TitleRules.sanitize(name) ?: DEFAULT_FOLDER_NAME, siblings),
                parentId = parent?.id,
                color = color,
                icon = icon.ifBlank { DEFAULT_FOLDER_ICON },
                createdAt = now,
                modifiedAt = now,
            )
            db.folders().upsert(FolderEntity.from(folder))
            folder
        }
    }

    override suspend fun updateFolder(folder: Folder) {
        val now = clock()
        val affectedDocs = db.withTransaction {
            val cur = db.folders().get(folder.id) ?: return@withTransaction emptyList()
            val tree = FolderTree(db.folders().getAllIncludingTrashed().map { it.toModel() })
            val parentId = validParent(tree, folder.id, folder.parentId, fallback = cur.parentId)
            val requestedName = TitleRules.sanitize(folder.name) ?: cur.name
            val name = if (requestedName == cur.name && parentId == cur.parentId) cur.name else uniqueSiblingName(requestedName, parentId, folder.id)
            val next = cur.copy(
                name = name, parentId = parentId, color = folder.color,
                icon = folder.icon.ifBlank { cur.icon }, sortOrder = folder.sortOrder, modifiedAt = now,
            )
            if (next == cur.copy(modifiedAt = now)) return@withTransaction emptyList()
            db.folders().upsert(next)
            if (name != cur.name || parentId != cur.parentId) docsInSubtree(tree, folder.id) else emptyList()
        }
        affectedDocs.forEach { hooks.documentMoved(it) }
    }

    override suspend fun moveFolder(id: String, newParentId: String?) {
        val now = clock()
        val affectedDocs = db.withTransaction {
            val cur = db.folders().get(id) ?: return@withTransaction emptyList()
            if (cur.parentId == newParentId) return@withTransaction emptyList()
            val tree = FolderTree(db.folders().getAllIncludingTrashed().map { it.toModel() })
            if (tree.wouldCreateCycle(id, newParentId)) {
                Log.w(TAG, "Refused to move a folder into itself or one of its sub-folders")
                return@withTransaction emptyList()
            }
            if (newParentId != null && tree[newParentId]?.trashedAt != null) return@withTransaction emptyList()
            if (newParentId != null && tree[newParentId] == null) return@withTransaction emptyList()
            db.folders().upsert(cur.copy(parentId = newParentId, name = uniqueSiblingName(cur.name, newParentId, id), modifiedAt = now))
            docsInSubtree(tree, id)
        }
        affectedDocs.forEach { hooks.documentMoved(it) }
    }

    override suspend fun trashFolder(id: String) {
        val now = clock()
        db.withTransaction {
            val tree = FolderTree(db.folders().getAllIncludingTrashed().map { it.toModel() })
            val root = tree[id] ?: return@withTransaction
            if (root.trashedAt != null) return@withTransaction
            val subtree = tree.subtree(id)
            for (fid in subtree) {
                val f = tree[fid] ?: continue
                if (f.trashedAt == null) db.folders().upsert(FolderEntity.from(f.copy(trashedAt = now)))
            }
            db.documents().getAll()
                .filter { it.folderId != null && it.folderId in subtree && it.trashedAt == null }
                .forEach { db.documents().update(it.copy(trashedAt = now)) }
        }
    }

    override suspend fun restoreFolder(id: String) {
        val affectedDocs = db.withTransaction {
            val tree = FolderTree(db.folders().getAllIncludingTrashed().map { it.toModel() })
            val root = tree[id] ?: return@withTransaction emptyList()
            val stamp = root.trashedAt
            if (stamp != null) {
                // Restore what was trashed together with this folder; items trashed separately stay in the trash.
                val subtree = tree.subtree(id)
                for (fid in subtree) {
                    val f = tree[fid] ?: continue
                    if (fid == id || f.trashedAt == stamp) db.folders().upsert(FolderEntity.from(f.copy(trashedAt = null)))
                }
                db.documents().getAll()
                    .filter { it.folderId != null && it.folderId in subtree && it.trashedAt == stamp }
                    .forEach { db.documents().update(it.copy(trashedAt = null)) }
            }
            val renamed = HashSet<String>()
            ensureFolderChainLive(root.parentId, childId = id, renamed = renamed)
            // A folder with its name may have been created at its level while it was in the trash.
            db.folders().get(id)?.takeIf { it.trashedAt == null }?.let { cur ->
                val next = withUniqueName(cur, renamed)
                if (next != cur) db.folders().upsert(next)
            }
            docsInRenamedFolders(renamed)
        }
        // Their folder path changed: sync moves their copies too.
        affectedDocs.forEach { hooks.documentMoved(it) }
    }

    override suspend fun folderPath(folderId: String?): String {
        if (folderId == null) return ""
        return FolderTree(db.folders().getAllIncludingTrashed().map { it.toModel() }).path(folderId)
    }

    /** [requested] if it is a live folder that would not create a cycle, otherwise [fallback]. */
    private fun validParent(tree: FolderTree, folderId: String, requested: String?, fallback: String?): String? {
        if (requested == null) return null
        val parent = tree[requested] ?: return fallback
        if (parent.trashedAt != null || tree.wouldCreateCycle(folderId, requested)) return fallback
        return requested
    }

    private suspend fun uniqueSiblingName(name: String, parentId: String?, selfId: String): String =
        DateNaming.unique(name, db.folders().children(parentId).filter { it.id != selfId }.map { it.name })

    private suspend fun docsInSubtree(tree: FolderTree, folderId: String): List<String> {
        val subtree = tree.subtree(folderId)
        return db.documents().getAll().filter { it.folderId != null && it.folderId in subtree }.map { it.id }
    }

    /**
     * [f] with a name none of its live siblings has (a folder revived or moved to the top level may
     * clash with one created meanwhile); adds its id to [renamed] when the name changes.
     */
    private suspend fun withUniqueName(f: FolderEntity, renamed: MutableSet<String>?): FolderEntity {
        val name = uniqueSiblingName(f.name, f.parentId, f.id)
        if (name == f.name) return f
        renamed?.add(f.id)
        return f.copy(name = name)
    }

    /** Documents whose folder path changed because the folders in [renamed] got a new name. */
    private suspend fun docsInRenamedFolders(renamed: Set<String>): List<String> {
        if (renamed.isEmpty()) return emptyList()
        val tree = FolderTree(db.folders().getAllIncludingTrashed().map { it.toModel() })
        return renamed.flatMap { docsInSubtree(tree, it) }.distinct()
    }

    /**
     * Makes [folderId] and its ancestors visible again (restoring trashed ones, re-rooting below a
     * missing parent, renaming them if a live sibling took their name meanwhile; their ids go to
     * [renamed]). Returns [folderId] if it exists, null otherwise. Runs inside a transaction.
     */
    private suspend fun ensureFolderChainLive(folderId: String?, childId: String? = null, renamed: MutableSet<String>? = null): String? {
        if (folderId == null) return null
        val all = db.folders().getAllIncludingTrashed().associateBy { it.id }
        if (all[folderId] == null) {
            // The parent is gone: the child becomes a top-level folder.
            childId?.let { all[it] }?.let { db.folders().upsert(withUniqueName(it.copy(parentId = null), renamed)) }
            return null
        }
        var current = all[folderId]
        val seen = HashSet<String>()
        while (current != null && seen.add(current.id)) {
            val parent = current.parentId?.let { all[it] }
            var next: FolderEntity = current
            if (current.trashedAt != null) next = next.copy(trashedAt = null)
            if (current.parentId != null && parent == null) next = next.copy(parentId = null)
            if (next != current) db.folders().upsert(withUniqueName(next, renamed))
            current = parent
        }
        return folderId
    }

    // ------------------------------------------------------------------ Documents

    override fun observeDocuments(filter: LibraryFilter): Flow<List<DocumentMeta>> =
        combine(db.documents().observeAll(), db.folders().observeAll()) { docs, folders ->
            LibraryQuery.apply(docs.map { it.toModel() }, filter, folders.map { it.toModel() }, clock())
        }.flowOn(Dispatchers.Default).distinctUntilChanged()

    override fun observeDocument(id: String): Flow<DocumentMeta?> =
        db.documents().observe(id).map { it?.toModel() }.distinctUntilChanged()

    override suspend fun getDocument(id: String): DocumentMeta? = db.documents().get(id)?.toModel()

    override suspend fun createDocument(kind: DocKind, folderId: String?, template: PageTemplate?, originalName: String?): DocumentMeta {
        val prefs = settings.current
        val now = clock()
        val id = UUID.randomUUID().toString()
        val importedTitle = originalName?.let { TitleRules.fromImportedName(it) }
        val status = when {
            importedTitle != null && !prefs.naming.renameImported -> AutoNameStatus.IMPORTED
            !prefs.naming.autoNameEnabled -> AutoNameStatus.DISABLED
            else -> AutoNameStatus.PENDING
        }
        val firstPages = when (kind) {
            DocKind.NOTEBOOK -> listOf(LibraryPages.notebookPage(template ?: prefs.notebookDefaults.template, id, now))
            DocKind.WHITEBOARD -> listOf(LibraryPages.whiteboardPage(id, now))
            DocKind.TYPED, DocKind.PDF -> emptyList()
        }
        val doc = db.withTransaction {
            val folder = folderId?.let { db.folders().get(it) }?.takeIf { it.trashedAt == null }
            val title = if (importedTitle != null) {
                DateNaming.unique(importedTitle, db.documents().titlesExcept(id))
            } else {
                TitleRules.untitled(now, db.documents().titlesWithPrefix(DateNaming.initialTitle(now)))
            }
            val meta = DocumentMeta(
                id = id, title = title, kind = kind, folderId = folder?.id, createdAt = now, modifiedAt = now,
                autoNameStatus = status, originalName = originalName, pageCount = firstPages.size,
            )
            db.documents().insert(DocumentEntity.from(meta))
            if (firstPages.isNotEmpty()) db.pages().upsertAll(firstPages)
            meta
        }
        hooks.documentMetaChanged(id)
        return doc
    }

    override suspend fun renameDocument(id: String, newTitle: String) {
        val clean = TitleRules.sanitize(newTitle) ?: return // blank names are rejected
        val now = clock()
        val renamed = db.withTransaction {
            val cur = db.documents().get(id) ?: return@withTransaction false
            if (clean == cur.title) return@withTransaction false
            val title = DateNaming.unique(clean, db.documents().titlesExcept(id))
            db.documents().update(cur.copy(title = title, autoNameStatus = AutoNameStatus.MANUAL, modifiedAt = now))
            true
        }
        if (renamed) hooks.documentRenamed(id)
    }

    override suspend fun applyAutoName(id: String, topic: String): String =
        applyTopic(id, topic, onlyWhileWaiting = false) ?: throw IllegalArgumentException("Document not found")

    /**
     * The background job's variant of [applyAutoName]: applies [topic] only if the document is
     * still waiting for an automatic name (not renamed by the user, not trashed) at commit time.
     * Returns the new title, or null when it was left alone.
     */
    internal suspend fun applyAutoNameIfWaiting(id: String, topic: String): String? =
        applyTopic(id, topic, onlyWhileWaiting = true)

    private suspend fun applyTopic(id: String, topic: String, onlyWhileWaiting: Boolean): String? {
        val (title, changed) = db.withTransaction {
            val cur = db.documents().get(id) ?: return@withTransaction null
            if (onlyWhileWaiting && (cur.autoNameStatus !in AutoNamingRules.AUTOMATIC || cur.trashedAt != null)) {
                return@withTransaction null
            }
            // The date prefix the document already shows is kept (see TitleRules.auto): never recomputed.
            val title = TitleRules.auto(cur.createdAt, topic, db.documents().titlesExcept(id), currentTitle = cur.title)
                ?: return@withTransaction cur.title to false // empty or generic topic: keep the current name
            db.documents().update(
                cur.copy(title = title, topic = DateNaming.cleanTopic(topic), autoNameStatus = AutoNameStatus.DONE),
            )
            title to (title != cur.title)
        } ?: return null
        // Notified after commit, so collaborators read the new title.
        if (changed) hooks.documentRenamed(id)
        return title
    }

    /**
     * Writes [doc] as given (inserting it if new, e.g. a restored backup), except for what a
     * stale copy held by another screen must never undo on an existing document: the page count
     * (owned by the page operations), the trash state (use trash/restore), a newer modifiedAt,
     * and a name the document already got (DONE/MANUAL never fall back to an automatic state).
     */
    override suspend fun updateDocument(doc: DocumentMeta) {
        db.withTransaction {
            val existing = db.documents().get(doc.id)
            if (existing == null) {
                db.documents().upsert(DocumentEntity.from(doc))
                return@withTransaction
            }
            val merged = mergeUpdate(existing.toModel(), doc, db.pages().count(doc.id))
            db.documents().upsert(DocumentEntity.from(merged))
        }
    }

    override suspend fun moveDocument(id: String, folderId: String?) {
        val moved = db.withTransaction {
            val cur = db.documents().get(id) ?: return@withTransaction false
            val target = folderId?.let { db.folders().get(it) }
            if (folderId != null && (target == null || target.trashedAt != null)) return@withTransaction false
            if (cur.folderId == folderId) return@withTransaction false
            db.documents().update(cur.copy(folderId = folderId))
            true
        }
        if (moved) hooks.documentMoved(id)
    }

    override suspend fun duplicateDocument(id: String): DocumentMeta {
        val src = db.documents().get(id) ?: throw IllegalArgumentException("Document not found")
        val newId = UUID.randomUUID().toString()
        val now = clock()
        val srcPages = db.pages().forDocument(id)
        val pageIds = srcPages.associate { it.id to UUID.randomUUID().toString() }
        val copied = try {
            docFiles.copyForDuplicate(id, newId, pageIds)
        } catch (e: Exception) {
            docFiles.deleteDocument(newId)
            throw e
        }
        val stalePages = ArrayList<String>()
        val doc = try {
            db.withTransaction {
                val title = TitleRules.copyOf(src.title, db.documents().titlesExcept(newId))
                val (status, attempts) = when (src.autoNameStatus) {
                    AutoNameStatus.QUEUED, AutoNameStatus.INSUFFICIENT -> AutoNameStatus.PENDING to 0
                    else -> src.autoNameStatus to 0
                }
                val entity = src.copy(
                    id = newId, title = title, modifiedAt = now, openedAt = 0, autoNameStatus = status,
                    autoNameAttempts = attempts, pinned = false, archived = false, trashedAt = null,
                    pageCount = srcPages.size, lastPageIndex = 0, lastZoom = 1f, lastScrollX = 0f, lastScrollY = 0f,
                    thumbnailPath = copied.coverPath, lastSyncedAt = 0,
                )
                db.documents().insert(entity)
                val newPages = srcPages.map { p ->
                    val fresh = p.indexedAt > 0 && p.indexedAt >= p.modifiedAt
                    val newPageId = pageIds.getValue(p.id)
                    if (!fresh) stalePages += newPageId
                    p.copy(
                        id = newPageId, documentId = newId, modifiedAt = now, indexedAt = if (fresh) now else 0,
                        thumbnailPath = copied.pageThumbnails[newPageId],
                    )
                }
                if (newPages.isNotEmpty()) db.pages().upsertAll(newPages)
                val outline = db.outline().forDocument(id).filter { it.pageId in pageIds }
                val outlineIds = outline.associate { it.id to UUID.randomUUID().toString() }
                outline.forEach { e ->
                    db.outline().upsert(
                        e.copy(
                            id = outlineIds.getValue(e.id), documentId = newId, pageId = pageIds.getValue(e.pageId),
                            parentId = e.parentId?.let { outlineIds[it] },
                        ),
                    )
                }
                val freshMap = pageIds.filterValues { it !in stalePages }
                if (freshMap.isNotEmpty()) pageOps.copyIndexRows(id, freshMap, newId, now)
                entity.toModel()
            }
        } catch (e: Exception) {
            docFiles.deleteDocument(newId)
            throw e
        }
        hooks.documentMetaChanged(newId)
        if (stalePages.isNotEmpty()) hooks.pagesNeedIndexing(newId, stalePages)
        if (doc.thumbnailPath == null) hooks.coverChanged(newId)
        return doc
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) {
        updateDoc(id) { it.copy(favorite = favorite) }
    }

    override suspend fun setPinned(id: String, pinned: Boolean) {
        updateDoc(id) { it.copy(pinned = pinned) }
    }

    override suspend fun setArchived(id: String, archived: Boolean) {
        updateDoc(id) { it.copy(archived = archived) }
    }

    override suspend fun setTags(id: String, tags: List<String>) {
        val clean = normalizeTags(tags)
        val now = clock()
        val changed = updateDoc(id) {
            if (it.tags == clean.joinToString(DocumentEntity.TAG_SEP)) it
            else it.copy(tags = clean.joinToString(DocumentEntity.TAG_SEP), modifiedAt = now)
        }
        if (changed) hooks.documentMetaChanged(id)
    }

    override suspend fun trashDocument(id: String) {
        val now = clock()
        updateDoc(id) { if (it.trashedAt != null) it else it.copy(trashedAt = now) }
    }

    override suspend fun restoreDocument(id: String) {
        val affectedDocs = db.withTransaction {
            val cur = db.documents().get(id) ?: return@withTransaction emptyList()
            if (cur.trashedAt == null) return@withTransaction emptyList()
            // Return it to its folder (bringing the folder back if needed), or to the root if the folder is gone.
            val renamed = HashSet<String>()
            val folderId = ensureFolderChainLive(cur.folderId, renamed = renamed)
            db.documents().update(cur.copy(trashedAt = null, folderId = folderId))
            docsInRenamedFolders(renamed)
        }
        // Their folder path changed: sync moves their copies too.
        affectedDocs.forEach { hooks.documentMoved(it) }
    }

    override suspend fun deletePermanently(id: String) {
        deletePermanently(id, onlyIfTrashedBefore = null)
    }

    /**
     * With [onlyIfTrashedBefore], deletes the document only while it is still in the trash and was
     * trashed before that time, checked in the deleting transaction: a document restored while
     * Empty trash or the purge works through its list stays alive. Returns false if it was skipped.
     */
    private suspend fun deletePermanently(id: String, onlyIfTrashedBefore: Long?): Boolean {
        fun keep(cur: DocumentEntity): Boolean {
            val trashedAt = cur.trashedAt
            return onlyIfTrashedBefore != null && (trashedAt == null || trashedAt >= onlyIfTrashedBefore)
        }
        if (onlyIfTrashedBefore != null) db.documents().get(id)?.let { if (keep(it)) return false }
        // Journaled first: if the process dies after the rows are gone, the folder is still removed later.
        docFiles.markPendingDelete(id)
        var skipped = false
        // Read inside the transaction: once the row is gone the cascade has taken the recordings
        // with it, and the speaker service keeps voice data per recording outside the database.
        val recordingIds: List<String>? = db.withTransaction {
            val cur = db.documents().get(id) ?: return@withTransaction null
            if (keep(cur)) {
                skipped = true
                return@withTransaction null
            }
            val recordings = db.recordings().forDocument(id).map { it.id }
            db.chat().threadsForDocument(id).forEach { db.chat().deleteThread(it) }
            db.textIndex().deleteDocument(id)
            db.embeddings().deleteDocument(id)
            // Cascades to pages, outline, recordings and their transcript segments and speakers.
            db.documents().delete(id)
            recordings
        }
        // Restored meanwhile. The journal entry stays: the purge never removes the folder of a
        // document whose row exists, and the next deletion of this document clears it.
        if (skipped) return false
        // The rows are gone: finish the cleanup even if the caller (a screen) is cancelled now,
        // or the voice data of the recordings and the sync queue would be left behind.
        withContext(NonCancellable) {
            docFiles.deleteDocument(id)
            docFiles.clearPendingDelete(id)
            if (recordingIds != null) {
                if (recordingIds.isNotEmpty()) hooks.recordingsDeleted(id, recordingIds)
                hooks.documentDeleted(id)
            }
        }
        return true
    }

    override suspend fun emptyTrash() {
        for (doc in db.documents().trashed()) deleteQuietly(doc.id, trashedBefore = Long.MAX_VALUE)
        deleteTrashedFolders(before = null)
    }

    /**
     * Permanently removes documents and folders that have been in the trash for more than
     * [TRASH_RETENTION_MS], and finishes deletions interrupted by a crash (only folders of
     * documents whose deletion was journaled and whose row is gone). Returns the number of
     * documents removed.
     */
    suspend fun purgeExpiredTrash(): Int {
        val now = clock()
        val cutoff = now - TRASH_RETENTION_MS
        var removed = 0
        for (doc in db.documents().trashedBefore(cutoff)) if (deleteQuietly(doc.id, trashedBefore = cutoff)) removed++
        deleteTrashedFolders(before = cutoff)
        for (pending in docFiles.pendingDeletes()) {
            // A row that still exists means the deletion never committed (the document is alive) or
            // is in progress right now: its entry is left to that deletion, which clears it.
            if (db.documents().get(pending) == null) {
                docFiles.deleteDocument(pending)
                docFiles.clearPendingDelete(pending)
            }
        }
        return removed
    }

    private suspend fun deleteQuietly(id: String, trashedBefore: Long): Boolean = try {
        deletePermanently(id, onlyIfTrashedBefore = trashedBefore)
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        Log.e(TAG, "Could not delete document $id: ${e.javaClass.simpleName}")
        false
    }

    /** Deletes trashed folder rows (all, or those trashed before [before]); live content inside moves to the root. */
    private suspend fun deleteTrashedFolders(before: Long?) {
        db.withTransaction {
            val all = db.folders().getAllIncludingTrashed()
            val doomed = all.filter { f -> f.trashedAt != null && (before == null || f.trashedAt < before) }.mapTo(HashSet()) { it.id }
            if (doomed.isEmpty()) return@withTransaction
            all.filter { it.id !in doomed && it.parentId != null && it.parentId in doomed }
                .forEach { db.folders().upsert(it.copy(parentId = null)) }
            db.documents().getAll()
                .filter { it.trashedAt == null && it.folderId != null && it.folderId in doomed }
                .forEach { db.documents().update(it.copy(folderId = null)) }
            doomed.forEach { db.folders().delete(it) }
        }
    }

    override suspend fun updateViewState(id: String, pageIndex: Int, zoom: Float, scrollX: Float, scrollY: Float) {
        db.documents().updateViewState(
            id, pageIndex.coerceAtLeast(0),
            if (zoom.isFinite() && zoom > 0f) zoom else 1f,
            if (scrollX.isFinite()) scrollX else 0f,
            if (scrollY.isFinite()) scrollY else 0f,
            clock(),
        )
    }

    override suspend fun touch(id: String) {
        db.documents().touch(id, clock())
    }

    override suspend fun setThumbnail(id: String, path: String?) {
        db.documents().setThumbnail(id, path)
    }

    /** Read-modify-write of one document in a transaction; returns true when something changed. */
    private suspend fun updateDoc(id: String, transform: (DocumentEntity) -> DocumentEntity): Boolean =
        db.withTransaction {
            val cur = db.documents().get(id) ?: return@withTransaction false
            val next = transform(cur)
            if (next == cur) return@withTransaction false
            db.documents().update(next)
            true
        }

    // ------------------------------------------------------------------ Pages

    override fun observePages(docId: String): Flow<List<PageMeta>> = pageOps.observePages(docId)
    override suspend fun getPages(docId: String): List<PageMeta> = pageOps.getPages(docId)
    override suspend fun getPage(pageId: String): PageMeta? = pageOps.getPage(pageId)
    override suspend fun addPage(docId: String, atIndex: Int?, template: PageTemplate?): PageMeta = pageOps.addPage(docId, atIndex, template)
    override suspend fun addPdfPages(docId: String, pdfPageSizesPt: List<Pair<Float, Float>>, firstPdfIndex: Int, atIndex: Int?): List<PageMeta> =
        pageOps.addPdfPages(docId, pdfPageSizesPt, firstPdfIndex, atIndex)
    override suspend fun deletePage(pageId: String) = pageOps.deletePage(pageId)
    override suspend fun duplicatePage(pageId: String): PageMeta = pageOps.duplicatePage(pageId)
    override suspend fun movePage(pageId: String, toIndex: Int) = pageOps.movePage(pageId, toIndex)
    override suspend fun reorderPages(docId: String, orderedPageIds: List<String>) = pageOps.reorderPages(docId, orderedPageIds)
    override suspend fun copyPageToDocument(pageId: String, targetDocId: String, atIndex: Int?): PageMeta =
        pageOps.copyPageToDocument(pageId, targetDocId, atIndex)
    override suspend fun rotatePage(pageId: String, degreesClockwise: Int) = pageOps.rotatePage(pageId, degreesClockwise)
    override suspend fun setPaper(pageId: String, paper: PaperSpec) = pageOps.setPaper(pageId, paper)
    override suspend fun setPaperForAll(docId: String, paper: PaperSpec) = pageOps.setPaperForAll(docId, paper)
    override suspend fun setPageSize(pageId: String, widthPt: Float, heightPt: Float) = pageOps.setPageSize(pageId, widthPt, heightPt)
    override suspend fun setBookmark(pageId: String, bookmarked: Boolean, title: String?) = pageOps.setBookmark(pageId, bookmarked, title)
    override suspend fun pageContentChanged(docId: String, pageId: String) = pageOps.pageContentChanged(docId, pageId)

    // ------------------------------------------------------------------ Outline

    override fun observeOutline(docId: String): Flow<List<OutlineEntry>> =
        db.outline().observe(docId).map { list -> list.map { it.toModel() } }.distinctUntilChanged()

    override suspend fun upsertOutlineEntry(entry: OutlineEntry) {
        val title = TitleRules.sanitize(entry.title) ?: return
        db.withTransaction {
            if (db.documents().get(entry.documentId) == null) return@withTransaction
            val page = db.pages().get(entry.pageId)
            if (page == null || page.documentId != entry.documentId) return@withTransaction
            val parentId = entry.parentId?.takeIf { it != entry.id && db.outline().get(it)?.documentId == entry.documentId }
            db.outline().upsert(
                OutlineEntity.from(entry.copy(id = entry.id.ifBlank { UUID.randomUUID().toString() }, title = title, parentId = parentId)),
            )
        }
    }

    override suspend fun deleteOutlineEntry(id: String) {
        db.withTransaction {
            val entry = db.outline().get(id) ?: return@withTransaction
            db.outline().forDocument(entry.documentId)
                .filter { it.parentId == id }
                .forEach { db.outline().upsert(it.copy(parentId = entry.parentId)) }
            db.outline().delete(id)
        }
    }

    // ------------------------------------------------------------------ Storage

    override suspend fun storageInfo(): StorageInfo {
        val scan = docFiles.scan()
        db.withTransaction {
            for (doc in db.documents().getAll()) {
                val size = scan.perDocument[FileStore.safe(doc.id)] ?: 0L
                if (size != doc.sizeBytes) db.documents().setSize(doc.id, size)
            }
        }
        return StorageInfo(
            documentCount = db.documents().countLive(),
            totalBytes = scan.totalBytes,
            audioBytes = scan.audioBytes,
            pdfBytes = scan.pdfBytes,
            inkBytes = scan.inkBytes,
            freeBytes = docFiles.freeBytes(),
        )
    }

    companion object {
        private const val TAG = "Library"
        const val TRASH_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000
        const val DEFAULT_FOLDER_NAME = "New folder"
        private const val DEFAULT_FOLDER_ICON = "folder"
        private const val MAX_TAGS = 32
        private const val MAX_TAG_CHARS = 48

        /** [incoming] over [existing], minus what a stale copy must not undo (see [updateDocument]). */
        internal fun mergeUpdate(existing: DocumentMeta, incoming: DocumentMeta, pageCount: Int): DocumentMeta {
            val merged = incoming.copy(
                pageCount = pageCount,
                trashedAt = existing.trashedAt,
                modifiedAt = maxOf(existing.modifiedAt, incoming.modifiedAt),
            )
            val alreadyNamed = existing.autoNameStatus == AutoNameStatus.DONE || existing.autoNameStatus == AutoNameStatus.MANUAL
            if (!alreadyNamed || incoming.autoNameStatus !in AutoNamingRules.AUTOMATIC) return merged
            return merged.copy(
                title = existing.title, autoNameStatus = existing.autoNameStatus,
                topic = existing.topic, autoNameAttempts = existing.autoNameAttempts,
            )
        }

        /** Trimmed, de-duplicated (case-insensitive) tags without separator characters. */
        internal fun normalizeTags(tags: List<String>): List<String> {
            val seen = HashSet<String>()
            return tags.asSequence()
                .mapNotNull { TitleRules.sanitize(it.replace(DocumentEntity.TAG_SEP, " "))?.take(MAX_TAG_CHARS)?.trim() }
                .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
                .take(MAX_TAGS)
                .toList()
        }
    }
}
