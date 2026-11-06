package com.inkwell.notes.library

import android.net.Uri
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
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Folder
import com.inkwell.notes.core.model.LibraryFilter
import com.inkwell.notes.core.model.LibrarySort
import com.inkwell.notes.core.model.LibraryView
import com.inkwell.notes.core.model.PageTemplate
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.StorageInfo
import com.inkwell.notes.export.ExportFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Everything the library pane draws, derived from the repository flows. */
data class LibraryUiState(
    val loaded: Boolean = false,
    val location: LibraryLocation = LibraryLocation.Root,
    /** The query the lists are filtered by (debounced, trimmed). */
    val query: String = "",
    val index: FolderIndex = FolderIndex(emptyList()),
    val currentFolder: Folder? = null,
    /** The open folder no longer exists (deleted or trashed elsewhere). */
    val folderMissing: Boolean = false,
    val breadcrumb: List<Folder> = emptyList(),
    val subFolders: List<Folder> = emptyList(),
    val documents: List<DocumentMeta> = emptyList(),
    val pinned: List<DocumentMeta> = emptyList(),
    val folderCounts: Map<String, Int> = emptyMap(),
    /** Every live, unarchived document (pickers, tag suggestions). */
    val allDocuments: List<DocumentMeta> = emptyList(),
    val docsById: Map<String, DocumentMeta> = emptyMap(),
    val view: LibraryView = LibraryView.GRID,
    val sort: LibrarySort = LibrarySort.MODIFIED_DESC,
    val autoNaming: Boolean = true,
    val autoNameDelayHours: Int = 24,
    val defaultPaper: PaperSpec = PaperSpec(),
) {
    val isEmpty: Boolean get() = subFolders.isEmpty() && documents.isEmpty() && pinned.isEmpty()

    /** Keys of everything selectable in the current view. */
    val selectableKeys: Set<ItemKey>
        get() = buildSet {
            subFolders.forEach { add(ItemKey.Fold(it.id)) }
            documents.forEach { add(ItemKey.Doc(it.id)) }
            pinned.forEach { add(ItemKey.Doc(it.id)) }
        }
}

/** Per-card extras loaded off the main thread. */
data class CardDecor(
    val hasRecordings: Boolean = false,
    /** Thumbnail file to show (null = draw the placeholder) and a cache key that changes when it is rewritten. */
    val thumbnailPath: String? = null,
    val thumbnailKey: String? = null,
)

/**
 * State holder for [LibraryScreen]. Lives as long as the library's back-stack entry, so it
 * follows folder changes ([onNavFolder]) instead of being recreated per folder.
 */
class LibraryViewModel(private val graph: AppGraph, initialFolderId: String?) : ViewModel() {

    private val navFolder = MutableStateFlow(initialFolderId)
    private val collection = MutableStateFlow<LibraryCollection?>(null)
    private val queryFlow = MutableStateFlow("")

    /** Search text bound to the text field (updated synchronously). */
    var queryText by mutableStateOf("")
        private set

    var selection by mutableStateOf(Selection.None)
        private set

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    val settings: StateFlow<AppSettings> = graph.settings.settings

    val location: StateFlow<LibraryLocation> = combine(navFolder, collection, ::locationOf)
        .stateIn(viewModelScope, SharingStarted.Eagerly, locationOf(initialFolderId, null))

    private val appliedQuery: Flow<String> = queryFlow
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0L else QUERY_DEBOUNCE_MS }

    private val folders: StateFlow<List<Folder>?> = graph.library.observeFolders()
        .catch { e -> logFailure("folders", e); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val allDocs: StateFlow<List<DocumentMeta>?> = graph.library.observeDocuments(LibraryFilter(inFolderOnly = false))
        .catch { e -> logFailure("documents", e); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val sort: Flow<LibrarySort> = settings.map { it.library.sort }.distinctUntilChanged()

    private val viewDocs: StateFlow<List<DocumentMeta>?> =
        combine(location, appliedQuery, sort) { l, q, s -> LibraryFilters.filterFor(l, q, s) }
            .distinctUntilChanged()
            .flatMapLatest { filter ->
                graph.library.observeDocuments(filter).catch { e -> logFailure("view", e); emit(emptyList()) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val state: StateFlow<LibraryUiState> =
        combine(
            combine(location, appliedQuery, ::Pair),
            folders,
            allDocs,
            viewDocs,
            settings,
        ) { (loc, q), f, all, view, s -> buildState(loc, q, f, all, view, s) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState(location = location.value))

    // ------------------------------------------------------------------ Card extras

    private val recordingsVersion: Flow<Int> = graph.db.invalidationTracker.createFlow("recordings")
        .scan(0) { acc, _ -> acc + 1 }
        .catch { e -> logFailure("recordings", e); emit(-1) }

    private val thumbTick = MutableStateFlow(0)
    private val recordingCache = ConcurrentHashMap<String, RecordingEntry>()
    private val thumbnailRequested = ConcurrentHashMap.newKeySet<String>()

    private data class RecordingEntry(val version: Int, val has: Boolean)

    val decor: StateFlow<Map<String, CardDecor>> =
        combine(
            state.map { it.documents + it.pinned }.distinctUntilChanged(),
            recordingsVersion,
            thumbTick,
        ) { docs, version, _ -> docs to version }
            .mapLatest { (docs, version) -> withContext(Dispatchers.IO) { loadDecor(docs, version) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    private suspend fun loadDecor(docs: List<DocumentMeta>, version: Int): Map<String, CardDecor> {
        val out = HashMap<String, CardDecor>(docs.size)
        // Covers missing (new imports, restored backups) are requested a few at a time so a
        // large library does not flood the thumbnail worker; later passes pick up the rest.
        var requests = 0
        for (d in docs) {
            val cached = recordingCache[d.id]
            val has = if (cached != null && cached.version == version) cached.has else {
                val v = try {
                    graph.db.recordings().forDocument(d.id).isNotEmpty()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    false
                }
                recordingCache[d.id] = RecordingEntry(version, v)
                v
            }
            val path = d.thumbnailPath
            val file = path?.let { File(it) }
            val exists = file != null && file.isFile
            if (!exists && d.pageCount > 0 && requests < MAX_THUMBNAIL_REQUESTS && thumbnailRequested.add(d.id)) {
                requests++
                try {
                    graph.thumbnails.refresh(d.id)
                } catch (t: Throwable) {
                    logFailure("thumbnail", t)
                }
            }
            out[d.id] = CardDecor(
                hasRecordings = has,
                thumbnailPath = if (exists) path else null,
                thumbnailKey = if (exists) "$path:${file!!.lastModified()}:${file.length()}" else null,
            )
        }
        return out
    }

    private var tickJob: Job? = null

    /** Thumbnails are rewritten in the background after editing; re-check them when the library shows again. */
    fun onResume() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            thumbTick.value++
            delay(1_500)
            thumbTick.value++
            delay(4_000)
            thumbTick.value++
        }
    }

    // ------------------------------------------------------------------ Storage and AI

    private var storageLoaded = false

    private val storageRefresh = MutableStateFlow(0)

    val storage: StateFlow<StorageInfo?> =
        combine(
            allDocs.filterNotNull().map { docs -> docs.size to docs.sumOf { it.modifiedAt / 1000 + it.pageCount } }.distinctUntilChanged(),
            storageRefresh,
        ) { a, b -> a to b }
            .mapLatest {
                if (storageLoaded) delay(STORAGE_DEBOUNCE_MS)
                val info = withContext(Dispatchers.IO) {
                    try {
                        graph.library.storageInfo()
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        logFailure("storage", t)
                        null
                    }
                }
                storageLoaded = true
                info
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun refreshStorage() {
        storageLoaded = false
        storageRefresh.value++
    }

    /** True when "Suggest name" will use an AI model (otherwise local keywords). */
    val aiNaming: StateFlow<Boolean> = settings
        .map { it.naming.useAi to it.ai }
        .distinctUntilChanged()
        .mapLatest { (useAi, _) ->
            useAi && withContext(Dispatchers.IO) {
                try {
                    graph.ai.isAvailable(AiTask.NAMING)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    false
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    val jobProgress: StateFlow<LibraryJobs.Progress?> = LibraryJobs.progress

    // ------------------------------------------------------------------ State

    private fun buildState(
        loc: LibraryLocation,
        q: String,
        f: List<Folder>?,
        all: List<DocumentMeta>?,
        view: List<DocumentMeta>?,
        s: AppSettings,
    ): LibraryUiState {
        val sortOrder = s.library.sort
        val index = FolderIndex(f.orEmpty(), LibrarySorting.folderComparator(sortOrder))
        val currentId = (loc as? LibraryLocation.InFolder)?.folderId
        val current = index[currentId]
        val missing = currentId != null && f != null && current == null
        val subFolders = when {
            !LibraryFilters.showsFolders(loc) || missing -> emptyList()
            q.isEmpty() -> index.childrenOf(currentId)
            else -> {
                val scope = if (currentId == null) index.allRows().map { it.folder }
                else index.subtree(currentId).filter { it != currentId }.mapNotNull { index[it] }
                scope.filter { it.name.contains(q, ignoreCase = true) }.sortedWith(LibrarySorting.folderComparator(sortOrder))
            }
        }
        val liveDocs = all.orEmpty()
        val showPinned = LibraryFilters.showsPinnedRow(loc, q)
        val pinned = if (showPinned) liveDocs.filter { it.pinned }.sortedByDescending { it.modifiedAt } else emptyList()
        val viewList = if (missing) emptyList() else view.orEmpty()
        val documents = if (showPinned) viewList.filter { !it.pinned } else viewList
        val byId = HashMap<String, DocumentMeta>(liveDocs.size + viewList.size)
        liveDocs.forEach { byId[it.id] = it }
        viewList.forEach { byId[it.id] = it }
        return LibraryUiState(
            loaded = f != null && all != null && view != null,
            location = loc,
            query = q,
            index = index,
            currentFolder = current,
            folderMissing = missing,
            breadcrumb = index.breadcrumb(currentId),
            subFolders = subFolders,
            documents = documents,
            pinned = pinned,
            folderCounts = FolderIndex.itemCounts(index, liveDocs),
            allDocuments = liveDocs,
            docsById = byId,
            view = s.library.view,
            sort = sortOrder,
            autoNaming = s.naming.autoNameEnabled,
            autoNameDelayHours = s.naming.delayHours,
            defaultPaper = s.notebookDefaults.template.paper,
        )
    }

    // ------------------------------------------------------------------ Navigation within the library

    /** Called with the folder argument of the navigation entry. */
    fun onNavFolder(folderId: String?) {
        if (navFolder.value == folderId) return
        navFolder.value = folderId
        collection.value = null
        resetTransient()
    }

    /** Shows [folderId] (null = top level). The screen also asks the navigator to move there. */
    fun showFolder(folderId: String?) {
        collection.value = null
        navFolder.value = folderId
        resetTransient()
    }

    fun showCollection(c: LibraryCollection) {
        collection.value = c
        resetTransient()
    }

    /** Leaves a collection for the folder the user was browsing before. */
    fun leaveCollection() {
        collection.value = null
        resetTransient()
    }

    fun setQuery(text: String) {
        queryText = text
        queryFlow.value = text
    }

    private fun resetTransient() {
        setQuery("")
        selection = Selection.None
    }

    // ------------------------------------------------------------------ Selection

    fun toggleSelection(key: ItemKey) {
        selection = selection.toggle(key)
    }

    fun select(key: ItemKey) {
        selection = selection.plus(key)
    }

    fun selectAll() {
        selection = Selection(state.value.selectableKeys)
    }

    fun clearSelection() {
        selection = Selection.None
    }

    fun pruneSelection(existing: Set<ItemKey>) {
        if (selection.active) selection = selection.retainOnly(existing)
    }

    // ------------------------------------------------------------------ Creating

    private var creating = false

    /** Instant creation without a name prompt; the editor opens right away. */
    fun create(kind: DocKind, template: PageTemplate? = null) {
        if (creating) return
        creating = true
        launchSafely(R.string.lib_err_create) {
            try {
                val t = if (kind == DocKind.NOTEBOOK) template ?: settings.value.notebookDefaults.template else null
                val doc = graph.library.createDocument(kind, creationFolder(), t)
                _events.send(LibraryEvent.Open(doc))
            } finally {
                creating = false
            }
        }
    }

    fun createFromTemplate(template: PageTemplate, makeDefault: Boolean) {
        if (makeDefault) {
            launchSafely(R.string.lib_err_settings) {
                graph.settings.update { it.copy(notebookDefaults = it.notebookDefaults.copy(template = template)) }
            }
        }
        create(DocKind.NOTEBOOK, template)
    }

    fun creationFolder(): String? = LibraryFilters.creationFolder(location.value)?.takeIf { it in state.value.index }

    fun createFolder(name: String, parentId: String?, color: Int, icon: String, onCreated: (Folder) -> Unit = {}) =
        launchSafely(R.string.lib_err_create_folder) {
            val folder = graph.library.createFolder(name.trim().ifEmpty { DEFAULT_FOLDER_NAME }, parentId, color, icon)
            onCreated(folder)
        }

    // ------------------------------------------------------------------ Editing

    fun renameDocument(id: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        launchSafely(R.string.lib_err_rename) { graph.library.renameDocument(id, clean) }
    }

    fun renameFolder(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        launchSafely(R.string.lib_err_rename) {
            val cur = graph.library.getFolder(id) ?: return@launchSafely
            graph.library.updateFolder(cur.copy(name = clean))
        }
    }

    fun updateFolderAppearance(id: String, color: Int, icon: String) = launchSafely(R.string.lib_err_update) {
        val cur = graph.library.getFolder(id) ?: return@launchSafely
        graph.library.updateFolder(cur.copy(color = color, icon = icon))
    }

    fun suggestName(docId: String) {
        viewModelScope.launch {
            message(UiText.Res(R.string.lib_msg_suggesting))
            val title = try {
                withContext(Dispatchers.IO) { graph.naming.renameNow(docId) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                logFailure("naming", t)
                message(UiText.Res(R.string.lib_err_naming))
                return@launch
            }
            message(
                if (title != null) UiText.Res(R.string.lib_msg_renamed, listOf(title))
                else UiText.Res(R.string.lib_msg_not_enough_content),
            )
        }
    }

    fun setFavorite(ids: Collection<String>, favorite: Boolean) {
        if (ids.isEmpty()) return
        selection = Selection.None
        launchSafely(R.string.lib_err_update) { ids.forEach { graph.library.setFavorite(it, favorite) } }
    }

    fun setPinned(ids: Collection<String>, pinned: Boolean) {
        if (ids.isEmpty()) return
        selection = Selection.None
        launchSafely(R.string.lib_err_update) { ids.forEach { graph.library.setPinned(it, pinned) } }
    }

    fun setArchived(ids: Collection<String>, archived: Boolean, undoable: Boolean = true) {
        if (ids.isEmpty()) return
        val list = ids.toList()
        selection = Selection.None
        launchSafely(R.string.lib_err_update) {
            list.forEach { graph.library.setArchived(it, archived) }
            if (undoable) {
                message(
                    UiText.Plural(if (archived) R.plurals.lib_msg_archived else R.plurals.lib_msg_unarchived, list.size),
                    action = UiText.Res(R.string.lib_undo),
                ) { setArchived(list, !archived, undoable = false) }
            }
        }
    }

    fun duplicate(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val list = ids.toList()
        selection = Selection.None
        launchSafely(R.string.lib_err_duplicate) {
            var n = 0
            for (id in list) {
                graph.library.duplicateDocument(id)
                n++
            }
            message(UiText.Plural(R.plurals.lib_msg_duplicated, n))
        }
    }

    fun setTags(id: String, tags: List<String>) = launchSafely(R.string.lib_err_tags) { graph.library.setTags(id, tags) }

    // ------------------------------------------------------------------ Moving and deleting

    private fun folderOf(docId: String): String? = state.value.docsById[docId]?.folderId

    /** Plan for dropping [items] on [target], for drop-target highlighting. */
    fun planDrop(items: ItemSet, target: DropTarget): DropPlan = DropRules.plan(items, target, state.value.index, ::folderOf)

    /** Moves (or trashes) [items]; returns false when the drop is not allowed. */
    fun drop(items: ItemSet, target: DropTarget): Boolean {
        if (target == DropTarget.Trash) {
            trash(items)
            return true
        }
        val s = state.value
        val plan = DropRules.plan(items, target, s.index, ::folderOf)
        if (!plan.allowed) {
            if (plan.reason == DropPlan.Reason.INTO_ITSELF) message(UiText.Res(R.string.lib_msg_into_itself))
            return false
        }
        selection = Selection.None
        val destination = (target as? DropTarget.IntoFolder)?.folderId
        val docOrigins = plan.documentIds.associateWith { folderOf(it) }
        val folderOrigins = plan.folderIds.associateWith { s.index.parentOf(it)?.id }
        val folderNames = plan.folderIds.associateWith { s.index[it]?.name }
        val destinationName: UiText = destination?.let { s.index[it]?.name }?.let { UiText.Raw(it) } ?: UiText.Res(R.string.lib_nav_library)
        launchSafely(R.string.lib_err_move) {
            plan.documentIds.forEach { graph.library.moveDocument(it, destination) }
            plan.folderIds.forEach { graph.library.moveFolder(it, destination) }
            message(UiText.Res(R.string.lib_msg_moved_to, listOf(destinationName)), action = UiText.Res(R.string.lib_undo)) {
                launchSafely(R.string.lib_err_move) {
                    docOrigins.forEach { (id, origin) -> graph.library.moveDocument(id, origin?.takeIf { it in state.value.index }) }
                    folderOrigins.forEach { (id, origin) ->
                        graph.library.moveFolder(id, origin?.takeIf { it in state.value.index })
                        // The move may have renamed it ("Math (2)") to avoid a clash at the destination.
                        val name = folderNames[id]
                        val folder = graph.library.getFolder(id)
                        if (name != null && folder != null && folder.name != name) graph.library.updateFolder(folder.copy(name = name))
                    }
                }
            }
        }
        return true
    }

    /** Moves [items] to the Trash with an Undo message. Leaves the open folder if it was among them. */
    fun trash(items: ItemSet) {
        val s = state.value
        val n = DropRules.normalize(items, s.index, ::folderOf)
        if (n.isEmpty) return
        selection = Selection.None
        val openFolder = (s.location as? LibraryLocation.InFolder)?.folderId
        val leaveTo: Pair<Boolean, String?> = openFolder?.let { open ->
            n.folderIds.firstOrNull { s.index.isWithin(open, it) }?.let { true to s.index.parentOf(it)?.id }
        } ?: (false to null)
        // Leave a folder that is about to disappear before it does, so the view never shows it missing.
        if (leaveTo.first) _events.trySend(LibraryEvent.ShowFolder(leaveTo.second))
        launchSafely(R.string.lib_err_delete) {
            n.documentIds.forEach { graph.library.trashDocument(it) }
            n.folderIds.forEach { graph.library.trashFolder(it) }
            message(UiText.Plural(R.plurals.lib_msg_moved_trash, n.size), action = UiText.Res(R.string.lib_undo)) {
                launchSafely(R.string.lib_err_restore) {
                    n.folderIds.forEach { graph.library.restoreFolder(it) }
                    n.documentIds.forEach { graph.library.restoreDocument(it) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ View preferences

    fun setView(view: LibraryView) = launchSafely(R.string.lib_err_settings) {
        graph.settings.update { it.copy(library = it.library.copy(view = view)) }
    }

    fun setSort(sort: LibrarySort) = launchSafely(R.string.lib_err_settings) {
        graph.settings.update { it.copy(library = it.library.copy(sort = sort)) }
    }

    // ------------------------------------------------------------------ Import and export

    fun importPdfs(uris: List<Uri>) = LibraryJobs.importPdfs(graph, uris, creationFolder())

    fun importImages(uris: List<Uri>) = LibraryJobs.importImages(graph, uris, creationFolder())

    fun importScan(pdfUri: Uri) = LibraryJobs.importScan(graph, pdfUri, creationFolder())

    fun importPackage(uri: Uri) = LibraryJobs.importPackage(graph, uri, creationFolder())

    fun exportMany(docIds: List<String>, format: ExportFormat, treeUri: Uri) {
        selection = Selection.None
        LibraryJobs.exportMany(graph, docIds, format, treeUri)
    }

    fun cancelJob() = LibraryJobs.cancel()

    // ------------------------------------------------------------------ Helpers

    fun message(text: UiText, action: UiText? = null, long: Boolean = false, onAction: (() -> Unit)? = null) {
        _events.trySend(LibraryEvent.Message(LibraryMessage(text, action, long || action != null, onAction)))
    }

    private fun launchSafely(@StringRes error: Int, block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            logFailure("action", t)
            message(UiText.Res(error))
        }
    }

    private fun logFailure(what: String, t: Throwable) {
        // Never log titles or content: the exception type is enough to diagnose.
        Log.w(TAG, "Library $what failed: ${t.javaClass.simpleName}")
    }

    companion object {
        private const val TAG = "Library"
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val QUERY_DEBOUNCE_MS = 150L
        private const val STORAGE_DEBOUNCE_MS = 1_500L
        private const val DEFAULT_FOLDER_NAME = "New folder"
        private const val MAX_THUMBNAIL_REQUESTS = 24

        fun locationOf(folderId: String?, c: LibraryCollection?): LibraryLocation = when {
            c != null -> LibraryLocation.Collection(c)
            folderId != null -> LibraryLocation.InFolder(folderId)
            else -> LibraryLocation.Root
        }

        fun factory(folderId: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer { LibraryViewModel(AppGraph.get(), folderId) }
        }
    }
}
