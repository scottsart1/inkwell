package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.DocumentMeta

/**
 * Where things go in Google Drive, mirroring the folder destination:
 *
 * ```
 * My Drive / Inkwell / <folder> / <sub-folder> / <document title> / notes.pdf
 *                                                                    notes.md
 *                                                                    transcript_<recording>   (Google Doc)
 *                                                                    audio_<recording>.m4a
 * ```
 *
 * Inkwell recognises its own folders and files by private app properties, not by name, so a
 * renamed or moved document keeps its Drive folder (renamed/moved along) and a renamed recording
 * keeps its files. Ids are remembered in [SyncStateStore] and checked again when they stop
 * working (deleted or trashed in Drive). One instance lives for one sync run.
 */
internal class DriveLayout(private val api: DriveApi, private val state: SyncStateStore) {
    private var root: String? = null
    /** Library folder chain below the root -> Drive folder id. */
    private val folders = HashMap<List<String>, String>()
    private val documents = HashMap<String, String>()
    /** Document folder id -> Inkwell's files in it, by [DriveNames.PROP_FILE] (normally one each). */
    private val files = HashMap<String, MutableMap<String, MutableList<DriveItem>>>()

    /** The "Inkwell" folder at the top of My Drive (created on first use, found again if its id is lost). */
    suspend fun root(): String {
        root?.let { return it }
        val saved = state.driveRootId?.let { id -> api.get(id)?.takeIf { it.isFolder }?.id }
        val id = saved
            ?: api.findByProperty(DriveNames.PROP_ROOT, "1").firstOrNull { it.isFolder }?.id
            ?: api.createFolder(DriveNames.ROOT_FOLDER, null, mapOf(DriveNames.PROP_ROOT to "1")).id
        if (id != state.driveRootId) state.driveRootId = id
        root = id
        return id
    }

    /** The library folder chain [names] below the root, created as needed. */
    private suspend fun libraryFolder(names: List<String>): String {
        if (names.isEmpty()) return root()
        folders[names]?.let { return it }
        val parent = libraryFolder(names.dropLast(1))
        val name = FolderNaming.segment(names.last(), fallback = "Folder")
        val id = api.findFolder(parent, name)?.id ?: api.createFolder(name, parent).id
        folders[names] = id
        return id
    }

    /** The document's own folder, renamed/moved to match its current title and library folder. */
    suspend fun documentFolder(doc: DocumentMeta, folderNames: List<String>): String {
        documents[doc.id]?.let { return it }
        val parent = libraryFolder(folderNames)
        val name = FolderNaming.documentSegment(doc.title, doc.id)
        val key = DriveNames.docKey(doc.id)
        val known = state.driveDocFolder(doc.id)?.let { id -> api.get(id)?.takeIf { it.isFolder } }
            ?: api.findByProperty(DriveNames.PROP_DOC, key).firstOrNull { it.isFolder }
        val id = if (known == null) {
            api.createFolder(name, parent, mapOf(DriveNames.PROP_DOC to key)).id
        } else {
            val moved = parent !in known.parents
            if (moved || known.name != name) {
                api.update(known.id, name, addParent = parent.takeIf { moved }, removeParents = if (moved) known.parents else emptyList())
            }
            known.id
        }
        if (id != state.driveDocFolder(doc.id)) state.setDriveDocFolder(doc.id, id)
        documents[doc.id] = id
        return id
    }

    /**
     * Writes one output into a document folder, replacing Inkwell's previous version of it (found
     * by [key]) so Drive keeps a single file with its link and version history.
     */
    suspend fun put(
        folderId: String,
        key: String,
        name: String,
        mimeType: String,
        content: DriveContent,
        convertTo: String? = null,
    ): DriveItem {
        val existing = filesIn(folderId)
        // A Google Doc is only ever replaced by another conversion, never by a plain file and vice versa.
        val previous = existing[key].orEmpty().firstOrNull { (convertTo == null) == (it.mimeType != DriveNames.MIME_GOOGLE_DOC) }
        val upload = DriveUpload(
            existingId = previous?.id,
            parentId = folderId,
            name = name,
            mimeType = mimeType,
            convertTo = convertTo,
            properties = mapOf(DriveNames.PROP_FILE to key),
            content = content,
        )
        val item = try {
            api.upload(upload)
        } catch (e: DriveException.NotFound) {
            // The previous version was deleted in Drive meanwhile: write a new one.
            if (previous == null) throw e
            existing[key]?.removeAll { it.id == previous.id }
            api.upload(upload.copy(existingId = null))
        }
        // One file per output: a version of the other kind (a Markdown transcript next to its
        // Google Doc, after Drive refused or accepted a conversion) goes to the Drive trash.
        for (other in existing[key].orEmpty()) {
            if (other.id != item.id) runCatching { api.trash(other.id) }
        }
        existing[key] = mutableListOf(item)
        return item
    }

    /** Forgets [docId]'s folder after Drive said it is gone, so the next attempt recreates it. */
    fun forgetDocument(docId: String) {
        documents.remove(docId)?.let { files.remove(it) }
        state.setDriveDocFolder(docId, null)
    }

    /** Forgets everything cached in this run (a folder disappeared mid-run). */
    fun reset() {
        root = null
        folders.clear()
        documents.clear()
        files.clear()
    }

    private suspend fun filesIn(folderId: String): MutableMap<String, MutableList<DriveItem>> = files.getOrPut(folderId) {
        val out = HashMap<String, MutableList<DriveItem>>()
        for (item in api.children(folderId)) {
            val k = item.properties[DriveNames.PROP_FILE] ?: continue
            if (!item.isFolder) out.getOrPut(k) { ArrayList() } += item
        }
        out
    }
}
