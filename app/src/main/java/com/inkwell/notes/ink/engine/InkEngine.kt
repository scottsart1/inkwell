package com.inkwell.notes.ink.engine

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.inkwell.notes.R
import com.inkwell.notes.audio.AudioClock
import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.InputPrefs
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.ToolState
import com.inkwell.notes.ink.CanvasViewState
import com.inkwell.notes.ink.InkCanvasListener
import com.inkwell.notes.ink.InkCanvasView
import com.inkwell.notes.ink.InkDocumentSource
import com.inkwell.notes.ink.PageLayoutMode
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.input.InputRouter
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.model.IndexedItem
import com.inkwell.notes.ink.model.Items
import com.inkwell.notes.ink.model.LoadState
import com.inkwell.notes.ink.model.PageDiff
import com.inkwell.notes.ink.model.PageHolder
import com.inkwell.notes.ink.render.CanvasTheme
import com.inkwell.notes.ink.render.FrontBufferLayer
import com.inkwell.notes.ink.render.OverlayView
import com.inkwell.notes.ink.render.PageLayerView
import com.inkwell.notes.ink.render.RenderHost
import com.inkwell.notes.ink.render.RenderManager
import com.inkwell.notes.ink.render.WetInkHost
import com.inkwell.notes.ink.render.WetInkPresenter
import com.inkwell.notes.ink.input.TapTracker
import com.inkwell.notes.ink.select.Handle
import com.inkwell.notes.ink.select.SelectionController
import com.inkwell.notes.ink.select.SelectionTool
import com.inkwell.notes.ink.tools.EditSession
import com.inkwell.notes.ink.tools.InkTool
import com.inkwell.notes.ink.tools.OverlayDrawer
import com.inkwell.notes.ink.tools.StickyHost
import com.inkwell.notes.ink.tools.ToolHost
import com.inkwell.notes.ink.tools.ToolRegistry
import com.inkwell.notes.ink.viewport.PageLayout
import com.inkwell.notes.ink.viewport.Viewport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The ink engine behind [InkCanvasView]: page model and loading, edits and history, tools,
 * rendering and the viewport. Everything runs on the main thread except tile rendering, the
 * front buffer and I/O.
 */
internal class InkEngine(val view: InkCanvasView) : ToolHost, CommandTarget, RenderHost, WetInkHost, StickyHost {
    override val context: Context = view.context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val density: Float = context.resources.displayMetrics.density

    override val listener: InkCanvasListener? get() = view.listener

    var source: InkDocumentSource? = null
        private set
    override val docId: String get() = source?.docId ?: ""

    val pages = ArrayList<PageHolder>()
    private val byId = HashMap<String, PageHolder>()
    override val layout = PageLayout()
    override val viewport = Viewport().also { it.density = density }
    var mode: PageLayoutMode = PageLayoutMode.VERTICAL
        private set

    override var toolState: ToolState = ToolState()
        private set
    override var inputPrefs: InputPrefs = InputPrefs()
        private set
    val history = UndoStack<InkCommand>(HISTORY_SIZE)
    override var theme: CanvasTheme = CanvasTheme(dark = false)
        private set

    val render = RenderManager(this, scope)
    val pageView = PageLayerView(context, this)
    val overlayView = OverlayView(context, this)
    var front: FrontBufferLayer? = null
        private set
    override val wetInk = WetInkPresenter(this)
    val tools = ToolRegistry(this)
    val viewportCtl = ViewportController(this)
    val router = InputRouter(this)
    val editor = CanvasEditor(this)
    val painter = EnginePainter(this)
    val selection = SelectionController(this)
    val selectionTool = SelectionTool(selection)
    val keys = KeyboardController(this)
    override val taps = TapTracker(slopPx = 32f * density)

    var audioClock: AudioClock? = null
    var playback: PlaybackHighlight? = null
        private set
    val overlays = ArrayList<OverlayDrawer>()

    private var selPageId: String? = null
    private var selIds: Set<String> = emptySet()
    private var lastUndoState = false to false
    private var lastToastAt = 0L
    private var historyBusy = false
    private var released = false

    // ---------------------------------------------------------------- Binding

    fun bind(src: InkDocumentSource, metas: List<PageMeta>, layoutMode: PageLayoutMode, initial: CanvasViewState?) {
        router.cancelAll()
        cancelActiveTools()
        for (h in pages) h.loadJob?.cancel()
        pages.clear(); byId.clear()
        render.clear()
        history.clear()
        selPageId = null; selIds = emptySet()
        playback = null
        source = src
        render.images.source = src
        render.backgrounds.source = src
        for (m in metas) addHolder(PageHolder(m))
        mode = layoutMode
        viewportCtl.onBound(initial)
        notifyUndoRedo(force = true)
        listener?.onSelectionChanged(null)
        invalidateAll()
    }

    private fun addHolder(h: PageHolder) {
        pages += h
        byId[h.id] = h
    }

    /** Lays pages out for the current mode and view size (gap depends on the base scale). */
    fun relayout() {
        val metas = pages.map { it.meta }
        layout.compute(metas, mode, 0f)
        val gap = viewport.configure(mode, layout)
        layout.compute(metas, mode, gap)
    }

    fun updatePages(metas: List<PageMeta>) {
        if (PageChanges.isBookkeepingOnly(pages.map { it.meta }, metas)) {
            // Autosave bumps modifiedAt (and thumbnails arrive) a few hundred milliseconds after
            // every stroke, i.e. usually while the next one is being written. Nothing to lay out
            // again, and committing and ending the gesture in progress would truncate that stroke.
            for ((i, m) in metas.withIndex()) pages[i].meta = m
            return
        }
        flushTools()
        val anchor = viewportCtl.captureAnchor()
        val old = HashMap(byId)
        pages.clear(); byId.clear()
        for (m in metas) {
            val h = old.remove(m.id)
            if (h == null) { addHolder(PageHolder(m)); continue }
            val renderChanged = PageChanges.affectsRendering(h.meta, m)
            h.meta = m
            addHolder(h)
            if (renderChanged) {
                render.dropPage(h.id)
                h.bumpVisual()
            }
        }
        for (gone in old.values) {
            gone.loadJob?.cancel()
            render.dropPage(gone.id)
        }
        if (old.isNotEmpty()) history.removeIf { cmd -> cmd.pageIds.none { byId.containsKey(it) } }
        if (selPageId != null && !byId.containsKey(selPageId!!)) clearSelection()
        viewportCtl.onPagesChanged(anchor)
        notifyUndoRedo()
        invalidateAll()
    }

    fun setLayoutMode(newMode: PageLayoutMode) {
        if (newMode == mode) return
        flushTools()
        val anchor = viewportCtl.captureAnchor()
        mode = newMode
        render.clear()
        viewportCtl.onPagesChanged(anchor)
        invalidateAll()
    }

    fun setToolState(state: ToolState) {
        val toolChanged = state.tool != toolState.tool
        if (toolChanged && selection.isManipulating) selection.commit()
        toolState = state
        router.onToolStateChanged(state)
        if (toolChanged) {
            // Picking a writing or creating tool ends the selection (lasso and hand keep it).
            if (state.tool != com.inkwell.notes.core.model.ToolType.LASSO && state.tool != com.inkwell.notes.core.model.ToolType.HAND) clearSelection()
            taps.reset()
            invalidateOverlay()
        }
    }

    fun setInputPrefs(prefs: InputPrefs) {
        val lowLatencyChanged = prefs.lowLatencyInk != inputPrefs.lowLatencyInk
        inputPrefs = prefs
        router.onPrefsChanged(prefs)
        if (lowLatencyChanged) updateFrontBuffer()
        invalidateOverlay()
    }

    fun setDarkTheme(dark: Boolean) {
        if (theme.dark == dark) return
        theme = CanvasTheme(dark)
        com.inkwell.notes.ink.render.PageRenderer.emphasisColor = theme.withAlpha(theme.accent, 0x66)
        invalidateAll()
    }

    // ---------------------------------------------------------------- Attach / detach

    fun onAttached() {
        released = false
        updateFrontBuffer()
    }

    fun onDetached() {
        flushTools()
        // Keep the SurfaceView child; only its renderer goes (re-created on the next attach).
        front?.detach(view, removeView = false)
        render.pause()
    }

    /** Creates or removes the low-latency layer to match the preference. */
    fun updateFrontBuffer() {
        val want = inputPrefs.lowLatencyInk && view.isAttachedToWindow && !frontFailed
        val f = front
        if (want) {
            val layer = f ?: FrontBufferLayer(context) { onFrontBufferFailed() }.also { front = it }
            layer.attach(view)
        } else if (f != null) {
            f.detach(view, removeView = true)
            front = null
        }
    }

    private var frontFailed = false

    private fun onFrontBufferFailed() {
        frontFailed = true
        view.post {
            front?.detach(view, removeView = true)
            front = null
            invalidateOverlay()
        }
    }

    fun release() {
        if (released) return
        released = true
        router.cancelAll()
        cancelActiveTools()
        viewportCtl.stop()
        for (h in pages) h.loadJob?.cancel()
        front?.detach(view, removeView = false)
        front = null
        render.release()
        scope.cancel()
    }

    // ---------------------------------------------------------------- Page access

    override val pageCount: Int get() = pages.size
    override fun page(index: Int): PageHolder? = pages.getOrNull(index)
    override fun holder(index: Int): PageHolder? = pages.getOrNull(index)
    override fun holderById(pageId: String): PageHolder? = byId[pageId]
    override fun indexOf(pageId: String): Int {
        val h = byId[pageId] ?: return -1
        return pages.indexOf(h)
    }
    override fun pageIndexOf(pageId: String): Int = indexOf(pageId)

    /** Page index for document operations: infinite layouts only use page 0. */
    fun layoutIndexValid(i: Int): Boolean = i in 0 until layout.count

    override fun renderMeta(index: Int): PageMeta {
        val m = pages[index].meta
        if (!m.isInfinite || layout.isInfinite) return m
        return m.copy(widthPt = layout.pageWidth(index), heightPt = layout.pageHeight(index))
    }

    override fun visiblePages(out: IntArray): Int {
        if (layout.count == 0 || !viewport.hasSize) return 0
        val l = viewport.viewToDocX(0f); val t = viewport.viewToDocY(0f)
        val r = viewport.viewToDocX(viewport.viewWidth); val b = viewport.viewToDocY(viewport.viewHeight)
        return layout.pagesIn(l, t, r, b, out)
    }

    override val isViewportMoving: Boolean get() = viewportCtl.isMoving

    // ---------------------------------------------------------------- Coordinates

    override val scale: Float get() = viewport.scale

    override fun pageToView(pageIndex: Int, px: Float, py: Float, out: FloatArray) {
        if (!layoutIndexValid(pageIndex)) { out[0] = 0f; out[1] = 0f; return }
        viewport.pageToView(layout, pageIndex, px, py, out)
    }

    override fun viewToPage(pageIndex: Int, vx: Float, vy: Float, out: FloatArray) {
        if (!layoutIndexValid(pageIndex)) { out[0] = 0f; out[1] = 0f; return }
        viewport.viewToPage(layout, pageIndex, vx, vy, out)
    }

    private val affine = FloatArray(6)
    private val matrixValues = FloatArray(9).also { it[8] = 1f }

    override fun pageMatrix(pageIndex: Int, out: Matrix) {
        if (!layoutIndexValid(pageIndex)) { out.reset(); return }
        viewport.pageToViewAffine(layout, pageIndex, affine)
        val v = matrixValues
        v[0] = affine[0]; v[1] = affine[2]; v[2] = affine[4]
        v[3] = affine[1]; v[4] = affine[3]; v[5] = affine[5]
        v[6] = 0f; v[7] = 0f; v[8] = 1f
        out.setValues(v)
    }

    override fun pageClip(pageIndex: Int, out: RectF): Boolean {
        if (layout.isInfinite || !layoutIndexValid(pageIndex)) return false
        val ox = viewport.pageOriginX(layout, pageIndex); val oy = viewport.pageOriginY(layout, pageIndex)
        out.set(ox, oy, ox + layout.displayWidth(pageIndex) * scale, oy + layout.displayHeight(pageIndex) * scale)
        return true
    }

    private val corner = FloatArray(2)

    override fun pageBoundsToView(pageIndex: Int, b: Bounds, out: RectF) {
        if (b.isEmpty || !layoutIndexValid(pageIndex)) { out.setEmpty(); return }
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY; var bt = Float.NEGATIVE_INFINITY
        for (k in 0 until 4) {
            val x = if (k % 2 == 0) b.left else b.right
            val y = if (k < 2) b.top else b.bottom
            viewport.pageToView(layout, pageIndex, x, y, corner)
            l = minOf(l, corner[0]); r = maxOf(r, corner[0]); t = minOf(t, corner[1]); bt = maxOf(bt, corner[1])
        }
        out.set(l, t, r, bt)
    }

    /** Page under a view point (nearest page when between pages). */
    fun pageIndexAt(vx: Float, vy: Float): Int {
        if (layout.count == 0) return -1
        return layout.nearestPage(viewport.viewToDocX(vx), viewport.viewToDocY(vy))
    }

    // ---------------------------------------------------------------- Invalidation

    override fun invalidateOverlay() { overlayView.postInvalidateOnAnimation() }
    override fun invalidateOverlay(rect: RectF) {
        overlayView.postInvalidateOnAnimation(rect.left.toInt() - 1, rect.top.toInt() - 1, rect.right.toInt() + 2, rect.bottom.toInt() + 2)
    }
    override fun invalidatePages() { pageView.postInvalidateOnAnimation() }

    fun invalidateAll() {
        pageView.postInvalidateOnAnimation()
        overlayView.postInvalidateOnAnimation()
    }

    override fun afterNextFrameCommitted(action: () -> Unit) {
        val vto = overlayView.viewTreeObserver
        if (!vto.isAlive) { action(); return }
        vto.registerFrameCommitCallback {
            // One more frame of margin so the composited window shows the stroke first.
            overlayView.postOnAnimation(action)
        }
    }

    // ---------------------------------------------------------------- Loading

    private var lastEnsureAt = 0L
    private var ensureScheduled = false
    private val ensureRunnable = Runnable { ensureScheduled = false; ensureLoadedNow() }

    /** Loads pages near the viewport and releases far ones (throttled while scrolling). */
    fun ensureLoaded() {
        val now = SystemClock.uptimeMillis()
        if (now - lastEnsureAt >= ENSURE_INTERVAL_MS) { ensureLoadedNow(); return }
        if (!ensureScheduled) {
            ensureScheduled = true
            view.postDelayed(ensureRunnable, ENSURE_INTERVAL_MS - (now - lastEnsureAt))
        }
    }

    private fun ensureLoadedNow() {
        lastEnsureAt = SystemClock.uptimeMillis()
        val src = source ?: return
        if (pages.isEmpty()) return
        val vis = IntArray(64)
        val n = visiblePages(vis)
        val first: Int; val last: Int
        if (n == 0) { val c = viewport.currentPage(layout); first = c; last = c } else { first = vis[0]; last = vis[n - 1] }
        val now = SystemClock.uptimeMillis()
        val lo = (first - PRELOAD).coerceAtLeast(0); val hi = (last + PRELOAD).coerceAtMost(pages.size - 1)
        for (i in lo..hi) {
            val h = pages[i]
            when (h.state) {
                LoadState.NOT_LOADED -> load(src, h)
                LoadState.FAILED -> if (now - h.lastFailureAt > RETRY_BASE_MS * h.failures.coerceAtMost(6) && i in first..last) load(src, h)
                else -> Unit
            }
        }
        if (layout.isInfinite) return
        val keepLo = first - KEEP_CONTENT; val keepHi = last + KEEP_CONTENT
        val keepIds = HashSet<String>()
        for ((i, h) in pages.withIndex()) {
            val near = i in keepLo..keepHi
            if (i in (first - KEEP_RENDER)..(last + KEEP_RENDER)) keepIds += h.id
            if (!near && h.state == LoadState.LOADED && !h.editedThisSession && h.id != selPageId && h.pendingAdds.isEmpty() &&
                !(wetInk.isActive && wetInk.stroke.pageIndex == i)
            ) {
                h.unload()
            }
        }
        render.retainPages(keepIds)
    }

    private fun load(src: InkDocumentSource, h: PageHolder) {
        if (h.state == LoadState.LOADING) return
        h.state = LoadState.LOADING
        val meta = h.meta
        h.loadJob = scope.launch {
            try {
                val c = withContext(Dispatchers.IO) { src.loadContent(meta) }
                if (source !== src || byId[h.id] !== h) return@launch
                onLoaded(h, c)
            } catch (e: CancellationException) {
                if (h.state == LoadState.LOADING) h.state = LoadState.NOT_LOADED
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Page failed to load (${t.javaClass.simpleName})")
                h.state = LoadState.FAILED
                h.failures++
                h.lastFailureAt = SystemClock.uptimeMillis()
                invalidatePages()
            }
        }
    }

    private fun onLoaded(h: PageHolder, loaded: PageContent) {
        val prev = h.visualVersion
        h.state = LoadState.LOADED
        h.failures = 0
        h.setContent(loaded)
        render.onPageEdited(h, prev, null, null)
        if (h.pendingAdds.isNotEmpty()) {
            // Ink written before the page finished loading goes on top of it, in its own order.
            val adds = ContentOps.stackFrom(h.pendingAdds, loaded.maxZ() + 1)
            h.pendingAdds.clear()
            val before = h.content
            val after = ContentOps.add(before, adds)
            setPageContent(h, after)
            render.onPageEdited(h, h.visualVersion - 1, null, null)
            ContentDiffCommand.of("Ink", mapOf(h.id to (before to after)))?.let { history.push(it) }
            notifyContentChanged(h)
            notifyUndoRedo()
        }
        if (selPageId == h.id) pruneSelection()
        if (layout.isInfinite && pages.indexOf(h) == 0) viewportCtl.onInfiniteContentLoaded()
        invalidateAll()
        render.schedule()
    }

    fun reloadPage(pageId: String) {
        val h = byId[pageId] ?: return
        val src = source ?: return
        h.loadJob?.cancel()
        if (wetInk.isActive && wetInk.stroke.pageIndex == pages.indexOf(h)) router.cancelAll()
        h.state = LoadState.NOT_LOADED
        h.editedThisSession = false
        load(src, h)
    }

    override fun isEditable(pageIndex: Int, notify: Boolean): Boolean {
        val h = page(pageIndex) ?: return false
        if (!layoutIndexValid(pageIndex)) return false
        if (h.state == LoadState.FAILED) {
            if (notify) showMessage(R.string.ink_page_not_editable)
            return false
        }
        if (h.state == LoadState.NOT_LOADED) source?.let { load(it, h) }
        return true
    }

    fun showMessage(res: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastToastAt < 3500) return
        lastToastAt = now
        Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- Edits

    override fun newId(): String = UUID.randomUUID().toString()

    override fun nextZ(pageIndex: Int): Int {
        val h = page(pageIndex) ?: return 0
        return h.content.maxZ() + 1 + h.pendingAdds.size
    }

    override fun audioAnchor(): AudioAnchor? = try {
        val id = docId
        if (id.isEmpty()) null else audioClock?.anchorFor(id)
    } catch (t: Throwable) {
        null
    }

    /** Installs new content for a page (no history, no notification). */
    fun setPageContent(h: PageHolder, content: PageContent) {
        h.setContent(content)
        h.editedThisSession = true
        if (h.id == selPageId) pruneSelection()
        invalidateOverlay()
    }

    fun notifyContentChanged(h: PageHolder) {
        if (!h.isLoaded) return
        try {
            source?.onContentChanged(h.meta, h.content)
        } catch (t: Throwable) {
            Log.e(TAG, "Saving page content failed", t)
            showMessage(R.string.ink_save_failed)
        }
    }

    override fun commitNewItems(pageIndex: Int, items: List<Any>, label: String, select: Boolean) {
        if (items.isEmpty()) return
        // Pending keyboard nudges become their own undo step first.
        keys.flush()
        val h = page(pageIndex) ?: return
        when (h.state) {
            LoadState.FAILED -> { showMessage(R.string.ink_page_not_editable); return }
            LoadState.NOT_LOADED, LoadState.LOADING -> {
                h.pendingAdds.addAll(items)
                source?.let { load(it, h) }
                render.addPending(h, items)
                return
            }
            LoadState.LOADED -> Unit
        }
        val before = h.content
        val after = ContentOps.add(before, items)
        val prevVisual = h.visualVersion
        setPageContent(h, after)
        render.addPending(h, items)
        val changed = Bounds()
        val tmp = Bounds()
        for (it in items) changed.union(ItemGeometry.bounds(it, tmp, after))
        // New items sit above everything, so tiles can draw them incrementally (in paint order).
        val onTop = if (items.any { it is Stroke && it.pen == PenType.HIGHLIGHTER }) null
        else com.inkwell.notes.ink.render.RenderOrder.order(ContentOps.add(PageContent.EMPTY, items))
        render.onPageEdited(h, prevVisual, changed, onTop)
        history.push(ContentDiffCommand(label, listOf(appendDiff(h.id, before, items))))
        notifyContentChanged(h)
        notifyUndoRedo()
        if (select) setSelection(pageIndex, items.mapTo(LinkedHashSet()) { Items.id(it) })
    }

    /** Diff for items appended to the end of their type lists. */
    private fun appendDiff(pageId: String, before: PageContent, items: List<Any>): PageDiff {
        val counts = HashMap<com.inkwell.notes.ink.ItemType, Int>()
        val added = items.map { item ->
            val type = Items.type(item)
            val base = ContentOps.listOf(before, type).size
            val k = counts.getOrDefault(type, 0)
            counts[type] = k + 1
            IndexedItem(base + k, item)
        }
        return PageDiff(pageId, emptyList(), added)
    }

    override fun applyEdit(pageIndex: Int, label: String, transform: (PageContent) -> PageContent): Boolean {
        keys.flush()
        val h = page(pageIndex) ?: return false
        if (!h.isLoaded) return false
        val before = h.content
        val after = transform(before)
        if (after === before) return false
        val diff = ContentOps.diff(h.id, before, after)
        if (diff.isEmpty) return false
        val prev = h.visualVersion
        setPageContent(h, after)
        render.onPageEdited(h, prev, diff.changedBounds(before, after), null)
        history.push(ContentDiffCommand(label, listOf(diff)))
        notifyContentChanged(h)
        notifyUndoRedo()
        return true
    }

    override fun beginEdit(label: String): EditSession {
        keys.flush()
        return Session(label)
    }

    private inner class Session(private val label: String) : EditSession {
        private val before = LinkedHashMap<String, PageContent>()
        private val area = HashMap<String, Bounds>()
        private var done = false

        override fun content(pageIndex: Int): PageContent? = page(pageIndex)?.takeIf { it.isLoaded && !done }?.content

        override fun update(pageIndex: Int, content: PageContent, changed: Bounds?) {
            if (done) return
            val h = page(pageIndex) ?: return
            if (!h.isLoaded) return
            before.getOrPut(h.id) { h.content }
            val prev = h.visualVersion
            setPageContent(h, content)
            render.onPageEdited(h, prev, changed, null)
            val a = area.getOrPut(h.id) { Bounds() }
            if (changed == null) a.set(-1e9f, -1e9f, 1e9f, 1e9f) else a.union(changed)
        }

        override fun commit(): Boolean {
            if (done) return false
            done = true
            val changes = LinkedHashMap<String, Pair<PageContent, PageContent>>()
            for ((id, b) in before) { val h = byId[id] ?: continue; changes[id] = b to h.content }
            val cmd = ContentDiffCommand.of(label, changes) ?: return false
            history.push(cmd)
            for (id in changes.keys) byId[id]?.let { notifyContentChanged(it) }
            notifyUndoRedo()
            return true
        }

        override fun cancel() {
            if (done) return
            done = true
            for ((id, b) in before) {
                val h = byId[id] ?: continue
                val prev = h.visualVersion
                setPageContent(h, b)
                render.onPageEdited(h, prev, area[id], null)
            }
        }
    }

    override fun pushCommand(command: InkCommand) {
        keys.flush()
        history.push(command)
        for (id in command.pageIds) byId[id]?.let { notifyContentChanged(it) }
        notifyUndoRedo()
    }

    // ---------------------------------------------------------------- CommandTarget

    override fun contentOf(pageId: String): PageContent? = byId[pageId]?.takeIf { it.isLoaded }?.content

    override fun setContent(pageId: String, content: PageContent, changed: Bounds?) {
        val h = byId[pageId] ?: return
        val prev = h.visualVersion
        setPageContent(h, content)
        render.onPageEdited(h, prev, changed, null)
        notifyContentChanged(h)
    }

    // ---------------------------------------------------------------- History

    fun undo() = runHistory(undo = true)
    fun redo() = runHistory(undo = false)

    private fun runHistory(undo: Boolean) {
        if (historyBusy) return
        flushTools()
        val cmd = (if (undo) history.peekUndo() else history.peekRedo()) ?: return
        val unloaded = cmd.pageIds.mapNotNull { byId[it] }.filter { !it.isLoaded }
        if (unloaded.any { it.state == LoadState.FAILED }) {
            showMessage(R.string.ink_page_not_editable)
            return
        }
        if (unloaded.isNotEmpty()) {
            // Load the affected pages first, then apply.
            historyBusy = true
            val src = source ?: run { historyBusy = false; return }
            scope.launch {
                try {
                    for (h in unloaded) {
                        if (h.state != LoadState.LOADING) load(src, h)
                        h.loadJob?.join()
                    }
                } finally {
                    historyBusy = false
                }
                if (unloaded.all { it.isLoaded }) runHistory(undo)
            }
            return
        }
        if (undo) history.popUndo() else history.popRedo()
        val ok = try {
            if (undo) cmd.undo(this) else cmd.redo(this)
        } catch (t: Throwable) {
            Log.e(TAG, "History step failed", t)
            false
        }
        if (!ok) history.discard(cmd)
        notifyUndoRedo()
        // Undo/redo may have moved or resized selected items: hosts re-anchor their menu.
        if (selPageId != null) reportSelection()
        // Bring the change into view.
        val target = cmd.pageIds.firstNotNullOfOrNull { id -> indexOf(id).takeIf { it >= 0 } }
        if (target != null && !viewportCtl.isPageVisible(target)) viewportCtl.scrollToPage(target, smooth = true)
        invalidateAll()
    }

    fun canUndo(): Boolean = history.canUndo
    fun canRedo(): Boolean = history.canRedo

    fun notifyUndoRedo(force: Boolean = false) {
        val state = history.canUndo to history.canRedo
        if (!force && state == lastUndoState) return
        lastUndoState = state
        listener?.onUndoRedoChanged(state.first, state.second)
    }

    // ---------------------------------------------------------------- Selection

    override val selectionPageIndex: Int get() = selPageId?.let { indexOf(it) } ?: -1
    override val selectedIds: Set<String> get() = selIds

    override fun setSelection(pageIndex: Int, ids: Set<String>) {
        val h = page(pageIndex) ?: return
        if (ids.isEmpty()) { clearSelection(); return }
        selPageId = h.id
        selIds = LinkedHashSet(ids)
        invalidateOverlay()
        listener?.onSelectionChanged(editor.buildSelection())
    }

    override fun clearSelection() {
        if (selPageId == null && selIds.isEmpty()) return
        selPageId = null
        selIds = emptySet()
        invalidateOverlay()
        listener?.onSelectionChanged(null)
    }

    /** Drops selected ids that no longer exist. */
    private fun pruneSelection() {
        // Items lifted for a drag are temporarily off the page; they come back on release.
        if (selection.isLifted) return
        val h = selPageId?.let { byId[it] } ?: return
        if (!h.isLoaded) return
        val existing = selIds.filterTo(LinkedHashSet()) { ContentOps.find(h.content, it) != null }
        if (existing.size == selIds.size) return
        if (existing.isEmpty()) clearSelection() else {
            selIds = existing
            listener?.onSelectionChanged(editor.buildSelection())
        }
    }

    /** Re-reports the selection after the viewport moved (its view bounds changed). */
    fun reportSelectionMoved() {
        if (selection.isManipulating) return
        if (selPageId != null) listener?.onSelectionChanged(editor.buildSelection())
    }

    /** Reports the current selection with fresh bounds (cleared when its items are gone). */
    fun reportSelection() {
        if (selPageId == null) return
        val sel = editor.buildSelection()
        if (sel == null) clearSelection() else listener?.onSelectionChanged(sel)
        invalidateOverlay()
    }

    /** Asks the host to edit a text box or sticky note. */
    override fun requestEdit(pageIndex: Int, item: Any, pageX: Float, pageY: Float) {
        val h = page(pageIndex) ?: return
        if (!isEditable(pageIndex)) return
        val r = RectF()
        pageBoundsToView(pageIndex, ItemGeometry.bounds(item, Bounds(), h.content), r)
        when (item) {
            is com.inkwell.notes.core.model.TextItem -> listener?.onTextEditRequested(h.id, item.id, pageX, pageY, r)
            is com.inkwell.notes.core.model.StickyItem -> listener?.onStickyEditRequested(h.id, item.id, r)
        }
    }

    override fun beginItemDrag(pageIndex: Int, itemId: String, viewX: Float, viewY: Float, isFinger: Boolean): InkTool? {
        val h = page(pageIndex) ?: return null
        if (!h.isLoaded || !isEditable(pageIndex)) return null
        if (ContentOps.find(h.content, itemId) == null) return null
        setSelection(pageIndex, com.inkwell.notes.ink.tools.LassoSelector.expandGroups(h.content, setOf(itemId)))
        return if (selection.begin(viewX, viewY, Handle.BODY, isFinger)) selectionTool else null
    }

    override fun schedule(delayMs: Long, action: Runnable) {
        view.removeCallbacks(action)
        view.postDelayed(action, delayMs)
    }

    override fun unschedule(action: Runnable) { view.removeCallbacks(action) }

    override fun haptic() {
        val c = if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.KEYBOARD_TAP
        try { view.performHapticFeedback(c) } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------- Playback highlight

    fun setPlayback(recordingId: String?, positionMs: Long, windowMs: Long) {
        playback = recordingId?.let { PlaybackHighlight(it, positionMs, windowMs.coerceAtLeast(0)) }
        invalidateOverlay()
    }

    // ---------------------------------------------------------------- Tools

    /** The tool for stylus input of [type]. */
    fun tool(type: com.inkwell.notes.core.model.ToolType): InkTool = tools[type]

    fun flushTools() {
        try { selection.commit() } catch (e: Throwable) { Log.e(TAG, "Selection commit failed", e) }
        for (t in tools.all) if (t.isActive) try { t.flush(this) } catch (e: Throwable) { Log.e(TAG, "Tool flush failed", e) }
        try { keys.flush() } catch (e: Throwable) { Log.e(TAG, "Nudge commit failed", e) }
        router.endGestures()
    }

    private fun cancelActiveTools() {
        selection.cancel()
        for (t in tools.all) if (t.isActive) t.onCancel(this)
        if (wetInk.isActive) wetInk.cancel()
    }

    override fun addOverlay(drawer: OverlayDrawer) { if (drawer !in overlays) overlays += drawer; invalidateOverlay() }
    override fun removeOverlay(drawer: OverlayDrawer) { overlays -= drawer; invalidateOverlay() }

    // ---------------------------------------------------------------- Viewport (ToolHost)

    override fun panBy(dxPx: Float, dyPx: Float) = viewportCtl.panBy(dxPx, dyPx)
    override fun fling(vxPx: Float, vyPx: Float) = viewportCtl.fling(vxPx, vyPx)
    override fun stopViewportMotion() = viewportCtl.stop()

    // ---------------------------------------------------------------- Live ink (WetInkHost)

    override val frontLayer: FrontBufferLayer? get() = front
    override val lowLatencyEnabled: Boolean get() = inputPrefs.lowLatencyInk && front?.isReady == true

    // ---------------------------------------------------------------- Sticky (StickyHost)

    override fun addStickyAt(pageIndex: Int, x: Float, y: Float): String? =
        editor.addSticky(pageIndex, android.graphics.PointF(x, y), null)?.itemId

    companion object {
        private const val TAG = "InkEngine"
        const val HISTORY_SIZE = 200
        private const val PRELOAD = 2
        private const val KEEP_CONTENT = 8
        private const val KEEP_RENDER = 3
        private const val RETRY_BASE_MS = 2000L
        private const val ENSURE_INTERVAL_MS = 120L
    }
}

/** Items anchored to [recordingId] within [windowMs] before [positionMs] are emphasised. */
internal class PlaybackHighlight(val recordingId: String, val positionMs: Long, val windowMs: Long) {
    fun matches(anchor: AudioAnchor?): Boolean =
        anchor != null && anchor.recordingId == recordingId && anchor.offsetMs <= positionMs && anchor.offsetMs >= positionMs - windowMs
}
