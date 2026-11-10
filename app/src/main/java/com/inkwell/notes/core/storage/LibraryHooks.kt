package com.inkwell.notes.core.storage

import android.graphics.Bitmap
import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.transcription.Transcriptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * What the library tells other features after it changes. The repository is built from
 * plain collaborators (no graph), so these are resolved lazily from the process graph and are
 * strictly fire-and-forget: a failing or missing collaborator never fails a library operation.
 *
 * Deliberately not called from [LibraryRepository.updateDocument], [LibraryRepository.setThumbnail]
 * or view-state updates, which other features use to write back their own results; that
 * keeps notification loops impossible.
 */
internal interface LibraryHooks {
    /** Title, tags or folder changed: refresh the searchable metadata. */
    fun documentMetaChanged(docId: String)

    /** The title changed: refresh search and copies kept in sync destinations. */
    fun documentRenamed(docId: String)

    /**
     * The document is in another folder now, or its folder was renamed or moved: refresh search
     * and move the copies kept in sync destinations.
     */
    fun documentMoved(docId: String) = documentMetaChanged(docId)

    /** Pages were created whose text is not indexed yet (copies of stale pages). */
    fun pagesNeedIndexing(docId: String, pageIds: List<String>)

    /** The first page or its appearance changed: regenerate the library thumbnail. */
    fun coverChanged(docId: String)

    /** The document and its files are gone. */
    fun documentDeleted(docId: String)

    /**
     * Recordings of a deleted document are gone from the database (rows and audio cascade or
     * are removed with the folder), so what other features keep about them outside the
     * database must go too: a queued or running transcription, the speaker service's encrypted
     * per-recording voice data, its queued identification job, its model wait and its "voices to
     * name" notification.
     */
    fun recordingsDeleted(docId: String, recordingIds: List<String>) {}

    /** PNG of a source-PDF page, for copying a PDF page into a document with another PDF. */
    suspend fun renderPdfPagePng(docId: String, pdfPageIndex: Int, widthPx: Int, heightPx: Int): ByteArray?
}

internal object GraphLibraryHooks : LibraryHooks {
    private const val TAG = "LibraryHooks"

    private fun graph(): AppGraph? = try {
        AppGraph.get()
    } catch (e: IllegalStateException) {
        null
    }

    private inline fun dispatch(what: String, crossinline action: suspend (AppGraph) -> Unit) {
        val g = graph() ?: return
        g.appScope.launch {
            try {
                action(g)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun documentMetaChanged(docId: String) = dispatch("index meta") { it.indexing.documentMetaChanged(docId) }

    override fun documentRenamed(docId: String) = dispatch("rename") {
        it.indexing.documentMetaChanged(docId)
        // The notes show the title: copy them again. The folder copies live in a directory named
        // after it, transcripts and audio included: move them.
        it.sync.onDocumentChanged(docId)
        it.sync.onDocumentMoved(docId)
    }

    override fun documentMoved(docId: String) = dispatch("move") {
        it.indexing.documentMetaChanged(docId)
        // Only where the copies live changed: no new notes export for Google Drive or the vault.
        it.sync.onDocumentMoved(docId)
    }

    override fun pagesNeedIndexing(docId: String, pageIds: List<String>) = dispatch("index pages") { g ->
        pageIds.forEach { g.indexing.pageChanged(docId, it) }
    }

    override fun coverChanged(docId: String) = dispatch("thumbnail") { it.thumbnails.refresh(docId) }

    override fun documentDeleted(docId: String) = dispatch("delete") {
        it.pdf.close(docId)
        it.sync.onDocumentDeleted(docId)
        it.search.removeDocument(docId)
    }

    override fun recordingsDeleted(docId: String, recordingIds: List<String>) = dispatch("forget recordings") { g ->
        // Each recording on its own: one failure must not keep the others' voice data around.
        for (id in recordingIds) {
            try {
                // A running transcription would otherwise keep writing its progress file.
                Transcriptions.cancel(g, id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "cancel transcription failed: ${e.javaClass.simpleName}")
            }
            try {
                g.speakers.onRecordingDeleted(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "forget recording failed: ${e.javaClass.simpleName}")
            }
        }
    }

    override suspend fun renderPdfPagePng(docId: String, pdfPageIndex: Int, widthPx: Int, heightPx: Int): ByteArray? {
        val g = graph() ?: return null
        val bitmap = g.pdf.renderPage(docId, pdfPageIndex, widthPx, heightPx) ?: return null
        // The PDF service caches its renders, so the bitmap is not ours to recycle.
        return withContext(Dispatchers.IO) {
            if (bitmap.isRecycled) return@withContext null
            ByteArrayOutputStream().use { out ->
                if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
            }
        }
    }
}
