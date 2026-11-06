package com.inkwell.notes.library

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.withTransaction
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Folder
import com.inkwell.notes.core.model.LibraryFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** State holder for [TrashScreen]: trashed documents and folders, restore and permanent deletion. */
class TrashViewModel(private val graph: AppGraph) : ViewModel() {

    private val trashedDocs: Flow<List<DocumentMeta>> =
        graph.library.observeDocuments(LibraryFilter(inFolderOnly = false, trashed = true))
            .catch { e -> logFailure("documents", e); emit(emptyList()) }

    /** Every folder including trashed ones, re-read whenever the folders table changes. */
    private val allFolders: Flow<List<Folder>> =
        graph.db.invalidationTracker.createFlow("folders")
            .map { graph.db.folders().getAllIncludingTrashed().map { it.toModel() } }
            .catch { e -> logFailure("folders", e); emit(emptyList()) }

    /** Null until loaded. */
    val entries: StateFlow<List<TrashEntry>?> =
        combine(trashedDocs, allFolders) { docs, folders -> TrashModel.build(docs, folders) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = Channel<LibraryMessage>(Channel.BUFFERED)
    val messages: Flow<LibraryMessage> = _messages.receiveAsFlow()

    /** Keys of entries being restored or deleted (their buttons are disabled meanwhile). */
    var busy by mutableStateOf(emptySet<String>())
        private set

    var emptying by mutableStateOf(false)
        private set

    fun restore(entry: TrashEntry) = run(entry, R.string.lib_err_restore) {
        when (entry) {
            is TrashEntry.Doc -> graph.library.restoreDocument(entry.doc.id)
            is TrashEntry.FolderGroup -> graph.library.restoreFolder(entry.folder.id)
        }
        message(UiText.Res(R.string.lib_trash_restored, listOf(entry.title)))
    }

    fun deleteForever(entry: TrashEntry) = run(entry, R.string.lib_err_delete_forever) {
        when (entry) {
            is TrashEntry.Doc -> graph.library.deletePermanently(entry.doc.id)
            is TrashEntry.FolderGroup -> deleteFolderGroup(entry)
        }
        message(UiText.Res(R.string.lib_trash_deleted))
    }

    fun emptyTrash() {
        if (emptying) return
        emptying = true
        // Not viewModelScope: leaving the screen must not stop a deletion halfway.
        graph.appScope.launch(Dispatchers.Main.immediate) {
            try {
                graph.library.emptyTrash()
                message(UiText.Res(R.string.lib_trash_emptied))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                logFailure("empty", t)
                message(UiText.Res(R.string.lib_err_empty_trash))
            } finally {
                emptying = false
            }
        }
    }

    /**
     * Deletes what was trashed together with a folder: its documents (rows and files) and the
     * folder rows. Anything else still filed inside (items deleted separately earlier, or live
     * items) moves to the top level first, so nothing is left pointing at a missing folder.
     */
    private suspend fun deleteFolderGroup(entry: TrashEntry.FolderGroup) {
        for (id in entry.documentIds) graph.library.deletePermanently(id)
        val doomed = entry.folderIds.toSet()
        val covered = entry.documentIds.toSet()
        val folders = graph.db.folders().getAllIncludingTrashed()
        folders.filter { it.id !in doomed && it.parentId != null && it.parentId in doomed }
            .forEach { graph.library.moveFolder(it.id, null) }
        graph.db.documents().getAll()
            .filter { it.folderId != null && it.folderId in doomed && it.id !in covered }
            .forEach { graph.library.moveDocument(it.id, null) }
        graph.db.withTransaction {
            for (id in doomed) {
                val f = graph.db.folders().get(id) ?: continue
                if (f.trashedAt != null) graph.db.folders().delete(id)
            }
        }
    }

    private fun run(entry: TrashEntry, @StringRes error: Int, block: suspend () -> Unit) {
        if (entry.key in busy) return
        busy = busy + entry.key
        // Not viewModelScope: leaving the screen must not stop a restore or deletion halfway.
        graph.appScope.launch(Dispatchers.Main.immediate) {
            try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                logFailure("action", t)
                message(UiText.Res(error))
            } finally {
                busy = busy - entry.key
            }
        }
    }

    private fun message(text: UiText) {
        _messages.trySend(LibraryMessage(text))
    }

    private fun logFailure(what: String, t: Throwable) {
        Log.w("Trash", "Trash $what failed: ${t.javaClass.simpleName}")
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TrashViewModel(AppGraph.get()) }
        }
    }
}
