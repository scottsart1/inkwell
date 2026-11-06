package com.inkwell.notes.editor.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Texture
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.inkwell.notes.R
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.editor.NotebookController
import com.inkwell.notes.editor.copyPageTextToClipboard
import com.inkwell.notes.editor.logic.PageRanges
import com.inkwell.notes.editor.ui.MenuItemSpec
import com.inkwell.notes.editor.ui.OverflowMenu
import com.inkwell.notes.editor.ui.components.IconAction
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Page-level requests that need a dialog or another component. */
class PageCallbacks(
    val onPaper: (pageIds: List<String>) -> Unit,
    val onExport: (indices: List<Int>) -> Unit,
    val onCopyTo: (pageIds: List<String>) -> Unit,
    val onExtract: (indices: List<Int>) -> Unit,
    val onDelete: (pageIds: List<String>) -> Unit,
    val onBookmark: (PageMeta) -> Unit,
)

/** Thumbnails of every page: tap to go, long-press and drag to reorder, select several to act on. */
@Composable
fun PagesPanelContent(controller: NotebookController, callbacks: PageCallbacks, onNavigate: (Int) -> Unit) {
    val c = Inkwell.colors
    val pages = controller.pages
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val gridState = rememberLazyGridState()
    val reorder = remember(gridState) { GridReorder(gridState) }
    val order = if (reorder.draggingKey != null) reorder.order else pages.map { it.id }
    val byId = pages.associateBy { it.id }
    // Drop ids that no longer exist from the selection.
    LaunchedEffect(pages) { selected = selected.filterTo(HashSet()) { id -> pages.any { it.id == id } } }
    LaunchedEffect(controller.currentPage) {
        if (reorder.draggingKey == null && controller.currentPage in pages.indices) {
            val visible = gridState.layoutInfo.visibleItemsInfo.map { it.index }
            if (controller.currentPage !in visible) gridState.animateScrollToItem(controller.currentPage)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selecting) pluralStringResource(R.plurals.editor_pages_selected, selected.size, selected.size)
                else pluralStringResource(R.plurals.editor_page_count, pages.size, pages.size),
                style = MaterialTheme.typography.labelLarge,
                color = c.inkMuted,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            if (selecting) {
                IconAction(Icons.Outlined.SelectAll, stringResource(R.string.editor_select_all), { selected = pages.map { it.id }.toSet() })
            }
            TextButton(onClick = { selecting = !selecting; selected = emptySet() }) {
                Text(stringResource(if (selecting) R.string.editor_done else R.string.editor_select))
            }
        }
        HorizontalDivider(color = c.outline)
        val density = LocalDensity.current
        val edgePx = with(density) { 56.dp.toPx() }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(118.dp),
            state = gridState,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .weight(1f)
                .then(
                    if (selecting) Modifier else Modifier.pointerInput(reorder) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { reorder.start(it, controller.pages.map { p -> p.id }) },
                            onDragEnd = {
                                val order = reorder.finish()
                                if (order != null && PageRanges.isReorder(controller.pages, order)) {
                                    controller.launchOp(R.string.editor_reorder_failed) { controller.graph.library.reorderPages(controller.docId, order) }
                                }
                            },
                            onDragCancel = { reorder.cancel() },
                        ) { change, amount ->
                            change.consume()
                            reorder.drag(amount, size.height.toFloat(), edgePx)
                        }
                    },
                ),
        ) {
            itemsIndexed(order.mapNotNull { byId[it] }, key = { _, p -> p.id }) { index, page ->
                val dragging = reorder.draggingKey == page.id
                PageCell(
                    controller = controller,
                    page = page,
                    index = index,
                    selecting = selecting,
                    selected = page.id in selected,
                    dragging = dragging,
                    displacement = if (dragging) reorder.displacement() else Offset.Zero,
                    onTap = {
                        if (reorder.justDropped()) return@PageCell
                        if (selecting) selected = PageRanges.toggle(selected, page.id) else onNavigate(index)
                    },
                    callbacks = callbacks,
                    modifier = if (dragging) Modifier.zIndex(1f) else Modifier.animateItem(),
                )
            }
        }
        LaunchedEffect(reorder.draggingKey) {
            while (isActive && reorder.draggingKey != null) {
                val speed = reorder.autoScrollSpeed
                if (speed != 0f) gridState.scrollBy(speed)
                delay(16)
            }
        }
        if (selecting) SelectionActions(controller, selected, callbacks) { selecting = false; selected = emptySet() }
    }
}

@Composable
private fun PageCell(
    controller: NotebookController,
    page: PageMeta,
    index: Int,
    selecting: Boolean,
    selected: Boolean,
    dragging: Boolean,
    displacement: Offset,
    onTap: () -> Unit,
    callbacks: PageCallbacks,
    modifier: Modifier,
) {
    val c = Inkwell.colors
    var menuOpen by remember { mutableStateOf(false) }
    val label = stringResource(R.string.editor_page_n, index + 1)
    Column(
        modifier
            .graphicsLayer {
                translationX = displacement.x
                translationY = displacement.y
                if (dragging) { scaleX = 1.04f; scaleY = 1.04f; alpha = 0.92f }
            }
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onTap)
            .semantics { contentDescription = label }
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PageThumbnail(controller, page, index, current = index == controller.currentPage && !selecting, selecting = selecting, selected = selected)
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${index + 1}" + (page.bookmarkTitle?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = if (index == controller.currentPage) c.accent else c.inkMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (!selecting) {
                Box {
                    IconAction(Icons.Outlined.MoreVert, stringResource(R.string.editor_page_actions, index + 1), { menuOpen = true }, visual = 30.dp, iconSize = 18.dp)
                    OverflowMenu(menuOpen, { menuOpen = false }, pageMenu(controller, page, index, callbacks))
                }
            }
        }
    }
}

@Composable
private fun pageMenu(controller: NotebookController, page: PageMeta, index: Int, callbacks: PageCallbacks): List<MenuItemSpec> {
    val lib = controller.graph.library
    return listOf(
        MenuItemSpec(stringResource(R.string.editor_insert_before), Icons.AutoMirrored.Outlined.NoteAdd, 0) {
            controller.addPage(PageRanges.insertionIndex(index, after = false, count = controller.pages.size))
        },
        MenuItemSpec(stringResource(R.string.editor_insert_after), Icons.Outlined.PostAdd, 0) {
            controller.addPage(PageRanges.insertionIndex(index, after = true, count = controller.pages.size))
        },
        MenuItemSpec(stringResource(R.string.editor_duplicate_page), Icons.Outlined.CopyAll, 1) {
            // The copy is made from disk: write the page's latest ink first.
            controller.launchOp(R.string.editor_duplicate_failed) { controller.saveNow(); lib.duplicatePage(page.id) }
        },
        MenuItemSpec(stringResource(R.string.editor_rotate_page), Icons.AutoMirrored.Outlined.RotateRight, 1) {
            controller.launchOp(R.string.editor_rotate_failed) { lib.rotatePage(page.id, 90) }
        },
        MenuItemSpec(
            stringResource(if (page.bookmarked) R.string.editor_remove_bookmark else R.string.editor_add_bookmark),
            if (page.bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
            1,
        ) { if (page.bookmarked) controller.toggleBookmark(page) else callbacks.onBookmark(page) },
        MenuItemSpec(stringResource(R.string.editor_paper_and_size), Icons.Outlined.Texture, 2) { callbacks.onPaper(listOf(page.id)) },
        MenuItemSpec(stringResource(R.string.editor_export_page), Icons.Outlined.IosShare, 2) { callbacks.onExport(listOf(index)) },
        MenuItemSpec(stringResource(R.string.editor_copy_to_notebook), Icons.AutoMirrored.Outlined.DriveFileMove, 2) { callbacks.onCopyTo(listOf(page.id)) },
        MenuItemSpec(stringResource(R.string.editor_copy_page_text), Icons.Outlined.ContentCopy, 2) { controller.copyPageTextToClipboard(index) },
        MenuItemSpec(stringResource(R.string.editor_delete_page), Icons.Outlined.DeleteOutline, 3, enabled = controller.pages.size > 1) {
            callbacks.onDelete(listOf(page.id))
        },
    )
}

@Composable
private fun SelectionActions(controller: NotebookController, selected: Set<String>, callbacks: PageCallbacks, onFinished: () -> Unit) {
    val c = Inkwell.colors
    val pages = controller.pages
    val ids = PageRanges.orderedSelection(pages, selected).map { it.id }
    val indices = PageRanges.indicesOf(pages, selected)
    val lib = controller.graph.library
    val enabled = ids.isNotEmpty()
    HorizontalDivider(color = c.outline)
    Row(
        Modifier.fillMaxWidth().background(c.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(Icons.Outlined.DeleteOutline, stringResource(R.string.editor_delete_pages), {
            callbacks.onDelete(ids)
        }, enabled = enabled && PageRanges.canDelete(ids.size, pages.size))
        IconAction(Icons.Outlined.CopyAll, stringResource(R.string.editor_duplicate_pages), {
            controller.launchOp(R.string.editor_duplicate_failed) { controller.saveNow(); ids.forEach { lib.duplicatePage(it) } }
            onFinished()
        }, enabled = enabled)
        IconAction(Icons.AutoMirrored.Outlined.RotateRight, stringResource(R.string.editor_rotate_pages), {
            controller.launchOp(R.string.editor_rotate_failed) { ids.forEach { lib.rotatePage(it, 90) } }
        }, enabled = enabled)
        IconAction(Icons.Outlined.IosShare, stringResource(R.string.editor_export_pages), { callbacks.onExport(indices) }, enabled = enabled)
        IconAction(Icons.AutoMirrored.Outlined.DriveFileMove, stringResource(R.string.editor_copy_to_notebook), { callbacks.onCopyTo(ids) }, enabled = enabled)
        IconAction(Icons.AutoMirrored.Outlined.CallSplit, stringResource(R.string.editor_extract_pages), { callbacks.onExtract(indices) }, enabled = enabled)
        IconAction(Icons.Outlined.Texture, stringResource(R.string.editor_paper_and_size), { callbacks.onPaper(ids) }, enabled = enabled)
    }
}

/** Long-press drag reordering for a lazy grid (positions in viewport pixels). */
class GridReorder(private val grid: LazyGridState) {
    var order by mutableStateOf<List<String>>(emptyList())
    var draggingKey by mutableStateOf<String?>(null)
        private set
    private var currentIndex by mutableIntStateOf(-1)
    private var initialOffset = IntOffset.Zero
    private var delta by mutableStateOf(Offset.Zero)
    var autoScrollSpeed by mutableFloatStateOf(0f)
        private set
    private var droppedAt = 0L
    private var startOrder: List<String> = emptyList()

    private fun itemAt(p: Offset): LazyGridItemInfo? = grid.layoutInfo.visibleItemsInfo.firstOrNull {
        p.x >= it.offset.x && p.x <= it.offset.x + it.size.width && p.y >= it.offset.y && p.y <= it.offset.y + it.size.height
    }

    fun start(p: Offset, ids: List<String>) {
        val item = itemAt(p) ?: return
        order = ids
        draggingKey = item.key as? String ?: return
        currentIndex = item.index
        initialOffset = item.offset
        delta = Offset.Zero
        startOrder = order
    }

    fun drag(amount: Offset, viewportHeight: Float, edgePx: Float) {
        val key = draggingKey ?: return
        delta += amount
        val info = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val center = Offset(initialOffset.x + delta.x + info.size.width / 2f, initialOffset.y + delta.y + info.size.height / 2f)
        val target = itemAt(center)
        if (target != null && target.index != currentIndex && target.key != key) {
            order = PageRanges.move(order, currentIndex, target.index)
            currentIndex = target.index
        }
        autoScrollSpeed = when {
            center.y < edgePx -> -12f
            center.y > viewportHeight - edgePx -> 12f
            else -> 0f
        }
    }

    /** Translation that keeps the dragged item under the finger. */
    fun displacement(): Offset {
        val key = draggingKey ?: return Offset.Zero
        val info = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return delta
        return Offset(initialOffset.x + delta.x - info.offset.x, initialOffset.y + delta.y - info.offset.y)
    }

    /** Ends the drag; returns the new order if it changed. */
    fun finish(): List<String>? {
        val changed = order != startOrder
        reset()
        droppedAt = System.currentTimeMillis()
        return if (changed) order else null
    }

    fun cancel() {
        order = startOrder
        reset()
    }

    private fun reset() {
        draggingKey = null
        currentIndex = -1
        delta = Offset.Zero
        autoScrollSpeed = 0f
    }

    /** True right after a drop, so the release does not also count as a tap. */
    fun justDropped(): Boolean = System.currentTimeMillis() - droppedAt < 350
}

