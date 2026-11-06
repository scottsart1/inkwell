package com.inkwell.notes.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.inkwell.notes.R
import com.inkwell.notes.app.AppNavigator
import com.inkwell.notes.library.FolderStyle
import com.inkwell.notes.library.LibraryText
import com.inkwell.notes.library.TrashEntry
import com.inkwell.notes.library.TrashModel
import com.inkwell.notes.library.TrashViewModel
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Trash: deleted documents and folders with restore, delete forever and empty trash. */
@Composable
internal fun TrashContent(vm: TrashViewModel, nav: AppNavigator) {
    val c = Inkwell.colors
    val entries by vm.entries.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    var confirmDelete by remember { mutableStateOf<TrashEntry?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMs = System.currentTimeMillis()
        }
    }
    LaunchedEffect(vm) {
        vm.messages.collect { m ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(m.text.resolve(resources), duration = SnackbarDuration.Short)
            }
        }
    }
    BackHandler { nav.back() }

    val list = entries
    Box(Modifier.fillMaxSize().background(c.ground).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LibIconButton(Icons.Outlined.ChevronLeft, stringResource(R.string.lib_back), nav::back)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.lib_trash_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = c.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(R.string.lib_trash_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.inkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!list.isNullOrEmpty()) {
                    Text(
                        stringResource(R.string.lib_trash_empty_action),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (vm.emptying) c.inkMuted else c.danger,
                        modifier = Modifier
                            .clip(R8)
                            .clickable(enabled = !vm.emptying, role = Role.Button) { confirmEmpty = true }
                            .padding(horizontal = 12.dp, vertical = 13.dp),
                    )
                }
            }
            Hairline()
            when {
                list == null -> Box(Modifier.fillMaxSize())
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        Icons.Outlined.Delete,
                        stringResource(R.string.lib_trash_empty_title),
                        stringResource(R.string.lib_trash_empty_body),
                        stringResource(R.string.lib_empty_action_back),
                        nav::back,
                    )
                }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        Modifier.widthIn(max = 880.dp).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(list, key = { it.key }) { entry ->
                            TrashRow(
                                entry = entry,
                                nowMs = nowMs,
                                busy = entry.key in vm.busy || vm.emptying,
                                onRestore = { vm.restore(entry) },
                                onDelete = { confirmDelete = entry },
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp)) { data ->
            Snackbar(data, shape = R12, containerColor = c.ink, contentColor = c.ground, actionColor = c.accentContainer)
        }
    }

    confirmDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.lib_trash_confirm_delete, entry.title),
            body = stringResource(
                if (entry is TrashEntry.FolderGroup) R.string.lib_trash_confirm_delete_folder_body else R.string.lib_trash_confirm_delete_body,
            ),
            confirmLabel = stringResource(R.string.lib_trash_delete_forever),
            onConfirm = {
                confirmDelete = null
                vm.deleteForever(entry)
            },
            onDismiss = { confirmDelete = null },
        )
    }
    if (confirmEmpty) {
        val count = TrashModel.itemCount(list.orEmpty())
        ConfirmDialog(
            title = stringResource(R.string.lib_trash_confirm_empty),
            body = pluralStringResource(R.plurals.lib_trash_confirm_empty_body, count, count),
            confirmLabel = stringResource(R.string.lib_trash_empty_action),
            onConfirm = {
                confirmEmpty = false
                vm.emptyTrash()
            },
            onDismiss = { confirmEmpty = false },
        )
    }
}

@Composable
private fun TrashRow(entry: TrashEntry, nowMs: Long, busy: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
    val c = Inkwell.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(R12)
            .background(c.surface)
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint) = when (entry) {
            is TrashEntry.Doc -> kindIcon(entry.doc.kind) to c.inkMuted
            is TrashEntry.FolderGroup -> FolderStyle.icon(entry.folder.icon) to FolderStyle.color(entry.folder.color, c.inkMuted)
        }
        Box(Modifier.size(40.dp).clip(R8).background(c.surfaceAlt), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(Dimens.icon), tint = tint)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = when (entry) {
                is TrashEntry.Doc -> LibraryText.kindLabel(entry.doc.kind)
                is TrashEntry.FolderGroup -> LibraryText.folderLine(entry.itemCount)
            }
            Text(
                detail + LibraryText.SEPARATOR + LibraryText.trashLine(entry.trashedAt, nowMs),
                style = MaterialTheme.typography.bodySmall,
                color = c.inkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.lib_trash_restore),
            style = MaterialTheme.typography.labelLarge,
            color = if (busy) c.inkMuted else c.accent,
            modifier = Modifier
                .clip(R8)
                .clickable(enabled = !busy, role = Role.Button, onClick = onRestore)
                .padding(horizontal = 12.dp, vertical = 13.dp),
        )
        LibIconButton(Icons.Outlined.DeleteForever, stringResource(R.string.lib_trash_delete_forever), onDelete, enabled = !busy, tint = c.danger)
    }
}
