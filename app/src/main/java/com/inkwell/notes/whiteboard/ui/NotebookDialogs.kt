package com.inkwell.notes.whiteboard.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.whiteboard.BoardOperations
import kotlinx.coroutines.launch

/** "Send to notebook page": choose a notebook (or a new one) for the current selection. */
@Composable
fun SendToNotebookDialog(ops: BoardOperations, onDismiss: () -> Unit, onSent: (DocumentMeta, PageMeta) -> Unit) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    val docs by produceState<List<DocumentMeta>?>(null) { value = runCatching { ops.documents(setOf(DocKind.NOTEBOOK)) }.getOrDefault(emptyList()) }
    fun send(target: DocumentMeta?) {
        if (working) return
        working = true
        scope.launch {
            val result = ops.sendSelectionToNotebook(target)
            working = false
            if (result != null) onSent(result.first, result.second)
        }
    }
    // Stays open while sending: closing it would cancel the send halfway.
    PickerShell(stringResource(R.string.wb_send_choose), { if (!working) onDismiss() }) {
        if (working) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Inkwell.colors.accent, strokeWidth = 2.dp)
            }
        } else {
            DocumentList(
                docs,
                stringResource(R.string.wb_no_notebooks),
                onPick = { send(it) },
                header = {
                    PickerRow(
                        icon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, null, tint = Inkwell.colors.accent, modifier = Modifier.size(Dimens.icon)) },
                        title = stringResource(R.string.wb_new_notebook),
                        subtitle = null,
                        onClick = { send(null) },
                    )
                },
            )
        }
    }
}

/** "Add notebook page as objects": choose a notebook or PDF, then a page. */
@Composable
fun AddNotebookPageDialog(ops: BoardOperations, loadPages: suspend (String) -> List<PageMeta>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<DocumentMeta?>(null) }
    var working by remember { mutableStateOf(false) }
    val docs by produceState<List<DocumentMeta>?>(null) {
        value = runCatching { ops.documents(setOf(DocKind.NOTEBOOK, DocKind.PDF)) }.getOrDefault(emptyList())
    }
    val pages by produceState<List<PageMeta>?>(null, doc) {
        value = null
        val d = doc ?: return@produceState
        value = runCatching { loadPages(d.id).sortedBy { it.index } }.getOrDefault(emptyList())
    }
    val current = doc
    if (current == null) {
        PickerShell(stringResource(R.string.wb_choose_notebook), onDismiss) {
            DocumentList(docs, stringResource(R.string.wb_no_notebooks), onPick = { doc = it })
        }
        return
    }
    // Stays open while adding: closing it would cancel the copy halfway.
    PickerShell(current.title, { if (!working) onDismiss() }, onBack = { if (!working) doc = null }) {
        val p = pages
        when {
            p == null || working -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Inkwell.colors.accent, strokeWidth = 2.dp)
            }
            p.isEmpty() -> Text(stringResource(R.string.wb_page_empty), style = MaterialTheme.typography.bodyMedium, color = Inkwell.colors.inkMuted, modifier = Modifier.padding(24.dp))
            else -> LazyVerticalGrid(GridCells.Adaptive(132.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                items(p, key = { it.id }) { page ->
                    val aspect = if (!page.isInfinite) page.widthPt / page.heightPt else 1f
                    PageThumbCell(
                        key = page.id,
                        aspect = aspect,
                        label = stringResource(R.string.wb_page_number, page.index + 1),
                        load = { ops.pageThumbnail(current, page, 280) },
                        onClick = {
                            if (!working) {
                                working = true
                                scope.launch {
                                    val ok = ops.addNotebookPage(current, page)
                                    working = false
                                    if (ok) onDismiss()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
