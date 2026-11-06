package com.inkwell.notes.editor.ui

import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import com.inkwell.notes.R
import com.inkwell.notes.app.AppNavigator
import com.inkwell.notes.audio.RecordingState
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenConfig
import com.inkwell.notes.core.model.PenPreset
import com.inkwell.notes.core.model.ScrollDirection
import com.inkwell.notes.core.model.SearchHit
import com.inkwell.notes.core.model.TextConfig
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.core.model.ToolbarPrefs
import com.inkwell.notes.editor.EditorSession
import com.inkwell.notes.editor.ImageImporter
import com.inkwell.notes.editor.ImagePlacement
import com.inkwell.notes.editor.NotebookController
import com.inkwell.notes.editor.TextEditRequest
import com.inkwell.notes.editor.buildOutlineRequest
import com.inkwell.notes.editor.catchingNonCancel
import com.inkwell.notes.editor.copySelection
import com.inkwell.notes.editor.copyTextToClipboard
import com.inkwell.notes.editor.generateOutline
import com.inkwell.notes.editor.logic.AnchorLocator
import com.inkwell.notes.editor.logic.EditorAction
import com.inkwell.notes.editor.logic.FragmentOps
import com.inkwell.notes.editor.logic.PageRanges
import com.inkwell.notes.editor.recognizeSelection
import com.inkwell.notes.editor.selectionAiContext
import com.inkwell.notes.editor.ui.pages.PaperChoice
import com.inkwell.notes.editor.ui.selection.SelectionAction
import com.inkwell.notes.editor.ui.toolbar.ColorTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/** Activity-result launchers a pane owns. */
class PaneLaunchers(
    val pickImage: () -> Unit,
    val takePicture: (Uri) -> Unit,
    val openPdf: () -> Unit,
    val requestMic: () -> Unit,
    val hasMicPermission: () -> Boolean,
)

/** Hooks the split screen gives each of its panes. */
class SplitHooks(
    val isLeft: Boolean,
    val onClosePane: () -> Unit,
    val onSendToOther: (content: PageContent, sourceDocId: String) -> Unit,
    val dragSink: com.inkwell.notes.editor.ui.selection.SelectionDragSink?,
)

/** Everything a pane does in response to the user, kept out of the layout code. */
class PaneActions(
    private val controller: NotebookController,
    private val ui: PaneUiState,
    private val nav: AppNavigator,
    private val mode: PaneMode,
    private val split: SplitHooks?,
    private val launchers: PaneLaunchers,
) {
    private val graph get() = controller.graph
    private val canvas get() = controller.canvas
    private val scope get() = controller.uiScope
    private fun str(id: Int, vararg args: Any) = controller.str(id, *args)
    private val messages get() = controller.messages

    // ------------------------------------------------------------------ Selection

    fun onSelectionAction(action: SelectionAction) {
        val sel = controller.selection ?: return
        when (action) {
            SelectionAction.Cut -> controller.copySelection(cut = true)
            SelectionAction.Copy -> controller.copySelection()
            SelectionAction.Duplicate -> canvas.duplicateSelection()
            SelectionAction.Delete -> canvas.deleteSelection()
            is SelectionAction.Recolor -> canvas.recolorSelection(action.color)
            is SelectionAction.Width -> canvas.setSelectionWidth(action.width)
            is SelectionAction.Opacity -> canvas.setSelectionOpacity(action.opacity)
            SelectionAction.Group -> canvas.groupSelection()
            SelectionAction.Ungroup -> canvas.ungroupSelection()
            SelectionAction.BringToFront -> canvas.bringSelectionToFront()
            SelectionAction.SendToBack -> canvas.sendSelectionToBack()
            SelectionAction.ConvertToText -> scope.launch {
                controller.recognizeSelection(sel)?.let { ui.dialog = PaneDialog.ConvertText(it) }
            }
            SelectionAction.CopyText -> scope.launch {
                controller.recognizeSelection(sel)?.let { controller.copyTextToClipboard(it.text) }
            }
            SelectionAction.SearchThis -> scope.launch {
                controller.recognizeSelection(sel)?.let { ui.openSearch(it.text.lineSequence().first().take(80)) }
            }
            SelectionAction.Straighten -> canvas.straightenSelection()
            SelectionAction.Reflow -> canvas.reflowSelection(null)
            is SelectionAction.WordSpacing -> canvas.adjustSelectionWordSpacing(action.factor)
            SelectionAction.Translate -> askAi(str(R.string.editor_prompt_translate, Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)), true)
            SelectionAction.Summarize -> askAi(str(R.string.editor_prompt_summarize), true)
            SelectionAction.Explain -> askAi(str(R.string.editor_prompt_explain), true)
            SelectionAction.AskAi -> askAi(null, false)
            SelectionAction.SaveElement -> ui.dialog = PaneDialog.SaveElement
            SelectionAction.PlayFromHere -> FragmentOps.firstAudioAnchor(sel.content)?.let(controller::playFrom)
            // copySelection normalises the fragment and freezes connector ends at their live
            // positions; the raw selection content would paste arrows with stale endpoints.
            SelectionAction.SendToOtherPane -> canvas.copySelection()?.let { split?.onSendToOther?.invoke(it, controller.docId) }
            SelectionAction.Crop -> ui.dialog = PaneDialog.Crop
        }
    }

    private fun askAi(prompt: String?, autoSend: Boolean) {
        val sel = controller.selection ?: return
        scope.launch { ui.openAi(controller.selectionAiContext(sel, prompt, autoSend)) }
    }

    fun convertSelection(text: String) {
        canvas.replaceSelectionWithText(text)
        ui.dialog = null
    }

    // ------------------------------------------------------------------ Text

    fun commitText(req: TextEditRequest, text: String, config: TextConfig) = controller.commitText(req, text, config)

    /** "Add text" from the long-press menu: a new box where the user pressed. */
    fun startTextAt(pageIndex: Int, pageX: Float, pageY: Float, viewX: Float, viewY: Float) {
        val page = controller.pages.getOrNull(pageIndex) ?: return
        val density = controller.appContext.resources.displayMetrics.density
        controller.textEdit = TextEditRequest(
            pageId = page.id, pageIndex = pageIndex, itemId = null, pageX = pageX, pageY = pageY,
            viewRect = RectF(viewX, viewY, viewX + 240 * density, viewY + 40 * density),
            initialText = "", config = controller.tools.state.text, pxPerPt = controller.estimatePxPerPt(),
        )
    }

    // ------------------------------------------------------------------ Images

    fun pickPhoto(placement: ImagePlacement?) {
        ui.pendingPlacement = placement
        ui.dialog = null
        runCatching { launchers.pickImage() }.onFailure { messages.show(str(R.string.editor_no_photo_picker)) }
    }

    fun takePhoto(placement: ImagePlacement?) {
        ui.pendingPlacement = placement
        ui.dialog = null
        val pm = controller.appContext.packageManager
        if (!pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)) {
            messages.show(str(R.string.editor_no_camera))
            return
        }
        runCatching {
            val (uri, file) = ImageImporter.newCameraUri(controller.appContext)
            ui.cameraFile = file
            launchers.takePicture(uri)
        }.onFailure {
            Log.w(TAG, "Camera unavailable: ${it.javaClass.simpleName}")
            messages.show(str(R.string.editor_no_camera))
        }
    }

    fun pasteImage(placement: ImagePlacement?) {
        ui.dialog = null
        val uri = ImageImporter.clipboardImageUri(controller.appContext)
        if (uri == null) messages.show(str(R.string.editor_no_clipboard_image)) else controller.insertImageFrom(uri, placement)
    }

    fun onPhotoPicked(uri: Uri?) {
        val placement = ui.pendingPlacement
        ui.pendingPlacement = null
        if (uri != null) controller.insertImageFrom(uri, placement)
    }

    fun onPhotoTaken(success: Boolean) {
        val file = ui.cameraFile
        val placement = ui.pendingPlacement
        ui.cameraFile = null
        ui.pendingPlacement = null
        if (file == null) return
        if (success && file.length() > 0) {
            scope.launch {
                // After process death the result arrives before the document is on the canvas.
                if (!controller.bound) snapshotFlow { controller.bound }.first { it }
                controller.insertImageFrom(Uri.fromFile(file), placement, deleteAfter = file)
            }
        } else {
            scope.launch(Dispatchers.IO) { file.delete() }
        }
    }

    // ------------------------------------------------------------------ Tools and settings

    fun applyCustomColor(target: ColorTarget, color: Int) {
        val t = controller.tools
        controller.updateTools(
            when (target) {
                ColorTarget.PEN -> t.updatePen(ToolType.PEN) { it.copy(color = color) }
                ColorTarget.PENCIL -> t.updatePen(ToolType.PENCIL) { it.copy(color = color) }
                ColorTarget.HIGHLIGHTER -> t.updatePen(ToolType.HIGHLIGHTER) { it.copy(color = color) }
                ColorTarget.SHAPE_STROKE -> t.updateShape { it.copy(strokeColor = color) }
                ColorTarget.SHAPE_FILL -> t.updateShape { it.copy(fillColor = color) }
                ColorTarget.TEXT -> t.updateText { it.copy(color = color) }
            },
        )
        ui.dialog = null
    }

    fun savePreset(name: String, tool: ToolType, pen: PenConfig) {
        ui.dialog = null
        graph.appScope.launch {
            catchingNonCancel {
                graph.settings.update { s -> s.copy(penPresets = s.penPresets + PenPreset(UUID.randomUUID().toString(), name, tool, pen)) }
            }.onFailure { messages.show(str(R.string.editor_settings_failed)) }
        }
    }

    fun renamePreset(preset: PenPreset, name: String) {
        ui.dialog = null
        graph.appScope.launch {
            catchingNonCancel {
                graph.settings.update { s -> s.copy(penPresets = s.penPresets.map { if (it.id == preset.id) it.copy(name = name) else it }) }
            }.onFailure { messages.show(str(R.string.editor_settings_failed)) }
        }
    }

    fun deletePreset(preset: PenPreset) {
        ui.dialog = null
        graph.appScope.launch {
            catchingNonCancel {
                graph.settings.update { s -> s.copy(penPresets = s.penPresets.filterNot { it.id == preset.id }) }
            }.onFailure { messages.show(str(R.string.editor_settings_failed)) }
        }
    }

    fun updateToolbar(prefs: ToolbarPrefs) {
        graph.appScope.launch {
            catchingNonCancel { graph.settings.update { it.copy(toolbar = prefs) } }.onFailure { messages.show(str(R.string.editor_settings_failed)) }
        }
    }

    fun toggleScrollDirection() {
        graph.appScope.launch {
            catchingNonCancel {
                graph.settings.update { s ->
                    val next = if (s.view.scrollDirection == ScrollDirection.VERTICAL) ScrollDirection.HORIZONTAL else ScrollDirection.VERTICAL
                    s.copy(view = s.view.copy(scrollDirection = next))
                }
            }.onFailure { messages.show(str(R.string.editor_settings_failed)) }
        }
    }

    // ------------------------------------------------------------------ Recording

    fun onRecord() {
        when (val st = graph.recording.state.value) {
            is RecordingState.Active -> ui.dialog = if (st.docId == controller.docId) PaneDialog.RecordingControls else PaneDialog.RecordingElsewhere(st.docId)
            else -> if (launchers.hasMicPermission()) startRecording() else launchers.requestMic()
        }
    }

    fun startRecording() {
        ui.dialog = null
        catchingNonCancel { graph.recording.start(controller.docId, null) }.onFailure {
            Log.w(TAG, "Recording failed to start: ${it.javaClass.simpleName}")
            messages.show(str(R.string.editor_record_failed))
        }
    }

    fun stopOtherAndRecordHere() {
        ui.dialog = null
        catchingNonCancel { graph.recording.stop() }
        if (launchers.hasMicPermission()) startRecording() else launchers.requestMic()
    }

    /** Highlights notes written at [positionMs] and scrolls to the first page that has them. */
    fun showNotesAt(recordingId: String, positionMs: Long) {
        canvas.setPlaybackHighlight(recordingId, positionMs)
        scope.launch {
            val pages = controller.pages
            val contents = pages.indices.map { i ->
                i to (canvas.pageContent(i) ?: withContext(Dispatchers.IO) {
                    catchingNonCancel { graph.pageStore.load(controller.docId, pages[i].id) }.getOrDefault(PageContent.EMPTY)
                })
            }
            val hit = withContext(Dispatchers.Default) { AnchorLocator.find(contents, recordingId, positionMs) }
            if (hit == null) {
                messages.show(str(R.string.editor_no_notes_at_time))
            } else {
                val b = hit.bounds
                // The closest notes may lie outside the window around the position: highlight what is revealed.
                canvas.setPlaybackHighlight(recordingId, AnchorLocator.highlightPosition(hit, positionMs))
                canvas.revealOnPage(hit.pageIndex, b.centerX, b.centerY, RectF(b.left, b.top, b.right, b.bottom))
            }
        }
    }

    // ------------------------------------------------------------------ Search

    fun revealHit(hit: SearchHit) {
        val index = hit.pageId?.let { id -> controller.pages.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: hit.pageIndex
        val b = hit.bounds
        when {
            index != null && b != null && b.size >= 4 -> canvas.revealOnPage(index, (b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f, RectF(b[0], b[1], b[2], b[3]))
            index != null -> controller.scrollToPage(index)
            hit.recordingId != null && hit.timestampMs != null -> scope.launch {
                val rec = withContext(Dispatchers.IO) { catchingNonCancel { graph.db.recordings().get(hit.recordingId!!)?.toModel() }.getOrNull() }
                if (rec != null) {
                    graph.playback.load(rec)
                    graph.playback.seekTo(hit.timestampMs!!)
                    graph.playback.play()
                    ui.right = RightPanel.RECORDINGS
                }
            }
        }
    }

    // ------------------------------------------------------------------ Document

    fun rename(title: String) {
        ui.dialog = null
        val t = title.trim()
        if (t.isEmpty() || t == controller.doc?.title) return
        controller.launchOp(R.string.editor_rename_failed) { graph.library.renameDocument(controller.docId, t) }
    }

    fun suggestName() {
        ui.dialog = null
        scope.launch {
            val r = controller.busy.run(str(R.string.editor_busy_naming)) { catchingNonCancel { graph.naming.renameNow(controller.docId) } }
            r.onSuccess { name -> messages.show(if (name == null) str(R.string.editor_name_not_enough) else str(R.string.editor_renamed_to, name)) }
                .onFailure { messages.show(str(R.string.editor_name_failed)) }
        }
    }

    fun deletePages(ids: List<String>) {
        ui.dialog = null
        if (!PageRanges.canDelete(ids.size, controller.pages.size)) {
            messages.show(str(R.string.editor_keep_one_page))
            return
        }
        controller.launchOp(R.string.editor_delete_failed) {
            // Forget each page's unsaved ink right before its own delete: if a later delete
            // fails, the surviving pages keep their pending edits.
            ids.forEach {
                controller.discardPending(listOf(it))
                graph.library.deletePage(it)
            }
            messages.show(controller.appContext.resources.getQuantityString(R.plurals.editor_pages_deleted, ids.size, ids.size))
        }
    }

    fun applyPaper(pageIds: List<String>, choice: PaperChoice) {
        ui.dialog = null
        controller.launchOp(R.string.editor_paper_failed) {
            val pages = controller.pages
            val targets = if (choice.applyToAll) pages else pages.filter { it.id in pageIds }
            if (choice.applyToAll) graph.library.setPaperForAll(controller.docId, choice.paper)
            else targets.forEach { graph.library.setPaper(it.id, choice.paper) }
            choice.sizePt?.let { (w, h) ->
                targets.filter { it.pdfPageIndex == null }.forEach { graph.library.setPageSize(it.id, w, h) }
            }
        }
    }

    fun copyPagesTo(ids: List<String>, target: DocumentMeta) {
        ui.dialog = null
        controller.launchOp(R.string.editor_copy_pages_failed) {
            controller.saveNow()
            ids.forEach { graph.library.copyPageToDocument(it, target.id) }
            messages.show(
                controller.appContext.resources.getQuantityString(R.plurals.editor_pages_copied, ids.size, ids.size, target.title),
                str(R.string.editor_open),
            ) { nav.openDocument(target) }
        }
    }

    fun bookmarkWithTitle(pageId: String, title: String) {
        ui.dialog = null
        controller.pages.firstOrNull { it.id == pageId }?.let { controller.toggleBookmark(it, title) }
    }

    fun addOutlineEntry(title: String) {
        ui.dialog = null
        val page = controller.currentPageMeta ?: return
        controller.launchOp(R.string.editor_outline_save_failed) {
            val existing = graph.library.observeOutline(controller.docId).first()
            graph.library.upsertOutlineEntry(
                OutlineEntry(UUID.randomUUID().toString(), controller.docId, title.ifBlank { str(R.string.editor_page_n, controller.currentPage + 1) }, page.id, null, (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1),
            )
        }
    }

    fun renameOutlineEntry(entry: OutlineEntry, title: String) {
        ui.dialog = null
        if (title.isBlank()) return
        controller.launchOp(R.string.editor_outline_save_failed) { graph.library.upsertOutlineEntry(entry.copy(title = title)) }
    }

    /** Builds the outline request, then confirms (if the privacy setting asks) and sends it. */
    fun requestAiOutline() {
        scope.launch {
            val request = controller.busy.run(str(R.string.editor_busy_preparing)) { catchingNonCancel { controller.buildOutlineRequest() }.getOrNull() }
            if (request == null) {
                messages.show(str(R.string.editor_outline_no_text), long = true)
                return@launch
            }
            val existing = catchingNonCancel { graph.library.observeOutline(controller.docId).first().size }.getOrDefault(0)
            if (graph.settings.current.privacy.confirmAiTransmission) {
                val ai = graph.ai
                val model = runCatching { ai.modelFor(AiTask.SUMMARIZE)?.let { ref -> ai.modelInfo(ref)?.displayName ?: ref.modelId } }.getOrNull()
                ui.dialog = PaneDialog.ConfirmAiOutline(
                    request,
                    runCatching { ai.estimateTokens(request) }.getOrDefault(0),
                    runCatching { ai.estimateCostUsd(request, 600) }.getOrNull(),
                    model,
                )
            } else {
                controller.generateOutline(request, existing)
            }
        }
    }

    fun confirmAiOutline(d: PaneDialog.ConfirmAiOutline) {
        ui.dialog = null
        scope.launch {
            val existing = catchingNonCancel { graph.library.observeOutline(controller.docId).first().size }.getOrDefault(0)
            controller.generateOutline(d.request, existing)
        }
    }

    // ------------------------------------------------------------------ PDF and documents

    fun openPdfPicker() {
        runCatching { launchers.openPdf() }.onFailure { messages.show(str(R.string.editor_no_file_picker)) }
    }

    fun insertPdf(uri: Uri) {
        scope.launch {
            val r = controller.busy.run(str(R.string.editor_busy_inserting_pdf)) {
                catchingNonCancel { graph.pdf.insertPdf(controller.docId, uri, controller.currentPage + 1) }
            }
            r.onSuccess { messages.show(str(R.string.editor_pdf_inserted)) }
                .onFailure { Log.w(TAG, "insertPdf failed: ${it.javaClass.simpleName}"); messages.show(str(R.string.editor_pdf_insert_failed)) }
        }
    }

    fun extractPages(indices: List<Int>) {
        ui.dialog = null
        if (indices.isEmpty()) return
        documentOp(R.string.editor_busy_extracting, R.string.editor_extract_failed, R.string.editor_extracted_to) {
            graph.pdf.extractPages(controller.docId, indices.sorted())
        }
    }

    fun splitHere() {
        ui.dialog = null
        val at = controller.currentPage
        if (at <= 0) {
            messages.show(str(R.string.editor_split_first_page))
            return
        }
        // The moved pages are copied, then deleted here: ink written meanwhile would be lost.
        documentOp(R.string.editor_busy_splitting, R.string.editor_split_failed, R.string.editor_split_to, lockInput = true) {
            graph.pdf.splitDocument(controller.docId, at)
        }
    }

    fun mergeWith(other: DocumentMeta) {
        ui.dialog = null
        documentOp(R.string.editor_busy_merging, R.string.editor_merge_failed, R.string.editor_merged_to) {
            graph.pdf.mergeDocuments(listOf(controller.docId, other.id))
        }
    }

    private fun documentOp(busyRes: Int, failRes: Int, doneRes: Int, lockInput: Boolean = false, block: suspend () -> DocumentMeta) {
        scope.launch {
            if (lockInput) controller.inputLocked = true
            val r = try {
                controller.saveNow()
                controller.busy.run(str(busyRes)) { catchingNonCancel { block() } }
            } finally {
                if (lockInput) controller.inputLocked = false
            }
            r.onSuccess { doc -> messages.show(str(doneRes, doc.title), str(R.string.editor_open)) { nav.openDocument(doc) } }
                .onFailure { Log.w(TAG, "Document operation failed: ${it.javaClass.simpleName}"); messages.show(str(failRes)) }
        }
    }

    fun openSideBySide(other: DocumentMeta) {
        ui.dialog = null
        if (other.kind != DocKind.NOTEBOOK && other.kind != DocKind.PDF) return
        EditorSession.splitOrigin = controller.docId
        nav.openSplit(controller.docId, other.id)
    }

    // ------------------------------------------------------------------ Keyboard

    fun onKey(action: EditorAction): Boolean {
        if (controller.inputLocked) return false
        when (action) {
            EditorAction.UNDO -> canvas.undo()
            EditorAction.REDO -> canvas.redo()
            EditorAction.FIND -> ui.openSearch()
            EditorAction.TOOL_PEN -> controller.selectTool(ToolType.PEN)
            EditorAction.TOOL_ERASER -> controller.selectTool(ToolType.ERASER)
            EditorAction.TOOL_HIGHLIGHTER -> controller.selectTool(ToolType.HIGHLIGHTER)
            EditorAction.TOOL_LASSO -> controller.selectTool(ToolType.LASSO)
            EditorAction.ADD_PAGE -> controller.addPage()
            EditorAction.ZOOM_IN -> canvas.zoomBy(1.25f)
            EditorAction.ZOOM_OUT -> canvas.zoomBy(0.8f)
            EditorAction.FOCUS_MODE -> if (mode == PaneMode.FULL) ui.focusMode = !ui.focusMode else return false
            EditorAction.BOOKMARK -> controller.toggleBookmark()
            EditorAction.COPY -> if (controller.selection != null) controller.copySelection() else return false
            EditorAction.CUT -> if (controller.selection != null) controller.copySelection(cut = true) else return false
            EditorAction.PASTE -> controller.paste(null, null)
            EditorAction.DUPLICATE -> if (controller.selection != null) canvas.duplicateSelection() else return false
            EditorAction.DELETE_SELECTION -> if (controller.selection != null) canvas.deleteSelection() else return false
            EditorAction.SELECT_ALL -> canvas.selectAll(controller.currentPage)
            EditorAction.ESCAPE -> when {
                controller.selection != null -> canvas.clearSelection()
                else -> return ui.closeTopmost()
            }
            EditorAction.PREV_PAGE -> controller.scrollToPage(controller.currentPage - 1)
            EditorAction.NEXT_PAGE -> controller.scrollToPage(controller.currentPage + 1)
        }
        return true
    }

    companion object {
        private const val TAG = "PaneActions"
    }
}
