package com.inkwell.notes.library.ui

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LibraryAddCheck
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inkwell.notes.R
import com.inkwell.notes.app.AppNavigator
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Folder
import com.inkwell.notes.library.DropTarget
import com.inkwell.notes.library.FolderStyle
import com.inkwell.notes.library.ItemKey
import com.inkwell.notes.library.ItemSet
import com.inkwell.notes.library.LibraryCollection
import com.inkwell.notes.library.LibraryLocation
import com.inkwell.notes.library.LibraryUiState
import com.inkwell.notes.library.LibraryViewModel
import com.inkwell.notes.library.Selection

/** Dialogs and sheets the library can show (one at a time). */
internal sealed interface LibraryDialog {
    data class RenameDoc(val doc: DocumentMeta) : LibraryDialog
    data class RenameFolder(val folder: Folder) : LibraryDialog
    data class NewFolder(val parentId: String?) : LibraryDialog
    data class FolderAppearance(val folder: Folder) : LibraryDialog
    data class Tags(val doc: DocumentMeta) : LibraryDialog
    data class Move(val items: ItemSet) : LibraryDialog
    data class Split(val doc: DocumentMeta) : LibraryDialog
    data class DeleteFolder(val folder: Folder) : LibraryDialog
    data class ExportOne(val docId: String) : LibraryDialog
    data class ExportMany(val docIds: List<String>) : LibraryDialog
    data object Storage : LibraryDialog
    data object Template : LibraryDialog
}

/**
 * Glue between the library UI, its view model and the app navigator: item actions, menus,
 * drag payloads and which dialog is open.
 */
@Stable
internal class LibraryController(
    val vm: LibraryViewModel,
    val nav: AppNavigator,
    private val resources: Resources,
) {
    var dialog by mutableStateOf<LibraryDialog?>(null)

    /** "Email to work" for one document; set by the screen. */
    var emailToWork: (String) -> Unit = {}

    /** Latest UI state; updated by the screen on every recomposition. */
    var state: LibraryUiState = LibraryUiState()

    val selection: Selection get() = vm.selection
    val selectionMode: Boolean get() = vm.selection.active

    // ---- Navigation

    fun open(doc: DocumentMeta) = nav.openDocument(doc)

    fun openFolder(folderId: String?) {
        vm.showFolder(folderId)
        nav.openLibrary(folderId)
    }

    fun showCollection(c: LibraryCollection) = vm.showCollection(c)

    /** System back inside the library: selection, then search, then collection, then parent folder. */
    fun handleBack(): Boolean {
        when {
            vm.selection.active -> vm.clearSelection()
            vm.queryText.isNotEmpty() -> vm.setQuery("")
            state.location is LibraryLocation.Collection -> vm.leaveCollection()
            state.location is LibraryLocation.InFolder -> openFolder(state.currentFolder?.let { state.index.parentOf(it.id)?.id })
            else -> return false
        }
        return true
    }

    val canHandleBack: Boolean
        get() = vm.selection.active || vm.queryText.isNotEmpty() || state.location !is LibraryLocation.Root

    // ---- Items

    fun tapDocument(doc: DocumentMeta) {
        if (selectionMode) vm.toggleSelection(ItemKey.Doc(doc.id)) else open(doc)
    }

    fun tapFolder(folder: Folder) {
        if (selectionMode) vm.toggleSelection(ItemKey.Fold(folder.id)) else openFolder(folder.id)
    }

    fun longPress(key: ItemKey) = vm.select(key)

    fun payloadFor(key: ItemKey): DragPayload? {
        val sel = vm.selection
        val items = if (key in sel) sel.asItems() else when (key) {
            is ItemKey.Doc -> ItemSet(documentIds = setOf(key.id))
            is ItemKey.Fold -> ItemSet(folderIds = setOf(key.id))
        }
        if (items.isEmpty) return null
        if (items.size > 1) {
            return DragPayload(items, resources.getQuantityString(R.plurals.lib_drag_items, items.size, items.size), Icons.Outlined.LibraryAddCheck)
        }
        val docId = items.documentIds.firstOrNull()
        if (docId != null) {
            val title = state.docsById[docId]?.title ?: return null
            return DragPayload(items, title, Icons.Outlined.Description)
        }
        val folder = state.index[items.folderIds.first()] ?: return null
        return DragPayload(items, folder.name, if (FolderStyle.hasCustomIcon(folder.icon)) FolderStyle.icon(folder.icon) else Icons.Outlined.Folder)
    }

    fun canDrop(items: ItemSet, target: DropTarget): Boolean = vm.planDrop(items, target).allowed

    fun drop(payload: DragPayload, target: DropTarget) {
        vm.drop(payload.items, target)
    }

    fun onDocAction(doc: DocumentMeta, action: DocAction) {
        when (action) {
            DocAction.OPEN -> open(doc)
            DocAction.SPLIT -> dialog = LibraryDialog.Split(doc)
            DocAction.RENAME -> dialog = LibraryDialog.RenameDoc(doc)
            DocAction.SUGGEST_NAME -> vm.suggestName(doc.id)
            DocAction.FAVORITE -> vm.setFavorite(listOf(doc.id), !doc.favorite)
            DocAction.PIN -> vm.setPinned(listOf(doc.id), !doc.pinned)
            DocAction.MOVE -> dialog = LibraryDialog.Move(ItemSet(documentIds = setOf(doc.id)))
            DocAction.DUPLICATE -> vm.duplicate(listOf(doc.id))
            DocAction.TAGS -> dialog = LibraryDialog.Tags(doc)
            DocAction.ARCHIVE -> vm.setArchived(listOf(doc.id), !doc.archived)
            DocAction.EXPORT -> dialog = LibraryDialog.ExportOne(doc.id)
            DocAction.EMAIL_WORK -> emailToWork(doc.id)
            DocAction.DELETE -> vm.trash(ItemSet(documentIds = setOf(doc.id)))
        }
    }

    fun onFolderAction(folder: Folder, action: FolderAction) {
        when (action) {
            FolderAction.OPEN -> openFolder(folder.id)
            FolderAction.RENAME -> dialog = LibraryDialog.RenameFolder(folder)
            FolderAction.APPEARANCE -> dialog = LibraryDialog.FolderAppearance(folder)
            FolderAction.NEW_INSIDE -> dialog = LibraryDialog.NewFolder(folder.id)
            FolderAction.MOVE -> dialog = LibraryDialog.Move(ItemSet(folderIds = setOf(folder.id)))
            FolderAction.DELETE -> dialog = LibraryDialog.DeleteFolder(folder)
        }
    }

    // ---- Selection actions

    private fun selectedDocs(): List<DocumentMeta> = vm.selection.documentIds.mapNotNull { state.docsById[it] }

    fun favoriteSelection() {
        val docs = selectedDocs()
        vm.setFavorite(docs.map { it.id }, Selection.bulkTarget(docs) { it.favorite })
    }

    fun pinSelection() {
        val docs = selectedDocs()
        vm.setPinned(docs.map { it.id }, Selection.bulkTarget(docs) { it.pinned })
    }

    fun archiveSelection() {
        val docs = selectedDocs()
        vm.setArchived(docs.map { it.id }, Selection.bulkTarget(docs) { it.archived })
    }

    /** Whether the archive action of the current selection archives (true) or unarchives. */
    fun selectionArchives(): Boolean = Selection.bulkTarget(selectedDocs()) { it.archived }

    fun selectionFavorites(): Boolean = Selection.bulkTarget(selectedDocs()) { it.favorite }

    fun selectionPins(): Boolean = Selection.bulkTarget(selectedDocs()) { it.pinned }

    fun duplicateSelection() = vm.duplicate(vm.selection.documentIds)

    fun moveSelection() {
        if (vm.selection.active) dialog = LibraryDialog.Move(vm.selection.asItems())
    }

    fun exportSelection() {
        val ids = vm.selection.documentIds.toList()
        if (ids.isNotEmpty()) dialog = LibraryDialog.ExportMany(ids)
    }

    fun deleteSelection() {
        if (vm.selection.active) vm.trash(vm.selection.asItems())
    }
}
