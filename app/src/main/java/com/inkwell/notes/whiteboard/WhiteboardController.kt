package com.inkwell.notes.whiteboard

import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.core.model.TextConfig
import com.inkwell.notes.core.model.ToolState
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.CanvasViewState
import com.inkwell.notes.ink.InkCanvasListener
import com.inkwell.notes.ink.InkCanvasView
import com.inkwell.notes.ink.InkSelection
import com.inkwell.notes.ink.ItemRef
import com.inkwell.notes.ink.ItemType
import com.inkwell.notes.ink.PageLayoutMode
import com.inkwell.notes.whiteboard.kit.UiMessage
import com.inkwell.notes.whiteboard.logic.BoardGeometry
import com.inkwell.notes.whiteboard.logic.Box
import com.inkwell.notes.whiteboard.logic.MinimapMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Request to edit (or create) a text box over the canvas. Rect in canvas-view pixels. */
data class TextEditRequest(
    val pageId: String,
    val itemId: String?,
    val pageX: Float,
    val pageY: Float,
    val viewRect: RectF,
    val initialText: String,
    val config: TextConfig,
    /** View pixels per board point at the current zoom. */
    val pxPerPt: Float,
)

data class StickyEditRequest(val pageId: String, val itemId: String, val viewRect: RectF, val initialText: String, val color: Int)

data class LongPressRequest(val pageX: Float, val pageY: Float, val viewX: Float, val viewY: Float)

/** A rendered overview of the board for the minimap: [image] covers [bounds] (board points). */
class MinimapImage(val image: ImageBitmap, val bounds: Box)

/**
 * State and canvas wiring of one open whiteboard. Plain class with Compose state, owned by
 * [WhiteboardScreen]; long operations live in [BoardOperations].
 */
class WhiteboardController(val graph: AppGraph, val docId: String) : InkCanvasListener {

    sealed interface LoadState {
        data object Loading : LoadState
        data object Missing : LoadState
        data object Failed : LoadState
        data object Ready : LoadState
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val boardSaver = BoardSaver.forDocument(graph, docId)
    val source = BoardSource(
        graph, docId, boardSaver,
        onLoaded = { _, content -> scope.launch { onContent(content, delayMs = 0) } },
        onChanged = { _, content -> onContent(content, delayMs = MINIMAP_DELAY_MS) },
    )
    val ops = BoardOperations(this)
    val audio = BoardAudio(this)

    var loadState by mutableStateOf<LoadState>(LoadState.Loading); private set
    var doc by mutableStateOf<DocumentMeta?>(null); private set
    var page by mutableStateOf<PageMeta?>(null); private set
    var tools by mutableStateOf(ToolState()); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var selection by mutableStateOf<InkSelection?>(null); private set
    var viewState by mutableStateOf(CanvasViewState()); private set
    /** Visible board region (points); null before the first layout. */
    var visible by mutableStateOf<Box?>(null); private set
    var temporaryTool by mutableStateOf<ToolType?>(null); private set
    var textEdit by mutableStateOf<TextEditRequest?>(null)
    var stickyEdit by mutableStateOf<StickyEditRequest?>(null)
    /** What the open text and sticky editors hold now, so leaving the board does not lose it. */
    private var textDraft: Triple<TextEditRequest, String, TextConfig>? = null
    private var stickyDraft: Triple<StickyEditRequest, String, Int>? = null
    var longPress by mutableStateOf<LongPressRequest?>(null)
    /** Board point where the next picked image should go (null = centre of the view). */
    var imageTarget: PointF? = null
    /** One event per "pick an image" request; the screen launches the photo picker. */
    val imageRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    var minimap by mutableStateOf<MinimapImage?>(null); private set
    /** Bounds of all content (board points), refreshed with the minimap. */
    var contentBox by mutableStateOf<Box?>(null); private set
    /** String resource of a running long operation, or null. */
    var busy by mutableStateOf<Int?>(null)

    val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)

    var canvas: InkCanvasView? = null; private set
    /** Newest content of the board page (main thread). */
    var latestContent: PageContent = PageContent.EMPTY; private set
    /** Minimap bitmap size in pixels (set by the minimap from its layout). */
    var minimapPx: Pair<Int, Int> = 400 to 280

    private var boundCanvas: InkCanvasView? = null
    private var released = false
    private var minimapJob: Job? = null
    private val minimapLock = Mutex()
    private var minimapShown = true
    private var viewSaveJob: Job? = null
    private var toolSaveJob: Job? = null
    private var lastSavedView: CanvasViewState? = null
    private val saveErrorListener: (Throwable, Boolean) -> Unit = { _, retry ->
        scope.launch { messages.tryEmit(UiMessage(if (retry) R.string.cd_save_retrying else R.string.cd_save_failed, long = !retry)) }
    }

    init {
        boardSaver.addErrorListener(saveErrorListener)
    }

    // ------------------------------------------------------------------ Loading & binding

    suspend fun load() {
        loadState = LoadState.Loading
        try {
            val d = graph.library.getDocument(docId)
            if (d == null || d.trashedAt != null) { loadState = LoadState.Missing; return }
            doc = d
            page = ensureBoardPage()
            tools = graph.settings.current.lastTools
            lastSavedView = CanvasViewState(0, d.lastZoom, d.lastScrollX, d.lastScrollY)
            loadState = LoadState.Ready
            maybeBind()
            audio.observe()
            // Stamps "opened" (for Recent) without marking the board modified.
            graph.appScope.launch { runCatching { graph.library.updateViewState(docId, 0, d.lastZoom, d.lastScrollX, d.lastScrollY) } }
            scope.launch {
                graph.library.observeDocument(docId).collect { if (it != null) doc = it }
            }
            scope.launch {
                graph.settings.settings.map { it.input }.distinctUntilChanged().collect { canvas?.setInputPrefs(it) }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "Loading whiteboard failed", t)
            loadState = LoadState.Failed
        }
    }

    /** The board's single infinite page, created (or made infinite) when needed. */
    private suspend fun ensureBoardPage(): PageMeta {
        val existing = graph.library.getPages(docId).minByOrNull { it.index }
        if (existing != null) {
            if (existing.isInfinite) return existing
            runCatching { graph.library.setPageSize(existing.id, 0f, 0f) }.onFailure { Log.w(TAG, "Could not make the page infinite", it) }
            return existing.copy(widthPt = 0f, heightPt = 0f)
        }
        val created = graph.library.addPage(docId)
        graph.library.setPageSize(created.id, 0f, 0f)
        runCatching { graph.library.setPaper(created.id, DEFAULT_PAPER) }
        return graph.library.getPage(created.id)?.takeIf { it.isInfinite }
            ?: created.copy(widthPt = 0f, heightPt = 0f, paper = DEFAULT_PAPER)
    }

    fun attachCanvas(view: InkCanvasView) {
        if (canvas === view) return
        canvas = view
        released = false
        view.listener = this
        view.setToolState(tools)
        view.setInputPrefs(graph.settings.current.input)
        // Items written while this board records get an anchor on the recording's timeline.
        view.setAudioClock(graph.recording.clock)
        maybeBind()
        audio.applyHighlight()
    }

    private fun maybeBind() {
        val c = canvas ?: return
        val p = page ?: return
        if (boundCanvas === c) return
        boundCanvas = c
        val restore = lastSavedView?.takeIf {
            graph.settings.current.view.reopenAtLastPosition && (it.zoom != 1f || it.scrollX != 0f || it.scrollY != 0f)
        }
        c.bind(source, listOf(p), PageLayoutMode.INFINITE, restore)
        c.setToolState(tools)
    }

    /** Commits in-progress strokes and writes everything pending (lifecycle pause). */
    fun flush() {
        runCatching { canvas?.flush() }
        boardSaver.saver.flushAsync()
        saveViewStateNow()
    }

    /** Leaves the screen: flush, release the canvas, stop observing. */
    fun dispose() {
        commitPendingEdits()
        flush()
        persistToolsNow()
        boardSaver.removeErrorListener(saveErrorListener)
        audio.dispose()
        releaseCanvas()
        graph.appScope.launch {
            boardSaver.saver.flush()
            runCatching { graph.thumbnails.refresh(docId) }
        }
        scope.cancel()
    }

    fun releaseCanvas() {
        if (released) return
        // The canvas view may be released before dispose(): commit open editors while it exists.
        commitPendingEdits()
        released = true
        canvas?.let { c ->
            runCatching { c.flush() }
            c.listener = null
            runCatching { c.release() }
        }
        canvas = null
        boundCanvas = null
    }

    // ------------------------------------------------------------------ Content tracking

    private fun onContent(content: PageContent, delayMs: Long) {
        latestContent = content
        minimapJob?.cancel()
        minimapJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (!minimapShown) {
                // Collapsed: only the bounds (fit-to-content falls back to them), no bitmap.
                contentBox = withContext(Dispatchers.Default) { BoardGeometry.contentBounds(content) }
                return@launch
            }
            val (w, h) = minimapPx
            // One render at a time: a superseded render finishes its current batch before it
            // stops, and the newer one waits for that instead of running beside it.
            minimapLock.withLock {
                if (content.images.isNotEmpty()) withContext(Dispatchers.IO) { ops.preloadOverviewImages(content) }
                val rendered = withContext(Dispatchers.Default) { ops.renderOverview(content, w, h) { isActive } }
                minimap = rendered
                contentBox = rendered?.bounds
            }
        }
    }

    /** Re-renders the overview now (e.g. after the minimap changed size). */
    fun refreshMinimap() = onContent(latestContent, 0)

    /** The minimap is rendered only while it is shown; showing it again renders it at once. */
    fun setMinimapShown(shown: Boolean) {
        if (shown == minimapShown) return
        minimapShown = shown
        if (shown) refreshMinimap()
    }

    /** Applies a changed page (e.g. new background) to the screen and the canvas. */
    fun updatePage(p: PageMeta) {
        page = p
        canvas?.updatePages(listOf(p))
    }

    // ------------------------------------------------------------------ Tools

    fun selectTool(tool: ToolType) = updateTools(tools.copy(tool = tool))

    fun updateTools(state: ToolState) {
        if (state == tools) return
        tools = state
        // The canvas ends the selection for writing and creating tools; lasso and hand keep it
        // (pasted or inserted items stay selected so they can be moved right away).
        canvas?.setToolState(state)
        toolSaveJob?.cancel()
        toolSaveJob = scope.launch { delay(1_000); persistToolsNow() }
    }

    private fun persistToolsNow() {
        toolSaveJob?.cancel()
        val t = tools
        graph.appScope.launch {
            runCatching {
                graph.settings.update { s ->
                    // Sticky/connector are whiteboard-only; notebooks keep their last real tool.
                    val tool = if (t.tool in BOARD_ONLY_TOOLS) s.lastTools.tool else t.tool
                    if (s.lastTools == t.copy(tool = tool)) s else s.copy(lastTools = t.copy(tool = tool))
                }
            }.onFailure { Log.w(TAG, "Saving tool settings failed", it) }
        }
    }

    // ------------------------------------------------------------------ Viewport

    fun zoomBy(factor: Float) { canvas?.zoomBy(factor) }
    fun setZoom(zoom: Float) { canvas?.setZoom(zoom) }

    fun centerOn(x: Float, y: Float) { canvas?.centerOn(0, x, y) }

    fun fitContent() {
        val c = canvas ?: return
        val content = c.contentBounds(0)?.toBox() ?: contentBox
        val vis = c.visibleRect(0)?.toBox() ?: visible
        if (content == null) {
            messages.tryEmit(UiMessage(R.string.wb_board_empty))
            return
        }
        if (vis != null) c.setZoom(MinimapMath.fitZoom(c.viewState().zoom, vis, content))
        c.centerOn(0, content.centerX, content.centerY)
    }

    private fun scheduleViewSave() {
        viewSaveJob?.cancel()
        viewSaveJob = scope.launch { delay(800); saveViewStateNow() }
    }

    private fun saveViewStateNow() {
        viewSaveJob?.cancel()
        val s = runCatching { canvas?.viewState() }.getOrNull() ?: viewState
        if (s == lastSavedView || page == null) return
        lastSavedView = s
        graph.appScope.launch {
            runCatching { graph.library.updateViewState(docId, 0, s.zoom, s.scrollX, s.scrollY) }
                .onFailure { Log.w(TAG, "Saving the view position failed", it) }
        }
    }

    // ------------------------------------------------------------------ History

    fun undo() { canvas?.undo() }
    fun redo() { canvas?.redo() }

    // ------------------------------------------------------------------ Text & stickies

    fun onTextDraft(req: TextEditRequest, text: String, config: TextConfig) {
        textDraft = Triple(req, text, config)
    }

    fun onStickyDraft(req: StickyEditRequest, text: String, color: Int) {
        stickyDraft = Triple(req, text, color)
    }

    /** Commits a text box or sticky still open in its editor (screen stopped or closed). */
    fun commitPendingEdits() {
        val text = textEdit
        textDraft?.let { (req, t, cfg) ->
            if (req === text && (t != req.initialText || cfg != req.config)) runCatching { commitText(req, t, cfg) }
        }
        val sticky = stickyEdit
        stickyDraft?.let { (req, t, color) ->
            if (req === sticky && (t != req.initialText || color != req.color)) runCatching { commitSticky(req, t, color) }
        }
    }

    fun commitText(req: TextEditRequest, text: String, config: TextConfig) {
        textEdit = null
        textDraft = null
        val c = canvas ?: return
        if (req.itemId == null) {
            if (text.isNotBlank()) c.insertText(0, text, PointF(req.pageX, req.pageY), config)
        } else {
            c.updateText(ItemRef(req.pageId, req.itemId, ItemType.TEXT), text, config)
        }
        // Adopt the format only when the user changed it here (not an old box's own style).
        if (config != req.config && config != tools.text) updateTools(tools.copy(text = config))
    }

    fun commitSticky(req: StickyEditRequest, text: String, color: Int) {
        stickyEdit = null
        stickyDraft = null
        canvas?.updateSticky(ItemRef(req.pageId, req.itemId, ItemType.STICKY), text, color)
        if (color != tools.stickyColor) updateTools(tools.copy(stickyColor = color))
    }

    fun addStickyAt(x: Float?, y: Float?) {
        val c = canvas ?: return
        val ref = c.addSticky(0, if (x != null && y != null) PointF(x, y) else null, tools.stickyColor) ?: return
        val sticky = c.pageContent(0)?.stickies?.firstOrNull { it.id == ref.itemId } ?: return
        val vis = c.visibleRect(0)?.toBox() ?: return
        val k = c.width / vis.width
        val rect = RectF((sticky.x - vis.left) * k, (sticky.y - vis.top) * k, (sticky.x + sticky.width - vis.left) * k, (sticky.y + sticky.height - vis.top) * k)
        stickyEdit = StickyEditRequest(ref.pageId, ref.itemId, rect, sticky.text, sticky.color)
    }

    fun startTextAt(x: Float, y: Float) {
        val p = page ?: return
        val vis = canvas?.visibleRect(0)?.toBox() ?: return
        val k = (canvas?.width ?: 0) / vis.width
        val vx = (x - vis.left) * k
        val vy = (y - vis.top) * k
        textEdit = TextEditRequest(p.id, null, x, y, RectF(vx, vy, vx, vy), "", tools.text, k)
    }

    fun requestImage(at: PointF?) {
        imageTarget = at
        imageRequests.tryEmit(Unit)
    }

    // ------------------------------------------------------------------ InkCanvasListener

    override fun onSelectionChanged(selection: InkSelection?) {
        this.selection = selection
    }

    override fun onUndoRedoChanged(canUndo: Boolean, canRedo: Boolean) {
        this.canUndo = canUndo
        this.canRedo = canRedo
    }

    override fun onViewportChanged(state: CanvasViewState) {
        viewState = state
        visible = canvas?.visibleRect(0)?.toBox()
        scheduleViewSave()
    }

    override fun onTemporaryToolChanged(tool: ToolType?) {
        temporaryTool = tool
    }

    override fun onToolChangeRequested(tool: ToolType) = selectTool(tool)

    override fun onLongPress(pageIndex: Int, pageX: Float, pageY: Float, viewX: Float, viewY: Float) {
        longPress = LongPressRequest(pageX, pageY, viewX, viewY)
    }

    override fun onTextEditRequested(pageId: String, itemId: String?, pageX: Float, pageY: Float, viewRect: RectF) {
        val existing = itemId?.let { id -> (canvas?.pageContent(0) ?: latestContent).texts.firstOrNull { it.id == id } }
        val config = existing?.let { TextConfig(it.fontSizePt, it.color, it.bold, it.italic) } ?: tools.text
        val vis = canvas?.visibleRect(0)?.toBox()
        val k = if (vis != null && vis.width > 0f) (canvas?.width ?: 0) / vis.width else viewState.zoom
        val rect = if (viewRect.width() > 1f) RectF(viewRect) else {
            val vx = vis?.let { (pageX - it.left) * k } ?: viewRect.left
            val vy = vis?.let { (pageY - it.top) * k } ?: viewRect.top
            RectF(vx, vy, vx, vy)
        }
        textEdit = TextEditRequest(pageId, itemId, existing?.x ?: pageX, existing?.y ?: pageY, rect, existing?.text.orEmpty(), config, k)
    }

    override fun onStickyEditRequested(pageId: String, itemId: String, viewRect: RectF) {
        val sticky = (canvas?.pageContent(0) ?: latestContent).stickies.firstOrNull { it.id == itemId } ?: return
        stickyEdit = StickyEditRequest(pageId, itemId, RectF(viewRect), sticky.text, sticky.color)
    }

    override fun onImagePlacementRequested(pageIndex: Int, pageX: Float, pageY: Float) {
        requestImage(PointF(pageX, pageY))
    }

    override fun onItemTapped(ref: ItemRef) = audio.onItemTapped(ref)

    companion object {
        private const val TAG = "Whiteboard"
        const val MINIMAP_DELAY_MS = 500L
        val BOARD_ONLY_TOOLS = setOf(ToolType.STICKY, ToolType.CONNECTOR)
        val DEFAULT_PAPER = PaperSpec(style = PaperStyle.DOT_GRID, color = 0xFFFFFFFF.toInt(), lineColor = 0xFFD9D5CC.toInt())
    }
}

fun RectF.toBox(): Box = Box(left, top, right, bottom)
