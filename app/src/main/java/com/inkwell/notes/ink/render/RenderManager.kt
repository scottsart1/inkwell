package com.inkwell.notes.ink.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.model.PageHolder
import com.inkwell.notes.ink.viewport.PageLayout
import com.inkwell.notes.ink.viewport.PageRotation
import com.inkwell.notes.ink.viewport.Viewport
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What the render manager needs from the engine. */
internal interface RenderHost {
    val layout: PageLayout
    val viewport: Viewport
    val pageCount: Int
    fun holder(index: Int): PageHolder?
    fun holderById(pageId: String): PageHolder?
    fun indexOf(pageId: String): Int
    /** Page metadata with sizes usable for rendering (unbounded pages get the layout's size). */
    fun renderMeta(index: Int): PageMeta
    /** Visible page indices (written to [out]); returns the count. */
    fun visiblePages(out: IntArray): Int
    /** True while a pinch, fling or animation is running (zoom level changes wait). */
    val isViewportMoving: Boolean
    fun invalidatePages()
    fun invalidateOverlay()
}

/**
 * Keeps committed content as cached bitmaps: per page, tiles at the settled zoom (plus the previous
 * level while a new one renders) and a low resolution snapshot used while tiles are missing.
 * Rendering happens on [TileWorker]; this class only schedules and draws. Main thread only.
 */
internal class RenderManager(private val host: RenderHost, scope: CoroutineScope) {
    val images = ImageCache(scope, maxBytes = 64 * 1024 * 1024)
    val backgrounds = BackgroundCache(scope)
    private var workerRef: TileWorker? = null
    private val worker: TileWorker
        get() = workerRef ?: TileWorker(images).also { workerRef = it }

    val tileSize = 512

    private class PageTiles {
        var level: TileBook<Bitmap>? = null
        var levelScale = 0f
        var prev: TileBook<Bitmap>? = null
        var prevScale = 0f
        var snapshot: Bitmap? = null
        var snapshotVersion = -1L
        val snapshotRegion = Bounds()
        var snapshotInFlight = false
        var lastEditAt = 0L
    }

    /** Items just committed, drawn by the overlay until tiles include them. */
    class Pending(val pageId: String, val items: List<Any>, val version: Long, val bounds: Bounds, val since: Long)

    private val pages = HashMap<String, PageTiles>()
    val pending = ArrayList<Pending>()

    /** Scale (px/pt) of the current tile level; 0 before the first layout. */
    var levelScale = 0f
        private set
    private var inFlight = 0
    private var frame = 0L
    private var released = false

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val visible = IntArray(64)
    private val range = IntArray(4)
    private val tmpBounds = Bounds()

    init {
        images.onLoaded = { assetId -> onImageLoaded(assetId) }
        backgrounds.onChanged = { pageId -> onBackgroundChanged(pageId) }
    }

    // ---------------------------------------------------------------- Levels

    /** Switches tiles to the current zoom once it settled. Returns true if a new level started. */
    fun updateLevel(force: Boolean = false): Boolean {
        val s = host.viewport.scale
        if (s <= 0f) return false
        if (!force && host.isViewportMoving) return false
        if (levelScale > 0f && abs(s - levelScale) / levelScale < 0.004f) return false
        levelScale = s
        for (pt in pages.values) startLevel(pt, s)
        host.invalidatePages()
        return true
    }

    private fun startLevel(pt: PageTiles, s: Float) {
        val cur = pt.level
        val curCount = cur?.tiles?.values?.count { it.bitmap != null } ?: 0
        val prevCount = pt.prev?.tiles?.values?.count { it.bitmap != null } ?: 0
        // Keep whichever level covers more as the fallback while the new level renders.
        if (cur != null && curCount > 0 && curCount >= prevCount / 2) {
            pt.prev = cur; pt.prevScale = pt.levelScale
        }
        pt.level = TileBook()
        pt.levelScale = s
    }

    private fun tilesFor(holder: PageHolder): PageTiles {
        return pages.getOrPut(holder.id) {
            PageTiles().also { if (levelScale > 0f) { it.level = TileBook(); it.levelScale = levelScale } }
        }
    }

    // ---------------------------------------------------------------- Edits

    /**
     * A page's content or visuals changed from [prevVisual] to the holder's current visual
     * version. [changed] (page points) limits re-rendering; null means everything.
     * [addedOnTop] are new topmost items that tiles may draw incrementally.
     */
    fun onPageEdited(holder: PageHolder, prevVisual: Long, changed: Bounds?, addedOnTop: List<Any>?) {
        val pt = pages[holder.id] ?: return
        pt.lastEditAt = SystemClock.uptimeMillis()
        val next = holder.visualVersion
        val i = host.indexOf(holder.id)
        if (i < 0) return
        val display = changed?.let { displayBounds(i, it, tmpBounds).copy() }
        pt.level?.let { book ->
            val sL = pt.levelScale
            book.onEdit(prevVisual, next, addedOnTop) { t -> display == null || tileTouches(t.ix, t.iy, sL, display) }
        }
        // Old-level tiles are only a fallback; drop the touched ones so stale ink never shows.
        pt.prev?.let { book ->
            val sP = pt.prevScale
            if (display == null) pt.prev = null
            else book.tiles.values.removeAll { tileTouches(it.ix, it.iy, sP, display) }
        }
        schedule()
    }

    private fun onBackgroundChanged(pageId: String) {
        val holder = host.holderById(pageId) ?: return
        holder.bumpVisual()
        val pt = pages[pageId] ?: run { host.invalidatePages(); return }
        pt.level?.invalidateAll()
        pt.prev = null
        host.invalidatePages()
        schedule()
    }

    private fun onImageLoaded(assetId: String) {
        for (i in 0 until host.pageCount) {
            val holder = host.holder(i) ?: continue
            if (!holder.isLoaded) continue
            val imgs = holder.content.images.filter { it.assetId == assetId }
            if (imgs.isEmpty()) continue
            val b = Bounds()
            for (img in imgs) b.union(ItemGeometry.bounds(img, tmpBounds))
            val prev = holder.visualVersion
            holder.bumpVisual()
            onPageEdited(holder, prev, b, null)
        }
        host.invalidatePages()
        host.invalidateOverlay()
    }

    // ---------------------------------------------------------------- Pending items

    fun addPending(holder: PageHolder, items: List<Any>) {
        val b = Bounds()
        for (it in items) b.union(ItemGeometry.bounds(it, tmpBounds, holder.content))
        pending += Pending(holder.id, items, holder.visualVersion, b, SystemClock.uptimeMillis())
        host.invalidateOverlay()
    }

    /** Drops pending items whose area is fully covered by up-to-date tiles. */
    fun checkPending() {
        if (pending.isEmpty()) return
        val removed = pending.removeAll { p -> isCovered(p) }
        if (removed) host.invalidateOverlay()
    }

    private fun isCovered(p: Pending): Boolean {
        val i = host.indexOf(p.pageId)
        if (i < 0) return true
        val holder = host.holder(i) ?: return true
        if (holder.visualVersion < p.version) return true // undone or reloaded meanwhile
        if (!isPageVisible(i)) return true
        val pt = pages[p.pageId] ?: return false
        val book = pt.level ?: return false
        val sL = pt.levelScale
        val d = displayBounds(i, p.bounds, tmpBounds)
        if (!visibleTileRange(i, sL, 0f, range)) return true
        val vx0 = range[0]; val vy0 = range[1]; val vx1 = range[2]; val vy1 = range[3]
        val x0 = max(vx0, TileWorker.tileIndex(d.left, tileSize, sL))
        val y0 = max(vy0, TileWorker.tileIndex(d.top, tileSize, sL))
        val x1 = min(vx1, TileWorker.tileIndex(d.right, tileSize, sL))
        val y1 = min(vy1, TileWorker.tileIndex(d.bottom, tileSize, sL))
        for (iy in y0..y1) for (ix in x0..x1) if (!book.covers(ix, iy, p.version)) return false
        return true
    }

    // ---------------------------------------------------------------- Drawing

    /** Call once at the start of every page-layer draw pass. */
    fun beginFrame() { frame++ }

    /**
     * Draws the cached content of page [i] (snapshot, previous level, current tiles). The canvas
     * is in view pixels and already clipped to the page.
     */
    fun drawPage(canvas: Canvas, i: Int, holder: PageHolder) {
        val pt = pages[holder.id] ?: tilesFor(holder)
        val vp = host.viewport
        val s = vp.scale
        val ox = vp.pageOriginX(host.layout, i)
        val oy = vp.pageOriginY(host.layout, i)
        val book = pt.level
        val covered = book != null && visibleTileRange(i, pt.levelScale, 0f, range) && coveredRange(book, holder.visualVersion, range)
        if (!covered) {
            pt.snapshot?.let { snap ->
                val r = pt.snapshotRegion
                dst.set(ox + r.left * s, oy + r.top * s, ox + r.right * s, oy + r.bottom * s)
                canvas.drawBitmap(snap, null, dst, bitmapPaint)
            }
            pt.prev?.let { drawBook(canvas, it, pt.prevScale, ox, oy, s) }
        } else if (pt.prev != null) {
            pt.prev = null
        }
        book?.let { drawBook(canvas, it, pt.levelScale, ox, oy, s) }
    }

    private fun coveredRange(book: TileBook<Bitmap>, version: Long, r: IntArray): Boolean {
        for (iy in r[1]..r[3]) for (ix in r[0]..r[2]) {
            val t = book.get(ix, iy) ?: return false
            if (t.bitmap == null || t.version != version) return false
        }
        return true
    }

    private fun drawBook(canvas: Canvas, book: TileBook<Bitmap>, sL: Float, ox: Float, oy: Float, s: Float) {
        val k = s / sL
        val step = tileSize * k
        val vw = host.viewport.viewWidth; val vh = host.viewport.viewHeight
        for (t in book.tiles.values) {
            val bmp = t.bitmap ?: continue
            val l = ox + t.ix * step; val top = oy + t.iy * step
            if (l > vw || top > vh || l + step < 0f || top + step < 0f) continue
            dst.set(l, top, l + step, top + step)
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
            t.lastUsedFrame = frame
        }
    }

    // ---------------------------------------------------------------- Scheduling

    private class Candidate(val holder: PageHolder, val index: Int, val book: TileBook<Bitmap>?, val tile: TileBook.Tile<Bitmap>?, val need: TileBook.Need, val priority: Float, val snapshot: Boolean)

    /** Starts render jobs for the most urgent missing or stale tiles. */
    fun schedule() {
        if (released || levelScale <= 0f) return
        if (inFlight >= MAX_IN_FLIGHT) return
        val vp = host.viewport
        if (!vp.hasSize) return
        val n = host.visiblePages(visible)
        val candidates = ArrayList<Candidate>()
        val cx = vp.viewWidth / 2f; val cy = vp.viewHeight / 2f
        val now = SystemClock.uptimeMillis()
        for (k in 0 until n) {
            val i = visible[k]
            val holder = host.holder(i) ?: continue
            if (!holder.isLoaded) continue
            val pt = tilesFor(holder)
            if (pt.level == null) { pt.level = TileBook(); pt.levelScale = levelScale }
            val book = pt.level!!
            val sL = pt.levelScale
            val meta = host.renderMeta(i)
            // Wait briefly for a sharper PDF background instead of rendering twice.
            val bgWait = backgrounds.isUpgrading(holder.id, (meta.widthPt * sL).toInt())
            if (!bgWait && visibleTileRange(i, sL, tileSize * 0.5f, range)) {
                val ox = vp.pageOriginX(host.layout, i); val oy = vp.pageOriginY(host.layout, i)
                val k2 = vp.scale / sL
                for (iy in range[1]..range[3]) for (ix in range[0]..range[2]) {
                    val tile = book.getOrCreate(ix, iy)
                    val need = book.need(tile, holder.visualVersion)
                    if (need == TileBook.Need.NONE) continue
                    if (tile.failures > 0 && now - tile.failedAt < FAILURE_BACKOFF_MS * tile.failures.coerceAtMost(8)) continue
                    val tx = ox + (ix + 0.5f) * tileSize * k2; val ty = oy + (iy + 0.5f) * tileSize * k2
                    val onScreen = tx > -tileSize * k2 / 2 && ty > -tileSize * k2 / 2 && tx < vp.viewWidth + tileSize * k2 / 2 && ty < vp.viewHeight + tileSize * k2 / 2
                    val dist = abs(tx - cx) + abs(ty - cy)
                    val pr = when {
                        need == TileBook.Need.INCREMENT -> dist * 0.001f
                        onScreen -> 10f + dist
                        else -> 100_000f + dist
                    }
                    candidates += Candidate(holder, i, book, tile, need, pr, false)
                }
            }
            if (needsSnapshot(holder, pt, now)) candidates += Candidate(holder, i, null, null, TileBook.Need.FULL, 1_000_000f + k, true)
        }
        if (candidates.isEmpty()) return
        candidates.sortBy { it.priority }
        for (c in candidates) {
            if (inFlight >= MAX_IN_FLIGHT) break
            if (c.snapshot) startSnapshot(c.holder, c.index) else startTile(c)
        }
    }

    private fun startTile(c: Candidate) {
        val book = c.book ?: return
        val tile = c.tile ?: return
        val holder = c.holder
        val pt = pages[holder.id] ?: return
        val ticket = book.begin(tile, c.need, holder.visualVersion)
        val meta = host.renderMeta(c.index)
        val infinite = host.layout.isInfinite
        val sL = pt.levelScale
        val job = RenderJob(
            kind = if (ticket.full) RenderJob.Kind.TILE else RenderJob.Kind.INCREMENT,
            pageId = holder.id, meta = meta, infinite = infinite, content = holder.content,
            version = ticket.version, scale = sL, ix = tile.ix, iy = tile.iy, tileSize = tileSize,
            background = backgrounds.bitmap(holder.id),
            base = if (ticket.full) null else tile.bitmap, baseVersion = ticket.baseVersion,
            addItems = if (ticket.full) null else ticket.items, levelScale = sL,
        )
        inFlight++
        worker.submit(job) { done ->
            inFlight--
            val p = pages[done.pageId]
            val h = host.holderById(done.pageId)
            if (p != null && h != null && p.level === book) {
                if (done.result == null) tile.failedAt = SystemClock.uptimeMillis()
                if (book.finish(tile, ticket, done.result, h.visualVersion)) {
                    // Counts as used now, so eviction below cannot drop it before it is drawn.
                    tile.lastUsedFrame = frame
                    host.invalidatePages()
                    checkPending()
                }
                evictIfNeeded()
            } else {
                tile.inFlight = false
            }
            schedule()
        }
    }

    private fun needsSnapshot(holder: PageHolder, pt: PageTiles, now: Long): Boolean {
        if (pt.snapshotInFlight) return false
        if (pt.snapshotVersion >= holder.visualVersion) return false
        if (pt.snapshot == null) return true
        return now - pt.lastEditAt > SNAPSHOT_IDLE_MS
    }

    private fun startSnapshot(holder: PageHolder, i: Int) {
        val pt = pages[holder.id] ?: return
        val layout = host.layout
        val region: Bounds
        val scale: Float
        if (layout.isInfinite) {
            val cb = PageRenderer.contentBoundsOf(holder.content)?.outset(64f) ?: run {
                pt.snapshot = null; pt.snapshotVersion = holder.visualVersion; return
            }
            region = cb
            scale = min(1f, sqrt(SNAPSHOT_PIXELS_INFINITE / max(1f, cb.width * cb.height)))
        } else {
            val dw = layout.displayWidth(i); val dh = layout.displayHeight(i)
            region = Bounds(0f, 0f, dw, dh)
            scale = min(2f, sqrt(SNAPSHOT_PIXELS / max(1f, dw * dh)))
        }
        pt.snapshotInFlight = true
        val job = RenderJob(
            kind = RenderJob.Kind.SNAPSHOT, pageId = holder.id, meta = host.renderMeta(i), infinite = layout.isInfinite,
            content = holder.content, version = holder.visualVersion, scale = scale, ix = 0, iy = 0, tileSize = tileSize,
            background = backgrounds.bitmap(holder.id), region = region,
        )
        inFlight++
        worker.submit(job) { done ->
            inFlight--
            val p = pages[done.pageId]
            if (p != null) {
                p.snapshotInFlight = false
                val bmp = done.result
                if (bmp != null && done.version >= p.snapshotVersion) {
                    p.snapshot = bmp
                    p.snapshotVersion = done.version
                    p.snapshotRegion.set(region)
                    host.invalidatePages()
                }
            }
            schedule()
        }
    }

    // ---------------------------------------------------------------- Geometry helpers

    private val visibleCheck = IntArray(64)

    private fun isPageVisible(i: Int): Boolean {
        val n = host.visiblePages(visibleCheck)
        for (k in 0 until n) if (visibleCheck[k] == i) return true
        return false
    }

    /** Tile index range [x0, y0, x1, y1] of page [i] visible on screen (plus [marginPx]). */
    fun visibleTileRange(i: Int, sL: Float, marginPx: Float, out: IntArray): Boolean {
        if (sL <= 0f) return false
        val vp = host.viewport
        val layout = host.layout
        val s = vp.scale
        val ox = vp.pageOriginX(layout, i); val oy = vp.pageOriginY(layout, i)
        var l = -marginPx; var t = -marginPx; var r = vp.viewWidth + marginPx; var b = vp.viewHeight + marginPx
        if (!layout.isInfinite) {
            l = max(l, ox); t = max(t, oy)
            r = min(r, ox + layout.displayWidth(i) * s); b = min(b, oy + layout.displayHeight(i) * s)
        }
        if (l >= r || t >= b) return false
        val dl = (l - ox) / s; val dt = (t - oy) / s; val dr = (r - ox) / s; val db = (b - oy) / s
        out[0] = floor(dl * sL / tileSize).toInt(); out[1] = floor(dt * sL / tileSize).toInt()
        out[2] = floor((dr * sL - 0.01f) / tileSize).toInt(); out[3] = floor((db * sL - 0.01f) / tileSize).toInt()
        if (!layout.isInfinite) {
            val maxX = ceil(layout.displayWidth(i) * sL / tileSize).toInt() - 1
            val maxY = ceil(layout.displayHeight(i) * sL / tileSize).toInt() - 1
            out[0] = out[0].coerceIn(0, maxX); out[2] = out[2].coerceIn(0, maxX)
            out[1] = out[1].coerceIn(0, maxY); out[3] = out[3].coerceIn(0, maxY)
        }
        // Guard against pathological zoom-out on the infinite canvas.
        if ((out[2] - out[0] + 1).toLong() * (out[3] - out[1] + 1) > 400) return false
        return out[0] <= out[2] && out[1] <= out[3]
    }

    private fun tileTouches(ix: Int, iy: Int, sL: Float, display: Bounds): Boolean {
        val l = ix * tileSize / sL; val t = iy * tileSize / sL
        val r = (ix + 1) * tileSize / sL; val b = (iy + 1) * tileSize / sL
        // One pixel of slack for antialiasing.
        val pad = 1.5f / sL
        return display.intersects(l - pad, t - pad, r + pad, b + pad)
    }

    /** Page-point bounds -> displayed-page bounds (rotation aware). */
    fun displayBounds(i: Int, b: Bounds, out: Bounds): Bounds {
        val layout = host.layout
        if (layout.isInfinite || layout.rotation(i) == 0) return out.set(b)
        val w = layout.pageWidth(i); val h = layout.pageHeight(i); val r = layout.rotation(i)
        val p = FloatArray(2)
        out.setEmpty()
        PageRotation.toDisplay(b.left, b.top, w, h, r, p); out.union(p[0], p[1])
        PageRotation.toDisplay(b.right, b.top, w, h, r, p); out.union(p[0], p[1])
        PageRotation.toDisplay(b.left, b.bottom, w, h, r, p); out.union(p[0], p[1])
        PageRotation.toDisplay(b.right, b.bottom, w, h, r, p); out.union(p[0], p[1])
        return out
    }

    // ---------------------------------------------------------------- Memory

    private fun evictIfNeeded() {
        var total = 0
        for (pt in pages.values) {
            total += pt.level?.tiles?.values?.count { it.bitmap != null } ?: 0
            total += pt.prev?.tiles?.values?.count { it.bitmap != null } ?: 0
        }
        val budget = tileBudget()
        if (total <= budget) return
        val all = ArrayList<Triple<TileBook<Bitmap>, Long, TileBook.Tile<Bitmap>>>()
        for (pt in pages.values) {
            pt.prev?.let { b -> b.tiles.forEach { (k, t) -> if (t.bitmap != null && !t.inFlight) all += Triple(b, k, t) } }
            pt.level?.let { b -> b.tiles.forEach { (k, t) -> if (t.bitmap != null && !t.inFlight) all += Triple(b, k, t) } }
        }
        all.sortBy { it.third.lastUsedFrame }
        var excess = total - budget
        for ((book, key, t) in all) {
            if (excess <= 0) break
            if (t.lastUsedFrame >= frame - 1) continue // drawn in the latest frame
            book.tiles.remove(key)
            excess--
        }
    }

    private fun tileBudget(): Int {
        val vp = host.viewport
        val perScreen = (ceil(vp.viewWidth / tileSize) + 1) * (ceil(vp.viewHeight / tileSize) + 1)
        return (perScreen * 2.5f).toInt().coerceIn(24, 120)
    }

    /** Releases caches of pages outside [keep] (far from the viewport). */
    fun retainPages(keep: Set<String>) {
        pages.keys.retainAll(keep)
        backgrounds.retain(keep)
        workerRef?.forgetPages(keep)
        pending.removeAll { it.pageId !in keep }
    }

    fun dropPage(pageId: String) {
        pages.remove(pageId)
        backgrounds.drop(pageId)
        pending.removeAll { it.pageId == pageId }
    }

    /** Responds to system memory pressure by dropping everything not on screen. */
    fun trimMemory() {
        val n = host.visiblePages(visible)
        val keep = HashSet<String>()
        for (k in 0 until n) host.holder(visible[k])?.let { keep += it.id }
        for ((id, pt) in pages) {
            pt.prev = null
            if (id !in keep) { pt.level = TileBook(); pt.snapshot = null; pt.snapshotVersion = -1 }
        }
        backgrounds.retain(keep)
        images.trimToHalf()
        workerRef?.trimMemory()
    }

    fun clear() {
        pages.clear()
        pending.clear()
        backgrounds.clear()
        images.clear()
        levelScale = 0f
    }

    /**
     * Stops the worker thread while the view is detached (a new one starts on demand). Jobs in
     * flight are abandoned, so their tiles are marked renderable again.
     */
    fun pause() {
        val w = workerRef ?: return
        workerRef = null
        w.quit()
        inFlight = 0
        for (pt in pages.values) {
            pt.snapshotInFlight = false
            pt.level?.tiles?.values?.forEach { it.inFlight = false }
            pt.prev?.tiles?.values?.forEach { it.inFlight = false }
        }
    }

    fun release() {
        released = true
        clear()
        workerRef?.quit()
        workerRef = null
    }

    companion object {
        private const val MAX_IN_FLIGHT = 2
        private const val FAILURE_BACKOFF_MS = 1500L
        private const val SNAPSHOT_IDLE_MS = 1200L
        private const val SNAPSHOT_PIXELS = 1_000_000f
        private const val SNAPSHOT_PIXELS_INFINITE = 1_500_000f
    }
}
