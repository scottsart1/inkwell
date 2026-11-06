package com.inkwell.notes.editor.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.audio.RecordingState
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.PenPreset
import com.inkwell.notes.editor.NotebookController
import com.inkwell.notes.editor.catchingNonCancel
import com.inkwell.notes.editor.saveSelectionAsElement
import com.inkwell.notes.editor.ui.dialogs.AiSendConfirmDialog
import com.inkwell.notes.editor.ui.dialogs.ConfirmDialog
import com.inkwell.notes.editor.ui.dialogs.DocumentInfoDialog
import com.inkwell.notes.editor.ui.dialogs.DocumentPickerDialog
import com.inkwell.notes.editor.ui.dialogs.ImageSourceDialog
import com.inkwell.notes.editor.ui.dialogs.MicRationaleDialog
import com.inkwell.notes.editor.ui.dialogs.MyElementsDialog
import com.inkwell.notes.editor.ui.dialogs.PresetEditDialog
import com.inkwell.notes.editor.ui.dialogs.TextInputDialog
import com.inkwell.notes.editor.ui.pages.PaperDialog
import com.inkwell.notes.editor.ui.selection.ConvertTextDialog
import com.inkwell.notes.editor.ui.selection.CropDialog
import com.inkwell.notes.editor.ui.toolbar.ColorPickerDialog
import com.inkwell.notes.export.ui.ExportDialog
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** Renders the pane's current dialog. */
@Composable
fun PaneDialogHost(controller: NotebookController, ui: PaneUiState, actions: PaneActions, presets: List<PenPreset>, onRequestMic: () -> Unit) {
    val dialog = ui.dialog ?: return
    val dismiss = { ui.dialog = null }
    val graph = controller.graph
    val doc = controller.doc
    val context = LocalContext.current
    when (dialog) {
        PaneDialog.Rename -> TextInputDialog(
            title = stringResource(R.string.editor_rename_title),
            label = stringResource(R.string.editor_name),
            initial = doc?.title.orEmpty(),
            confirmLabel = stringResource(R.string.editor_rename),
            onConfirm = actions::rename,
            onDismiss = dismiss,
            extra = {
                TextButton(onClick = actions::suggestName, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.editor_suggest_name))
                }
            },
        )
        is PaneDialog.Paper -> {
            val page = controller.pages.firstOrNull { it.id == dialog.pageIds.firstOrNull() } ?: controller.currentPageMeta
            if (page == null) DismissNow(ui) else PaperDialog(page, dialog.pageIds.size, { actions.applyPaper(dialog.pageIds, it) }, dismiss)
        }
        PaneDialog.DocInfo -> if (doc != null) DocumentInfoDialog(graph, doc, controller.pages, controller.currentPage, dismiss) else DismissNow(ui)
        is PaneDialog.Export -> ExportDialog(controller.docId, dialog.indices, onDismiss = dismiss)
        PaneDialog.PickSplitPartner -> DocumentPickerDialog(
            graph, stringResource(R.string.editor_pick_side_by_side), setOf(controller.docId), setOf(DocKind.NOTEBOOK, DocKind.PDF),
            onPick = actions::openSideBySide, onDismiss = dismiss,
        )
        is PaneDialog.PickCopyTarget -> DocumentPickerDialog(
            graph, stringResource(R.string.editor_pick_copy_target), setOf(controller.docId), setOf(DocKind.NOTEBOOK, DocKind.PDF),
            onPick = { actions.copyPagesTo(dialog.pageIds, it) }, onDismiss = dismiss,
        )
        PaneDialog.PickMergePartner -> DocumentPickerDialog(
            graph, stringResource(R.string.editor_pick_merge), setOf(controller.docId), setOf(DocKind.NOTEBOOK, DocKind.PDF),
            onPick = actions::mergeWith, onDismiss = dismiss,
        )
        is PaneDialog.ConfirmDeletePages -> ConfirmDialog(
            title = androidx.compose.ui.res.pluralStringResource(R.plurals.editor_delete_pages_title, dialog.pageIds.size, dialog.pageIds.size),
            message = stringResource(R.string.editor_delete_pages_body),
            confirmLabel = stringResource(R.string.editor_delete),
            destructive = true,
            onConfirm = { actions.deletePages(dialog.pageIds) },
            onDismiss = dismiss,
        )
        PaneDialog.ConfirmSplitHere -> ConfirmDialog(
            title = stringResource(R.string.editor_split_title),
            message = stringResource(R.string.editor_split_body, controller.currentPage + 1),
            confirmLabel = stringResource(R.string.editor_split_action),
            onConfirm = actions::splitHere,
            onDismiss = dismiss,
        )
        is PaneDialog.BookmarkTitle -> TextInputDialog(
            title = stringResource(R.string.editor_add_bookmark),
            label = stringResource(R.string.editor_bookmark_title_optional),
            initial = "",
            confirmLabel = stringResource(R.string.editor_add_bookmark),
            allowEmpty = true,
            onConfirm = { actions.bookmarkWithTitle(dialog.pageId, it) },
            onDismiss = dismiss,
        )
        PaneDialog.AddOutlineEntry -> TextInputDialog(
            title = stringResource(R.string.editor_outline_add_current),
            label = stringResource(R.string.editor_outline_entry_title),
            initial = controller.currentPageMeta?.bookmarkTitle ?: stringResource(R.string.editor_page_n, controller.currentPage + 1),
            confirmLabel = stringResource(R.string.editor_add),
            onConfirm = actions::addOutlineEntry,
            onDismiss = dismiss,
        )
        is PaneDialog.RenameOutlineEntry -> TextInputDialog(
            title = stringResource(R.string.editor_rename),
            label = stringResource(R.string.editor_outline_entry_title),
            initial = dialog.entry.title,
            confirmLabel = stringResource(R.string.editor_save),
            onConfirm = { actions.renameOutlineEntry(dialog.entry, it) },
            onDismiss = dismiss,
        )
        is PaneDialog.ConfirmAiOutline -> AiSendConfirmDialog(dialog.model, dialog.tokens, dialog.costUsd, { actions.confirmAiOutline(dialog) }, dismiss)
        PaneDialog.MyElements -> {
            val flow = remember(controller) { controller.elements.observe().catch { emit(emptyList()) } }
            val elements by flow.collectAsState(initial = null)
            MyElementsDialog(
                elements = elements,
                onInsert = { e ->
                    ui.dialog = null
                    controller.uiScope.launch {
                        // Copying its images can fail (storage full): report it, never crash.
                        val fragment = catchingNonCancel { controller.elements.prepareForInsert(e, controller.docId) }.getOrNull()
                        if (fragment == null) controller.messages.show(controller.str(R.string.editor_element_unreadable))
                        else controller.canvas.insertItems(null, fragment, null, select = true)
                    }
                },
                onRename = { ui.dialog = PaneDialog.RenameElement(it) },
                onDelete = { e -> controller.launchOp(R.string.editor_element_delete_failed) { controller.elements.delete(e) } },
                onDismiss = dismiss,
            )
        }
        PaneDialog.SaveElement -> TextInputDialog(
            title = stringResource(R.string.editor_sel_save_element),
            label = stringResource(R.string.editor_name),
            initial = stringResource(R.string.editor_element_default_name),
            confirmLabel = stringResource(R.string.editor_save),
            onConfirm = { name -> ui.dialog = null; controller.saveSelectionAsElement(name) },
            onDismiss = dismiss,
        )
        is PaneDialog.RenameElement -> TextInputDialog(
            title = stringResource(R.string.editor_rename),
            label = stringResource(R.string.editor_name),
            initial = dialog.element.name,
            confirmLabel = stringResource(R.string.editor_save),
            onConfirm = { name ->
                ui.dialog = PaneDialog.MyElements
                controller.launchOp(R.string.editor_element_save_failed) { controller.elements.rename(dialog.element, name) }
            },
            onDismiss = { ui.dialog = PaneDialog.MyElements },
        )
        is PaneDialog.ConvertText -> ConvertTextDialog(dialog.result, actions::convertSelection, dismiss)
        PaneDialog.Crop -> CropDialog({ l, t, r, b -> ui.dialog = null; controller.canvas.cropSelectedImage(l, t, r, b) }, dismiss)
        is PaneDialog.CustomColor -> ColorPickerDialog(dialog.initial, dismiss) { actions.applyCustomColor(dialog.target, it) }
        is PaneDialog.SavePreset -> TextInputDialog(
            title = stringResource(R.string.editor_save_as_preset),
            label = stringResource(R.string.editor_preset_name),
            initial = "",
            confirmLabel = stringResource(R.string.editor_save),
            onConfirm = { actions.savePreset(it, dialog.tool, dialog.pen) },
            onDismiss = dismiss,
        )
        is PaneDialog.EditPreset -> PresetEditDialog(
            name = dialog.preset.name,
            onRename = { actions.renamePreset(dialog.preset, it) },
            onDelete = { actions.deletePreset(dialog.preset) },
            onDismiss = dismiss,
        )
        is PaneDialog.ImageSource -> ImageSourceDialog(
            onPhotos = { actions.pickPhoto(dialog.placement) },
            onCamera = { actions.takePhoto(dialog.placement) },
            onPaste = { actions.pasteImage(dialog.placement) },
            onDismiss = dismiss,
        )
        is PaneDialog.MicRationale -> MicRationaleDialog(
            permanentlyDenied = dialog.permanentlyDenied,
            onRetry = { ui.dialog = null; onRequestMic() },
            onOpenSettings = {
                ui.dialog = null
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            onDismiss = dismiss,
        )
        is PaneDialog.RecordingElsewhere -> ConfirmDialog(
            title = stringResource(R.string.editor_recording_elsewhere_title),
            message = stringResource(R.string.editor_recording_elsewhere_body),
            confirmLabel = stringResource(R.string.editor_recording_elsewhere_action),
            onConfirm = actions::stopOtherAndRecordHere,
            onDismiss = dismiss,
        )
        PaneDialog.RecordingControls -> {
            val state by graph.recording.state.collectAsState()
            val active = state as? RecordingState.Active
            if (active == null) {
                DismissNow(ui)
            } else {
                AlertDialog(
                    onDismissRequest = dismiss,
                    title = { Text(stringResource(R.string.editor_recording_now)) },
                    text = {
                        Column {
                            Text(
                                NotebookController.formatClock(active.elapsedMs) + if (active.paused) " · " + stringResource(R.string.editor_recording_paused) else "",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Inkwell.colors.ink,
                            )
                            Text(stringResource(R.string.editor_recording_hint), style = MaterialTheme.typography.bodySmall, color = Inkwell.colors.inkMuted, modifier = Modifier.padding(top = 8.dp))
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { ui.dialog = null; graph.recording.stop() }) { Text(stringResource(R.string.editor_recording_stop), color = Inkwell.colors.record) }
                    },
                    dismissButton = {
                        TextButton(onClick = { ui.dialog = null; if (active.paused) graph.recording.resume() else graph.recording.pause() }) {
                            Text(stringResource(if (active.paused) R.string.editor_recording_resume else R.string.editor_recording_pause))
                        }
                    },
                )
            }
        }
    }
}

/** Closes a dialog whose subject disappeared, outside of composition. */
@Composable
private fun DismissNow(ui: PaneUiState) {
    androidx.compose.runtime.LaunchedEffect(Unit) { ui.dialog = null }
}
