package com.inkwell.notes.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.LongPressAction
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.ScrollDirection
import com.inkwell.notes.core.model.TextConfig
import com.inkwell.notes.core.model.ToolState
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.editor.logic.FragmentOps
import com.inkwell.notes.editor.logic.ImageMath
import com.inkwell.notes.editor.logic.PageDiff
import com.inkwell.notes.editor.logic.PageRanges
import com.inkwell.notes.editor.logic.Placement
import com.inkwell.notes.editor.logic.ToolSelection
import com.inkwell.notes.ink.CanvasViewState
import com.inkwell.notes.ink.InkCanvasListener
import com.inkwell.notes.ink.InkCanvasView
import com.inkwell.notes.ink.InkDocumentSource
import com.inkwell.notes.ink.InkSelection
import com.inkwell.notes.ink.ItemRef
import com.inkwell.notes.ink.ItemType
import com.inkwell.notes.ink.PageLayoutMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/** A text box (or sticky) being edited in the Compose overlay. */
data class TextEditRequest(
    val pageId: String,
    val pageIndex: Int,
    /** null = new box at ([pageX], [pageY]). */
    val itemId: String?,
    val pageX: Float,
    val pageY: Float,
    /** Where the box is on screen, in canvas view pixels. */
    val viewRect: RectF,
    val initialText: String,
    val config: TextConfig,
    /** Approximate on-screen scale, for showing the text at its real size while editing. */
    val pxPerPt: Float,
    val sticky: Boolean = false,
    val origin: String? = null,
)

data class LongPressRequest(val pageIndex: Int, val pageX: Float, val pageY: Float, val viewX: Float, val viewY: Float, val pasteOnly: Boolean)

/** Where an image chosen by the user goes: a page point, or the viewport centre when null. */
data class ImagePlacement(val pageIndex: Int?, val at: PointF?)

/**
 * Everything one open notebook/PDF needs: the canvas (created once per document), its
 * [InkDocumentSource] (loading, debounced autosave, backgrounds, images) and listener, and the
 * observable editor state the Compose UI renders. Lives as long as its pane's composition.
 */
@Stable
class NotebookController(
    val graph: AppGraph,
    override val docId: String,
    context: Context,
    private val initialPage: Int?,
    val uiScope: CoroutineScope,
) : InkDocumentSource, InkCanvasListener {

    val appContext: Context = context.applicationContext
    val canvas: InkCanvasView = InkCanvasView(context)
    val messages = EditorMessages(uiScope)
    val busy = BusyTracker()
    val elements = ElementsStore(graph)
    /** Page thumbnails keyed by "pageId@modifiedAt". */
    val thumbCache = android.util.LruCache<String, Bitmap>(48)

    var doc by mutableStateOf<DocumentMeta?>(null); private set
    /** The document does not exist (deleted, or a stale link). */
    var missing by mutableStateOf(false); private set
    var pages by mutableStateOf<List<PageMeta>>(emptyList()); private set
    var pagesLoaded by mutableStateOf(false); private set
    var currentPage by mutableIntStateOf(0); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var selection by mutableStateOf<InkSelection?>(null); private set
    var tools by mutableStateOf(ToolSelection.initial(graph.settings.current.lastTools)); private set
    var viewState by mutableStateOf(CanvasViewState()); private set
    var bound by mutableStateOf(false); private set

    var textEdit by mutableStateOf<TextEditRequest?>(null)
    /** What the open [textEdit] overlay holds now, so leaving the editor does not lose it. */
    private var textDraft: Triple<TextEditRequest, String, TextConfig>? = null
    var longPress by mutableStateOf<LongPressRequest?>(null)
    var imagePlacement by mutableStateOf<ImagePlacement?>(null)
    /** True while pages are being moved out of this document (split): the canvas takes no input. */
    var inputLocked by mutableStateOf(false)

    private var disposed = false
    private var docSeen = false
    private var pendingScrollPageId: String? = null
    private var toolSaveJob: Job? = null
    @Volatile private var pendingToolState: ToolState? = null
    private val unreadablePages: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val docSaver = DocSaver.forDocument(graph, docId)
    private val saver get() = docSaver.scheduler
    private val saveErrorListener: (Throwable, Boolean) -> Unit = { _, willRetry ->
        if (!willRetry) messages.show(str(R.string.editor_save_failed), long = true)
    }

    init {
        canvas.listener = this
        docSaver.addErrorListener(saveErrorListener)
    }

    fun str(@StringRes id: Int, vararg args: Any): String = appContext.getString(id, *args)

    // ------------------------------------------------------------------ Data

    /** Collects the document and its pages until cancelled. */
    suspend fun observe() = coroutineScope {
        launch {
            graph.library.observeDocument(docId)
                .catch { e -> Log.e(TAG, "Document stream failed", e); messages.show(str(R.string.editor_load_failed), long = true) }
                .collect { onDocument(it) }
        }
        launch {
            graph.library.observePages(docId)
                .catch { e -> Log.e(TAG, "Page stream failed", e); messages.show(str(R.string.editor_load_failed), long = true) }
                .collect { onPages(it) }
        }
    }

    private fun onDocument(d: DocumentMeta?) {
        if (d == null) {
            missing = true
            if (docSeen) EditorSession.tabs.close(docId)
            return
        }
        docSeen = true
        missing = false
        doc = d
        EditorSession.tabs.open(docId, d.title)
        tryBind()
    }

    private fun onPages(list: List<PageMeta>) {
        // Autosave (modifiedAt) and the thumbnail service emit a new list a few hundred
        // milliseconds after every stroke; only structural changes reach the canvas.
        val structural = !PageDiff.isBookkeepingOnly(pages, list)
        pages = list
        pagesLoaded = true
        if (bound) { if (structural) canvas.updatePages(list) } else tryBind()
        currentPage = PageRanges.clampIndex(currentPage, list.size)
        pendingScrollPageId?.let { id ->
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0 && bound) {
                pendingScrollPageId = null
                canvas.scrollToPage(i, smooth = true)
            }
        }
    }

    private fun tryBind() {
        val d = doc ?: return
        if (bound || disposed || !pagesLoaded) return
        val s = graph.settings.current
        val initial = when {
            initialPage != null -> CanvasViewState(pageIndex = PageRanges.clampIndex(initialPage, pages.size))
            s.view.reopenAtLastPosition -> CanvasViewState(PageRanges.clampIndex(d.lastPageIndex, pages.size), d.lastZoom, d.lastScrollX, d.lastScrollY)
            else -> null
        }
        canvas.setInputPrefs(s.input)
        canvas.setToolState(tools.state)
        canvas.setAudioClock(graph.recording.clock)
        canvas.bind(this, pages, layoutModeFor(s.view.scrollDirection), initial)
        bound = true
        if (initialPage != null && pages.isNotEmpty()) canvas.scrollToPage(PageRanges.clampIndex(initialPage, pages.size), smooth = false)
        initial?.let { currentPage = it.pageIndex }
    }

    fun layoutModeFor(direction: ScrollDirection): PageLayoutMode =
        if (direction == ScrollDirection.HORIZONTAL) PageLayoutMode.HORIZONTAL else PageLayoutMode.VERTICAL

    val currentPageMeta: PageMeta? get() = pages.getOrNull(currentPage)

    // ------------------------------------------------------------------ InkDocumentSource

    override suspend fun loadContent(page: PageMeta): PageContent {
        saver.latest(page.id)?.let { return it.content }
        return try {
            graph.pageStore.load(docId, page.id, strict = true).also { unreadablePages.remove(page.id) }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "Loading page ${page.id} failed: ${t.javaClass.simpleName}")
            unreadablePages += page.id
            messages.show(str(R.string.editor_page_unreadable), long = true)
            PageContent.EMPTY
        }
    }

    override fun onContentChanged(page: PageMeta, content: PageContent) {
        if (disposed) return
        // Never overwrite a page whose stored content could not be read.
        if (page.id in unreadablePages) return
        saver.submit(page.id, PendingPageSave(page, content))
    }

    override suspend fun renderBackground(page: PageMeta, widthPx: Int, heightPx: Int): Bitmap? {
        val index = page.pdfPageIndex ?: return null
        return try {
            graph.pdf.renderPage(docId, index, widthPx, heightPx)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "PDF page $index could not be rendered: ${t.javaClass.simpleName}")
            null
        }
    }

    override suspend fun loadImage(assetId: String): Bitmap? = withContext(Dispatchers.IO) {
        ImageImporter.decodeFile(graph.files.assetFile(docId, assetId), ImageMath.MAX_DECODE_PX)
    }

    /** Commits the canvas and waits until every pending page is on disk (before exports and copies). */
    suspend fun saveNow() {
        if (bound && !disposed) runCatching { canvas.flush() }
        saver.flushAll().join()
    }

    /** Forgets unsaved content of pages that are being deleted. */
    fun discardPending(pageIds: Collection<String>) = pageIds.forEach(saver::discard)

    // ------------------------------------------------------------------ InkCanvasListener

    override fun onSelectionChanged(selection: InkSelection?) { this.selection = selection }

    override fun onUndoRedoChanged(canUndo: Boolean, canRedo: Boolean) {
        this.canUndo = canUndo
        this.canRedo = canRedo
    }

    override fun onViewportChanged(state: CanvasViewState) { viewState = state }

    override fun onCurrentPageChanged(pageIndex: Int) { currentPage = PageRanges.clampIndex(pageIndex, pages.size) }

    override fun onTemporaryToolChanged(tool: ToolType?) { tools = tools.withTemporary(tool) }

    override fun onToolChangeRequested(tool: ToolType) = updateTools(tools.onToolChangeRequested(tool))

    override fun onLongPress(pageIndex: Int, pageX: Float, pageY: Float, viewX: Float, viewY: Float) {
        when (graph.settings.current.input.longPress) {
            LongPressAction.CONTEXT_MENU -> longPress = LongPressRequest(pageIndex, pageX, pageY, viewX, viewY, pasteOnly = false)
            LongPressAction.PASTE_MENU -> longPress = LongPressRequest(pageIndex, pageX, pageY, viewX, viewY, pasteOnly = true)
            LongPressAction.LASSO -> selectTool(ToolType.LASSO)
            LongPressAction.NONE -> Unit
        }
    }

    override fun onTextEditRequested(pageId: String, itemId: String?, pageX: Float, pageY: Float, viewRect: RectF) {
        val index = pages.indexOfFirst { it.id == pageId }.takeIf { it >= 0 } ?: currentPage
        val existing = itemId?.let { id -> canvas.pageContent(index)?.texts?.firstOrNull { it.id == id } }
        val config = existing?.let { TextConfig(it.fontSizePt, it.color, it.bold, it.italic) } ?: tools.state.text
        val scale = existing?.takeIf { it.width > 0f && viewRect.width() > 0f }?.let { viewRect.width() / it.width } ?: estimatePxPerPt()
        textEdit = TextEditRequest(pageId, index, existing?.id, pageX, pageY, RectF(viewRect), existing?.text.orEmpty(), config, scale, origin = existing?.origin)
    }

    override fun onStickyEditRequested(pageId: String, itemId: String, viewRect: RectF) {
        val index = pages.indexOfFirst { it.id == pageId }.takeIf { it >= 0 } ?: currentPage
        val sticky = canvas.pageContent(index)?.stickies?.firstOrNull { it.id == itemId } ?: return
        val scale = if (sticky.width > 0f && viewRect.width() > 0f) viewRect.width() / sticky.width else estimatePxPerPt()
        textEdit = TextEditRequest(pageId, index, itemId, sticky.x, sticky.y, RectF(viewRect), sticky.text, tools.state.text, scale, sticky = true)
    }

    /** The text overlay reports every change, so a pane closed without Done still commits it. */
    fun onTextDraft(req: TextEditRequest, text: String, config: TextConfig) {
        textDraft = Triple(req, text, config)
    }

    /** Closes the text overlay and applies [text] to the box (or sticky) it was editing. */
    fun commitText(req: TextEditRequest, text: String, config: TextConfig) {
        textEdit = null
        textDraft = null
        when {
            req.sticky -> req.itemId?.let { canvas.updateSticky(ItemRef(req.pageId, it, ItemType.STICKY), text) }
            req.itemId == null -> if (text.isNotBlank()) canvas.insertText(req.pageIndex, text, PointF(req.pageX, req.pageY), config, req.origin)
            text.isBlank() -> {
                canvas.selectItems(req.pageId, listOf(req.itemId))
                canvas.deleteSelection()
            }
            else -> canvas.updateText(ItemRef(req.pageId, req.itemId, ItemType.TEXT), text, config)
        }
        // Only a format the user chose in the overlay becomes the text tool's default; fixing a
        // typo in an old 24pt red box must not turn every new box 24pt red.
        if (!req.sticky && config != req.config && config != tools.state.text) updateTools(tools.updateText { config })
    }

    /** Commits what is typed in a still-open text overlay (the app is stopped or the pane closes). */
    private fun commitPendingText() {
        val req = textEdit ?: return
        val (draftReq, text, config) = textDraft ?: return
        if (draftReq !== req || (text == req.initialText && config == req.config)) return
        commitText(req, text, config)
    }

    override fun onImagePlacementRequested(pageIndex: Int, pageX: Float, pageY: Float) {
        imagePlacement = ImagePlacement(pageIndex, PointF(pageX, pageY))
    }

    override fun onItemTapped(ref: ItemRef) {
        val anchor = anchorOf(ref) ?: return
        messages.show(str(R.string.editor_written_during_recording, formatClock(anchor.offsetMs)), str(R.string.editor_play_from_here)) {
            playFrom(anchor)
        }
    }

    private fun anchorOf(ref: ItemRef): AudioAnchor? {
        val index = pages.indexOfFirst { it.id == ref.pageId }.takeIf { it >= 0 } ?: return null
        val c = canvas.pageContent(index) ?: return null
        return when (ref.type) {
            ItemType.STROKE -> c.strokes.firstOrNull { it.id == ref.itemId }?.audio
            ItemType.SHAPE -> c.shapes.firstOrNull { it.id == ref.itemId }?.audio
            ItemType.TEXT -> c.texts.firstOrNull { it.id == ref.itemId }?.audio
            ItemType.IMAGE -> c.images.firstOrNull { it.id == ref.itemId }?.audio
            ItemType.STICKY -> c.stickies.firstOrNull { it.id == ref.itemId }?.audio
            else -> null
        }
    }

    fun estimatePxPerPt(): Float {
        val density = appContext.resources.displayMetrics.density
        val maxW = pages.maxOfOrNull { it.widthPt } ?: 595f
        return Placement.estimatePxPerPt(canvas.width.toFloat(), density, maxW, viewState.zoom)
    }

    // ------------------------------------------------------------------ Tools

    fun updateTools(next: ToolSelection) {
        if (next == tools) return
        val changed = next.state != tools.state
        tools = next
        if (changed) {
            canvas.setToolState(next.state)
            scheduleToolSave(next.state)
        }
    }

    fun selectTool(t: ToolType) = updateTools(tools.select(t))

    private fun scheduleToolSave(state: ToolState) {
        pendingToolState = state
        toolSaveJob?.cancel()
        toolSaveJob = graph.appScope.launch {
            delay(TOOL_SAVE_DEBOUNCE_MS)
            // Persist the state this timer was started for (a newer change cancelled this job);
            // reading pendingToolState from this worker thread could drop a change made on Main.
            persistTools(state)
            if (pendingToolState === state) pendingToolState = null
        }
    }

    /** Main thread: writes whatever has not been persisted yet (stop, dispose). */
    private fun saveToolsNow() {
        val state = pendingToolState ?: return
        pendingToolState = null
        persistTools(state)
    }

    private fun persistTools(state: ToolState) {
        graph.appScope.launch {
            runCatching { graph.settings.update { if (it.lastTools == state) it else it.copy(lastTools = state) } }
                .onFailure { Log.w(TAG, "Saving tools failed", it) }
        }
    }

    // ------------------------------------------------------------------ Playback

    /** Loads the anchor's recording and plays from the moment the item was written. */
    fun playFrom(anchor: AudioAnchor) {
        uiScope.launch {
            val rec = withContext(Dispatchers.IO) { runCatching { graph.db.recordings().get(anchor.recordingId)?.toModel() }.getOrNull() }
            if (rec == null) {
                messages.show(str(R.string.editor_recording_missing))
                return@launch
            }
            graph.playback.load(rec)
            graph.playback.seekTo(anchor.offsetMs)
            graph.playback.play()
        }
    }

    // ------------------------------------------------------------------ Content helpers

    /**
     * Makes [content] from [sourceDocId] safe to insert here: fresh ids, audio anchors kept only
     * within the same document, and referenced images copied into this document's assets.
     */
    suspend fun prepareFragment(content: PageContent, sourceDocId: String): PageContent = withContext(Dispatchers.IO) {
        val fresh = FragmentOps.withFreshIds(content, keepAudio = sourceDocId == docId)
        if (sourceDocId != docId) {
            for (assetId in FragmentOps.assetIds(fresh)) {
                val src = graph.files.assetFile(sourceDocId, assetId)
                val dst = graph.files.assetFile(docId, assetId)
                if (src.isFile && !dst.exists()) runCatching { src.copyTo(dst) }.onFailure { Log.w(TAG, "Asset copy failed", it) }
            }
        }
        fresh
    }

    /** Pastes the editor clipboard, else an image or text from the system clipboard. */
    fun paste(pageIndex: Int?, at: PointF?) {
        val clip = EditorSession.clipboard.value
        uiScope.launch {
            if (clip != null) {
                val fragment = prepareFragment(clip.content, clip.sourceDocId)
                canvas.insertItems(pageIndex, fragment, at, select = true)
                return@launch
            }
            val imageUri = ImageImporter.clipboardImageUri(appContext)
            if (imageUri != null) {
                insertImageFrom(imageUri, ImagePlacement(pageIndex, at))
                return@launch
            }
            val text = ImageImporter.clipboardText(appContext)
            if (text != null) {
                canvas.insertText(pageIndex, text, at, tools.state.text)
            } else {
                messages.show(str(R.string.editor_nothing_to_paste))
            }
        }
    }

    /** Uses only the clip description, so checking never triggers the system "pasted" notice. */
    fun hasSomethingToPaste(): Boolean = EditorSession.clipboard.value != null || ImageImporter.clipboardHasPasteable(appContext)

    /** Imports a picture into this document's assets and places it on the canvas. */
    fun insertImageFrom(uri: android.net.Uri, placement: ImagePlacement?, deleteAfter: File? = null) {
        uiScope.launch {
            val result = busy.run(str(R.string.editor_busy_image)) {
                catchingNonCancel { ImageImporter.importUri(appContext, graph.files, docId, uri) }
            }
            deleteAfter?.let { f -> withContext(Dispatchers.IO) { f.delete() } }
            result.onSuccess { img ->
                canvas.insertImage(placement?.pageIndex, img.assetId, img.width, img.height, placement?.at)
            }.onFailure {
                Log.w(TAG, "Image import failed: ${it.javaClass.simpleName}")
                messages.show(str(R.string.editor_image_failed))
            }
        }
    }

    /** Runs a library operation with error reporting. */
    fun launchOp(@StringRes failure: Int, block: suspend () -> Unit): Job = uiScope.launch {
        catchingNonCancel { block() }.onFailure {
            Log.w(TAG, "Operation failed: ${it.javaClass.simpleName}")
            messages.show(str(failure))
        }
    }

    /** Adds a page at [atIndex] (null = after the current page) and scrolls to it. */
    fun addPage(atIndex: Int? = null) = launchOp(R.string.editor_add_page_failed) {
        val index = atIndex ?: PageRanges.insertionIndex(currentPage, after = true, count = pages.size)
        val page = graph.library.addPage(docId, index)
        pendingScrollPageId = page.id
        val i = pages.indexOfFirst { it.id == page.id }
        if (i >= 0) { pendingScrollPageId = null; canvas.scrollToPage(i) }
    }

    fun toggleBookmark(page: PageMeta? = currentPageMeta, title: String? = null) {
        val p = page ?: return
        val on = !p.bookmarked
        launchOp(R.string.editor_bookmark_failed) {
            graph.library.setBookmark(p.id, on, if (on) title?.trim()?.ifEmpty { null } else null)
            val number = pages.indexOfFirst { it.id == p.id }.let { if (it >= 0) it + 1 else p.index + 1 }
            messages.show(if (on) str(R.string.editor_bookmark_added, number) else str(R.string.editor_bookmark_removed))
        }
    }

    fun scrollToPage(index: Int) {
        if (pages.isEmpty()) return
        canvas.scrollToPage(PageRanges.clampIndex(index, pages.size))
    }

    // ------------------------------------------------------------------ Lifecycle

    fun onPause() {
        if (!bound || disposed) return
        canvas.flush()
        saver.flushAll()
        saveViewState()
    }

    fun onStop() {
        if (disposed) return
        if (bound) runCatching { commitPendingText() }.onFailure { Log.e(TAG, "Text commit on stop failed", it) }
        saver.flushAll()
        toolSaveJob?.cancel()
        saveToolsNow()
    }

    private fun saveViewState() {
        if (!bound) return
        val vs = runCatching { canvas.viewState() }.getOrDefault(viewState)
        graph.appScope.launch {
            runCatching { graph.library.updateViewState(docId, vs.pageIndex, vs.zoom, vs.scrollX, vs.scrollY) }
                .onFailure { Log.w(TAG, "View state not saved", it) }
        }
    }

    /** Commits everything, persists the position, refreshes the thumbnail and frees the canvas. */
    fun dispose() {
        if (disposed) return
        val wasBound = bound
        if (wasBound) runCatching { commitPendingText() }.onFailure { Log.e(TAG, "Text commit on close failed", it) }
        if (wasBound) runCatching { canvas.flush() }.onFailure { Log.e(TAG, "Flush on close failed", it) }
        disposed = true
        val flushed = saver.flushAll()
        val vs = if (wasBound) runCatching { canvas.viewState() }.getOrDefault(viewState) else null
        toolSaveJob?.cancel()
        saveToolsNow()
        graph.appScope.launch {
            flushed.join()
            if (vs != null) runCatching { graph.library.updateViewState(docId, vs.pageIndex, vs.zoom, vs.scrollX, vs.scrollY) }
            if (!missing) runCatching { graph.thumbnails.refresh(docId) }
        }
        canvas.listener = null
        docSaver.removeErrorListener(saveErrorListener)
        thumbCache.evictAll()
        runCatching { canvas.setPlaybackHighlight(null, 0) }
        runCatching { canvas.release() }
        bound = false
    }

    companion object {
        private const val TAG = "NotebookController"
        const val TOOL_SAVE_DEBOUNCE_MS = 600L

        fun formatClock(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
