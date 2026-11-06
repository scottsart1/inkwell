package com.inkwell.notes.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.LibrarySort
import com.inkwell.notes.core.model.LibraryView
import com.inkwell.notes.library.DropTarget
import com.inkwell.notes.library.LibraryCollection
import com.inkwell.notes.library.LibraryFilters
import com.inkwell.notes.library.LibraryLocation
import com.inkwell.notes.library.LibrarySorting
import com.inkwell.notes.library.LibraryUiState
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

internal enum class NewAction { NOTEBOOK, TEMPLATE, WHITEBOARD, TYPED, IMPORT_PDF, IMPORT_IMAGES, SCAN, IMPORT_PACKAGE, FOLDER }

internal fun collectionLabel(c: LibraryCollection): Int = when (c) {
    LibraryCollection.RECENT -> R.string.lib_nav_recent
    LibraryCollection.FAVORITES -> R.string.lib_nav_favorites
    LibraryCollection.WHITEBOARDS -> R.string.lib_nav_whiteboards
    LibraryCollection.DOCUMENTS -> R.string.lib_nav_documents
    LibraryCollection.PDFS -> R.string.lib_nav_pdfs
    LibraryCollection.ARCHIVE -> R.string.lib_nav_archive
}

/**
 * Top bar: title or breadcrumb, search, view and sort controls, New. While items are
 * selected the first row shows the selection actions instead; on narrow widths the search row
 * stays in place so the content does not jump when selection starts.
 */
@Composable
internal fun LibraryTopBar(
    state: LibraryUiState,
    controller: LibraryController,
    drag: DragDropState,
    queryText: String,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    searchFocus: FocusRequester,
    onSearchFocused: (Boolean) -> Unit,
    singleRow: Boolean,
    showMenuButton: Boolean,
    onMenu: () -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    onNew: (NewAction) -> Unit,
) {
    val search: @Composable (Modifier) -> Unit = { m ->
        SearchField(queryText, onQueryChange, onSubmitSearch, searchFocus, onSearchFocused, m)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
        if (controller.selectionMode) {
            SelectionRow(controller, singleRow)
        } else {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showMenuButton) {
                    LibIconButton(Icons.Outlined.Menu, stringResource(R.string.lib_open_navigation), onMenu)
                    Spacer(Modifier.width(4.dp))
                }
                if (state.location is LibraryLocation.InFolder) {
                    val parent = state.currentFolder?.let { state.index.parentOf(it.id) }
                    val parentName = parent?.name ?: stringResource(R.string.lib_nav_library)
                    LibIconButton(Icons.Outlined.ChevronLeft, stringResource(R.string.lib_back_to, parentName), { controller.openFolder(parent?.id) })
                    Spacer(Modifier.width(4.dp))
                }
                Breadcrumb(state, controller, drag, showTrail = singleRow, modifier = Modifier.weight(1f))
                if (singleRow) {
                    Spacer(Modifier.width(16.dp))
                    search(Modifier.widthIn(min = 220.dp, max = 400.dp).weight(1.4f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    ViewToggle(state.view, controller)
                } else {
                    ViewToggleCompact(state.view, controller)
                }
                if (LibraryFilters.allowsSort(state.location)) {
                    SortMenu(state.sort, compact = !singleRow, onSort = controller.vm::setSort)
                } else if (singleRow) {
                    Text(
                        stringResource(R.string.lib_sort_recent),
                        style = MaterialTheme.typography.labelLarge,
                        color = Inkwell.colors.inkMuted,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                NewButton(compact = !singleRow, onNew = onNew)
            }
        }
        if (!singleRow) {
            search(Modifier.fillMaxWidth().padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun Breadcrumb(state: LibraryUiState, controller: LibraryController, drag: DragDropState, showTrail: Boolean, modifier: Modifier) {
    val c = Inkwell.colors
    val title: String
    val trail: List<Pair<String, DropTarget>>
    when (val loc = state.location) {
        LibraryLocation.Root -> {
            title = stringResource(R.string.lib_nav_library)
            trail = emptyList()
        }
        is LibraryLocation.Collection -> {
            title = stringResource(collectionLabel(loc.collection))
            trail = emptyList()
        }
        is LibraryLocation.InFolder -> {
            title = state.currentFolder?.name ?: ""
            val crumbs = if (showTrail) state.breadcrumb.dropLast(1) else emptyList()
            val shown = if (crumbs.size > 2) crumbs.takeLast(2) else crumbs
            val libraryLabel = stringResource(R.string.lib_nav_library)
            trail = if (!showTrail) emptyList() else buildList {
                add(libraryLabel to DropTarget.Root)
                if (crumbs.size > 2) add("…" to DropTarget.Root)
                shown.forEach { add(it.name to DropTarget.IntoFolder(it.id)) }
            }
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for ((label, target) in trail) {
            val hovered = drag.hovered == target
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (hovered) c.accent else c.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 160.dp)
                    .clip(R8)
                    .background(if (hovered) c.accentContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .dropTarget(drag, target)
                    .clickable(role = Role.Button) {
                        controller.openFolder((target as? DropTarget.IntoFolder)?.folderId)
                    }
                    .padding(horizontal = 6.dp, vertical = 13.dp),
            )
            Text("/", style = MaterialTheme.typography.labelLarge, color = c.outline)
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = if (trail.isEmpty()) 0.dp else 6.dp).semantics { heading() },
        )
    }
}

@Composable
private fun SearchField(
    text: String,
    onChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    focus: FocusRequester,
    onFocused: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val c = Inkwell.colors
    val hint = stringResource(R.string.lib_search_hint)
    var focused by remember { mutableStateOf(false) }
    // A query already there when the field is (re)composed, e.g. back from a document, keeps the
    // cursor at its end: the String overload of BasicTextField starts it at 0, so Ctrl+F and typing
    // would insert in front of the query.
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val shown = if (field.text == text) field else TextFieldValue(text, TextRange(text.length))
    BasicTextField(
        value = shown,
        onValueChange = { v ->
            field = v
            if (v.text != text) onChange(v.text)
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit(text) }),
        modifier = modifier
            .heightIn(min = Dimens.minTouch)
            .focusRequester(focus)
            .onFocusChanged {
                focused = it.isFocused
                onFocused(it.isFocused)
            }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                    onSubmit(text)
                    true
                } else if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && text.isNotEmpty()) {
                    onChange("")
                    true
                } else {
                    false
                }
            }
            .semantics { contentDescription = hint },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clip(R8)
                    .background(c.surface)
                    .border(1.dp, if (focused) c.accent else c.outline, R8)
                    .padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = c.inkMuted)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (text.isEmpty()) {
                        Text(hint, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                if (text.isNotEmpty()) {
                    LibIconButton(Icons.Outlined.Close, stringResource(R.string.lib_clear_search), { onChange("") })
                }
            }
        },
    )
}

@Composable
private fun ViewToggle(view: LibraryView, controller: LibraryController) {
    val c = Inkwell.colors
    Row(Modifier.clip(R8).border(1.dp, c.outline, R8)) {
        LibIconButton(Icons.Outlined.GridView, stringResource(R.string.lib_view_grid), { controller.vm.setView(LibraryView.GRID) }, selected = view == LibraryView.GRID)
        LibIconButton(Icons.AutoMirrored.Outlined.ViewList, stringResource(R.string.lib_view_list), { controller.vm.setView(LibraryView.LIST) }, selected = view == LibraryView.LIST)
    }
}

@Composable
private fun ViewToggleCompact(view: LibraryView, controller: LibraryController) {
    if (view == LibraryView.GRID) {
        LibIconButton(Icons.AutoMirrored.Outlined.ViewList, stringResource(R.string.lib_view_list), { controller.vm.setView(LibraryView.LIST) })
    } else {
        LibIconButton(Icons.Outlined.GridView, stringResource(R.string.lib_view_grid), { controller.vm.setView(LibraryView.GRID) })
    }
}

internal fun sortLabel(sort: LibrarySort): Int = when (sort) {
    LibrarySort.NAME_ASC -> R.string.lib_sort_name_asc
    LibrarySort.NAME_DESC -> R.string.lib_sort_name_desc
    LibrarySort.CREATED_DESC -> R.string.lib_sort_created_desc
    LibrarySort.CREATED_ASC -> R.string.lib_sort_created_asc
    LibrarySort.MODIFIED_DESC -> R.string.lib_sort_modified_desc
    LibrarySort.MODIFIED_ASC -> R.string.lib_sort_modified_asc
}

private fun sortShortLabel(sort: LibrarySort): Int = when (LibrarySorting.keyOf(sort)) {
    LibrarySorting.SortKey.NAME -> R.string.lib_sort_short_name
    LibrarySorting.SortKey.CREATED -> R.string.lib_sort_short_created
    LibrarySorting.SortKey.MODIFIED -> R.string.lib_sort_short_modified
}

@Composable
private fun SortMenu(sort: LibrarySort, compact: Boolean, onSort: (LibrarySort) -> Unit) {
    val c = Inkwell.colors
    var open by remember { mutableStateOf(false) }
    val sortLabelText = stringResource(R.string.lib_sort)
    val current = stringResource(sortLabel(sort))
    Box {
        Row(
            Modifier
                .heightIn(min = Dimens.minTouch)
                .clip(R8)
                .clickable(role = Role.Button, onClickLabel = sortLabelText) { open = true }
                .semantics { contentDescription = "$sortLabelText: $current" }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!compact) {
                Text(stringResource(sortShortLabel(sort)), style = MaterialTheme.typography.labelLarge, color = c.ink)
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                if (LibrarySorting.isAscending(sort)) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                null,
                Modifier.size(18.dp),
                tint = c.inkMuted,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = R12, containerColor = c.surface) {
            for (option in LibrarySorting.menuOrder) {
                LibMenuItem(
                    text = stringResource(sortLabel(option)),
                    icon = if (option == sort) Icons.Outlined.Check else null,
                    onClick = {
                        open = false
                        onSort(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun NewButton(compact: Boolean, onNew: (NewAction) -> Unit) {
    val c = Inkwell.colors
    var open by remember { mutableStateOf(false) }
    Box {
        if (compact) {
            Box(
                Modifier.size(Dimens.minTouch).clip(R8).background(c.accent)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.lib_new)) { open = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Add, stringResource(R.string.lib_new), Modifier.size(Dimens.icon), tint = c.onAccent) }
        } else {
            LibPrimaryButton(stringResource(R.string.lib_new), Icons.Outlined.Add, { open = true })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = R12, containerColor = c.surface) {
            val pick: (NewAction) -> Unit = { a ->
                open = false
                onNew(a)
            }
            LibMenuItem(stringResource(R.string.lib_new_notebook), Icons.Outlined.EditNote, { pick(NewAction.NOTEBOOK) }, trailing = stringResource(R.string.lib_shortcut_new))
            LibMenuItem(stringResource(R.string.lib_new_notebook_template), Icons.Outlined.Dashboard, { pick(NewAction.TEMPLATE) })
            LibMenuItem(stringResource(R.string.lib_new_whiteboard), Icons.Outlined.Gesture, { pick(NewAction.WHITEBOARD) })
            LibMenuItem(stringResource(R.string.lib_new_typed), Icons.AutoMirrored.Outlined.NoteAdd, { pick(NewAction.TYPED) })
            MenuDivider()
            LibMenuItem(stringResource(R.string.lib_import_pdf), Icons.Outlined.PictureAsPdf, { pick(NewAction.IMPORT_PDF) })
            LibMenuItem(stringResource(R.string.lib_import_images), Icons.Outlined.AddPhotoAlternate, { pick(NewAction.IMPORT_IMAGES) })
            LibMenuItem(stringResource(R.string.lib_scan_document), Icons.Outlined.DocumentScanner, { pick(NewAction.SCAN) })
            LibMenuItem(stringResource(R.string.lib_import_package), Icons.Outlined.FileOpen, { pick(NewAction.IMPORT_PACKAGE) })
            MenuDivider()
            LibMenuItem(stringResource(R.string.lib_new_folder_menu), Icons.Outlined.CreateNewFolder, { pick(NewAction.FOLDER) })
        }
    }
}

/** First top-bar row while items are selected: count and bulk actions. */
@Composable
private fun SelectionRow(controller: LibraryController, singleRow: Boolean) {
    val c = Inkwell.colors
    val sel = controller.selection
    val docs = sel.hasDocuments
    var overflow by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibIconButton(Icons.Outlined.Close, stringResource(R.string.lib_clear_selection), controller.vm::clearSelection)
        Spacer(Modifier.width(8.dp))
        Text(
            pluralStringResource(R.plurals.lib_selected_count, sel.size, sel.size),
            style = MaterialTheme.typography.titleLarge,
            color = c.ink,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        LibIconButton(Icons.Outlined.SelectAll, stringResource(R.string.lib_select_all), controller.vm::selectAll)
        LibIconButton(Icons.AutoMirrored.Outlined.DriveFileMove, stringResource(R.string.lib_action_move), controller::moveSelection)
        if (singleRow) {
            val favorites = controller.selectionFavorites()
            LibIconButton(
                if (favorites) Icons.Outlined.StarOutline else Icons.Outlined.Star,
                stringResource(if (favorites) R.string.lib_action_favorite else R.string.lib_action_unfavorite),
                controller::favoriteSelection,
                enabled = docs,
            )
            LibIconButton(
                Icons.Outlined.PushPin,
                stringResource(if (controller.selectionPins()) R.string.lib_action_pin else R.string.lib_action_unpin),
                controller::pinSelection,
                enabled = docs,
            )
            val archives = controller.selectionArchives()
            LibIconButton(
                if (archives) Icons.Outlined.Archive else Icons.Outlined.Unarchive,
                stringResource(if (archives) R.string.lib_action_archive else R.string.lib_action_unarchive),
                controller::archiveSelection,
                enabled = docs,
            )
            LibIconButton(Icons.Outlined.ContentCopy, stringResource(R.string.lib_action_duplicate), controller::duplicateSelection, enabled = docs)
            LibIconButton(Icons.Outlined.IosShare, stringResource(R.string.lib_action_export), controller::exportSelection, enabled = docs)
        }
        LibIconButton(Icons.Outlined.Delete, stringResource(R.string.lib_action_delete), controller::deleteSelection, tint = c.danger)
        if (!singleRow) {
            Box {
                LibIconButton(Icons.Outlined.MoreVert, stringResource(R.string.lib_more), { overflow = true })
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }, shape = R12, containerColor = c.surface) {
                    val run: (() -> Unit) -> Unit = { a ->
                        overflow = false
                        a()
                    }
                    val favorites = controller.selectionFavorites()
                    LibMenuItem(
                        stringResource(if (favorites) R.string.lib_action_favorite else R.string.lib_action_unfavorite),
                        if (favorites) Icons.Outlined.StarOutline else Icons.Outlined.Star,
                        { run(controller::favoriteSelection) },
                        enabled = docs,
                    )
                    LibMenuItem(
                        stringResource(if (controller.selectionPins()) R.string.lib_action_pin else R.string.lib_action_unpin),
                        Icons.Outlined.PushPin,
                        { run(controller::pinSelection) },
                        enabled = docs,
                    )
                    val archives = controller.selectionArchives()
                    LibMenuItem(
                        stringResource(if (archives) R.string.lib_action_archive else R.string.lib_action_unarchive),
                        if (archives) Icons.Outlined.Archive else Icons.Outlined.Unarchive,
                        { run(controller::archiveSelection) },
                        enabled = docs,
                    )
                    LibMenuItem(stringResource(R.string.lib_action_duplicate), Icons.Outlined.ContentCopy, { run(controller::duplicateSelection) }, enabled = docs)
                    LibMenuItem(stringResource(R.string.lib_action_export), Icons.Outlined.IosShare, { run(controller::exportSelection) }, enabled = docs)
                }
            }
        }
    }
}

/** Slim progress strip shown under the top bar while an import or export runs. */
@Composable
internal fun JobProgressBar(progress: com.inkwell.notes.library.LibraryJobs.Progress, onCancel: () -> Unit, horizontalPadding: androidx.compose.ui.unit.Dp) {
    val c = Inkwell.colors
    val label = when {
        progress.total <= 0 -> stringResource(R.string.lib_progress_importing_one)
        progress.kind == com.inkwell.notes.library.LibraryJobs.Kind.EXPORT ->
            stringResource(R.string.lib_progress_exporting, (progress.done + 1).coerceAtMost(progress.total), progress.total)
        else -> stringResource(R.string.lib_progress_importing, (progress.done + 1).coerceAtMost(progress.total), progress.total)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding).padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.inkMuted, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.lib_cancel),
                style = MaterialTheme.typography.labelLarge,
                color = c.accent,
                modifier = Modifier.clip(R8).clickable(role = Role.Button, onClick = onCancel).padding(horizontal = 10.dp, vertical = 13.dp),
            )
        }
        if (progress.total > 0) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = c.accent,
                trackColor = c.outline,
                drawStopIndicator = {},
            )
        } else {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = c.accent,
                trackColor = c.outline,
            )
        }
    }
}
