package com.inkwell.notes.ink.engine

import android.animation.ValueAnimator
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.inkwell.notes.ink.CanvasViewState
import com.inkwell.notes.ink.PageLayoutMode
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.render.PageRenderer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Zoom, scroll, fling and animated navigation of the canvas, plus throttled viewport/page
 * notifications and the reveal highlight flash. Main thread only.
 */
internal class ViewportController(private val e: InkEngine) {
    private val vp get() = e.viewport
    private val handler = Handler(Looper.getMainLooper())
    private val scroller = OverScroller(e.context, DecelerateInterpolator(1.4f))
    private var flinging = false
    private var animator: ValueAnimator? = null
    var pinching = false
        private set
    private var lastChangeAt = 0L
    private var pendingInitial: CanvasViewState? = null
    private var needsInitial = false
    /** The whiteboard centres on its content once it loads, until the user moves. */
    private var autoCenter = false
    private var lastNotifyAt = 0L
    private var notifyScheduled = false
    private var lastReportedPage = -1
    private val range = FloatArray(4)
    private val tmp = FloatArray(2)

    // Reveal highlight flash.
    var flashPage = -1
        private set
    val flashRect = Bounds()
    var flashAlpha = 0f
        private set
    private var flashAnimator: ValueAnimator? = null

    val isMoving: Boolean
        get() = flinging || animator != null || pinching || SystemClock.uptimeMillis() - lastChangeAt < SETTLE_MS

    // ---------------------------------------------------------------- Lifecycle

    fun onBound(initial: CanvasViewState?) {
        stop()
        pendingInitial = initial
        needsInitial = true
        autoCenter = initial == null
        lastReportedPage = -1
        if (vp.hasSize) applyInitial() else e.relayout()
    }

    fun onSizeChanged(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val anchor = if (vp.hasSize && !needsInitial) captureAnchor() else null
        vp.setViewSize(w.toFloat(), h.toFloat())
        e.relayout()
        if (needsInitial) { applyInitial(); return }
        if (anchor != null) restoreAnchor(anchor)
        e.render.updateLevel(force = true)
        changed(userMove = false)
    }

    private fun applyInitial() {
        e.relayout()
        val st = pendingInitial
        needsInitial = false
        pendingInitial = null
        if (st != null) restore(st, notifyNow = false) else defaultPosition()
        e.render.updateLevel(force = true)
        changed(userMove = false)
        e.ensureLoaded()
    }

    private fun defaultPosition() {
        vp.setZoomUnanchored(1f)
        if (e.layout.isInfinite) {
            vp.scrollX = -vp.viewWidth / vp.scale / 2f
            vp.scrollY = -vp.viewHeight / vp.scale / 2f
        } else if (e.layout.count > 0) {
            vp.scrollForPage(e.layout, 0, tmp)
            vp.scrollX = tmp[0]; vp.scrollY = tmp[1]
        }
        vp.clamp(e.layout)
    }

    /** Centres the whiteboard on its content after the first load (if the user has not moved). */
    fun onInfiniteContentLoaded() {
        if (!autoCenter || !e.layout.isInfinite) return
        autoCenter = false
        // Someone already writing on the board keeps the view: moving it under a stroke in
        // progress would add a long stray segment to that stroke.
        if (e.router.stylusInContact || e.router.activeTools().isNotEmpty()) return
        val c = e.page(0)?.content ?: return
        val b = PageRenderer.contentBoundsOf(c) ?: return
        vp.scrollX = b.centerX - vp.viewWidth / vp.scale / 2f
        vp.scrollY = b.centerY - vp.viewHeight / vp.scale / 2f
        changed(userMove = false)
    }

    fun stop() {
        scroller.forceFinished(true)
        flinging = false
        animator?.cancel()
        animator = null
    }

    // ---------------------------------------------------------------- Anchors (resize, page changes)

    class Anchor(val pageId: String?, val px: Float, val py: Float, val viewX: Float, val viewY: Float)

    fun captureAnchor(): Anchor? {
        if (!vp.hasSize || e.layout.count == 0) return null
        val cx = vp.viewWidth / 2f; val cy = vp.viewHeight / 2f
        val i = e.pageIndexAt(cx, cy)
        val h = e.page(i) ?: return null
        e.viewToPage(i, cx, cy, tmp)
        return Anchor(h.id, tmp[0], tmp[1], cx, cy)
    }

    private fun restoreAnchor(a: Anchor) {
        val i = a.pageId?.let { e.indexOf(it) } ?: -1
        if (i < 0 || !e.layoutIndexValid(i)) { vp.clamp(e.layout); return }
        e.layout.pageToDoc(i, a.px, a.py, tmp)
        val s = vp.scale
        vp.scrollX = tmp[0] - (vp.viewWidth / 2f) / s
        vp.scrollY = tmp[1] - (vp.viewHeight / 2f) / s
        vp.clamp(e.layout)
    }

    fun onPagesChanged(anchor: Anchor?) {
        e.relayout()
        if (anchor != null) restoreAnchor(anchor) else vp.clamp(e.layout)
        e.render.updateLevel(force = true)
        changed(userMove = false)
    }

    // ---------------------------------------------------------------- Change propagation

    /** Call after every viewport mutation. */
    fun changed(userMove: Boolean = true) {
        if (userMove) autoCenter = false
        vp.clamp(e.layout)
        lastChangeAt = SystemClock.uptimeMillis()
        e.invalidateAll()
        e.wetInk.onViewportChanged()
        e.router.onViewportChanged()
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, SETTLE_MS)
        notifyThrottled()
        e.ensureLoaded()
        e.render.schedule()
    }

    private val settle = Runnable {
        if (flinging || animator != null || pinching) {
            reschedule()
            return@Runnable
        }
        e.render.updateLevel()
        e.render.schedule()
        notifyNow()
        e.reportSelectionMoved()
        e.ensureLoaded()
    }

    private fun reschedule() {
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, SETTLE_MS)
    }

    private fun notifyThrottled() {
        val now = SystemClock.uptimeMillis()
        if (now - lastNotifyAt >= NOTIFY_MS) { notifyNow(); return }
        if (notifyScheduled) return
        notifyScheduled = true
        handler.postDelayed({ notifyScheduled = false; notifyNow() }, NOTIFY_MS - (now - lastNotifyAt))
    }

    private fun notifyNow() {
        lastNotifyAt = SystemClock.uptimeMillis()
        val l = e.listener ?: return
        l.onViewportChanged(viewState())
        val page = currentPageIndex()
        if (page != lastReportedPage) {
            lastReportedPage = page
            l.onCurrentPageChanged(page)
        }
    }

    // ---------------------------------------------------------------- Gestures

    fun panBy(dx: Float, dy: Float) {
        if (animator != null) { animator?.cancel(); animator = null }
        vp.panByPx(dx, dy)
        changed()
    }

    fun beginPinch() {
        stop()
        pinching = true
    }

    /** One pinch step: scale by [factor] around the focal point, then follow the focal motion. */
    fun pinch(fx: Float, fy: Float, factor: Float, dx: Float, dy: Float) {
        vp.zoomAround(vp.zoom * factor, fx, fy)
        vp.panByPx(dx, dy)
        changed()
    }

    fun endPinch() {
        pinching = false
        reschedule()
    }

    fun zoomAround(factor: Float, fx: Float, fy: Float) {
        stop()
        vp.zoomAround(vp.zoom * factor, fx, fy)
        changed()
    }

    fun fling(vx: Float, vy: Float) {
        stop()
        val s = vp.scale
        vp.scrollRange(e.layout, range)
        val sx = (vp.scrollX * s).roundToInt(); val sy = (vp.scrollY * s).roundToInt()
        val minX = (range[0] * s).toInt().coerceAtMost(sx); val maxX = (range[1] * s).toInt().coerceAtLeast(sx)
        val minY = (range[2] * s).toInt().coerceAtMost(sy); val maxY = (range[3] * s).toInt().coerceAtLeast(sy)
        scroller.fling(sx, sy, (-vx).roundToInt(), (-vy).roundToInt(), minX, maxX, minY, maxY)
        flinging = true
        e.pageView.postOnAnimation(flingStep)
    }

    private val flingStep = object : Runnable {
        override fun run() {
            if (!flinging) return
            if (scroller.computeScrollOffset()) {
                val s = vp.scale
                vp.scrollX = scroller.currX / s
                vp.scrollY = scroller.currY / s
                changed()
                e.pageView.postOnAnimation(this)
            } else {
                flinging = false
                reschedule()
            }
        }
    }

    // ---------------------------------------------------------------- Animated navigation

    private fun animateTo(zoom: Float, scrollX: Float, scrollY: Float, durationMs: Long = 300L) {
        stop()
        val z0 = vp.zoom; val z1 = zoom.coerceIn(vp.minZoom, vp.maxZoom)
        val w = vp.viewWidth; val h = vp.viewHeight
        val s0 = vp.scale; val s1 = vp.baseScale * z1
        val c0x = vp.scrollX + w / (2f * s0); val c0y = vp.scrollY + h / (2f * s0)
        val c1x = scrollX + w / (2f * s1); val c1y = scrollY + h / (2f * s1)
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = durationMs
        a.interpolator = DecelerateInterpolator(1.6f)
        a.addUpdateListener { anim ->
            val t = anim.animatedValue as Float
            val z = z0 * (z1 / z0).pow(t)
            vp.setZoomUnanchored(z)
            val s = vp.scale
            vp.scrollX = c0x + (c1x - c0x) * t - w / (2f * s)
            vp.scrollY = c0y + (c1y - c0y) * t - h / (2f * s)
            changed()
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (animator === animation) animator = null
                reschedule()
            }
        })
        animator = a
        a.start()
    }

    private fun goTo(zoom: Float, scrollX: Float, scrollY: Float, smooth: Boolean) {
        if (!vp.hasSize) return
        // Clamp the target first so animations end exactly where the clamp would put them.
        val oz = vp.zoom; val ox = vp.scrollX; val oy = vp.scrollY
        vp.setZoomUnanchored(zoom); vp.scrollX = scrollX; vp.scrollY = scrollY
        vp.clamp(e.layout)
        val tz = vp.zoom; val tx = vp.scrollX; val ty = vp.scrollY
        vp.setZoomUnanchored(oz); vp.scrollX = ox; vp.scrollY = oy
        if (smooth) animateTo(tz, tx, ty) else {
            stop()
            vp.setZoomUnanchored(tz); vp.scrollX = tx; vp.scrollY = ty
            changed()
        }
    }

    fun scrollToPage(index: Int, smooth: Boolean) {
        if (!e.layoutIndexValid(index)) return
        vp.scrollForPage(e.layout, index, tmp)
        goTo(vp.zoom, tmp[0], tmp[1], smooth)
    }

    /** Scroll that centres document point (dx, dy) at zoom [zoom]. */
    private fun centerScroll(dx: Float, dy: Float, zoom: Float, out: FloatArray) {
        val s = vp.baseScale * zoom.coerceIn(vp.minZoom, vp.maxZoom)
        out[0] = dx - vp.viewWidth / (2f * s)
        out[1] = dy - vp.viewHeight / (2f * s)
    }

    fun centerOn(index: Int, px: Float, py: Float, smooth: Boolean = false) {
        if (!e.layoutIndexValid(index)) return
        e.layout.pageToDoc(index, px, py, tmp)
        centerScroll(tmp[0], tmp[1], vp.zoom, tmp)
        goTo(vp.zoom, tmp[0], tmp[1], smooth)
    }

    fun revealOnPage(index: Int, px: Float, py: Float, highlight: RectF?) {
        if (!e.layoutIndexValid(index)) return
        centerOn(index, px, py, smooth = true)
        if (highlight != null && !highlight.isEmpty) flash(index, Bounds(highlight.left, highlight.top, highlight.right, highlight.bottom))
    }

    private fun flash(index: Int, rect: Bounds) {
        flashAnimator?.cancel()
        flashPage = index
        flashRect.set(rect)
        val a = ValueAnimator.ofFloat(0f, 1f, 0.35f, 1f, 0f)
        a.duration = 1600L
        a.addUpdateListener { flashAlpha = it.animatedValue as Float; e.invalidateOverlay() }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (flashAnimator === animation) { flashAnimator = null; flashPage = -1; flashAlpha = 0f; e.invalidateOverlay() }
            }
        })
        flashAnimator = a
        a.start()
    }

    fun setZoom(zoom: Float) {
        if (!vp.hasSize) return
        stop()
        vp.zoomAround(zoom, vp.viewWidth / 2f, vp.viewHeight / 2f)
        changed()
    }

    fun zoomBy(factor: Float) {
        if (!vp.hasSize || factor <= 0f) return
        setZoom(vp.zoom * factor)
    }

    private fun margin(): Float = vp.marginDp * e.density

    fun fitWidth() {
        if (!vp.hasSize) return
        val i = currentPageIndex()
        if (e.layout.isInfinite) { fitContent(widthOnly = true); return }
        if (!e.layoutIndexValid(i)) return
        val avail = max(vp.viewWidth - 2f * margin(), vp.viewWidth * 0.6f)
        val zoom = avail / e.layout.displayWidth(i) / vp.baseScale
        val l = e.layout
        val cx = (l.left(i) + l.right(i)) / 2f
        val s = vp.baseScale * zoom.coerceIn(vp.minZoom, vp.maxZoom)
        // Keep the vertical position of the current view where possible.
        val keepTop = if (l.mode == PageLayoutMode.VERTICAL) vp.viewToDocY(0f) else l.top(i) - l.gap
        goTo(zoom, cx - vp.viewWidth / (2f * s), keepTop, smooth = true)
    }

    fun fitPage() {
        if (!vp.hasSize) return
        if (e.layout.isInfinite) { fitContent(widthOnly = false); return }
        val i = currentPageIndex()
        if (!e.layoutIndexValid(i)) return
        val l = e.layout
        val m = margin()
        val zw = max(vp.viewWidth - 2f * m, vp.viewWidth * 0.6f) / l.displayWidth(i)
        val zh = max(vp.viewHeight - 2f * m, vp.viewHeight * 0.6f) / l.displayHeight(i)
        val zoom = min(zw, zh) / vp.baseScale
        centerScroll((l.left(i) + l.right(i)) / 2f, (l.top(i) + l.bottom(i)) / 2f, zoom, tmp)
        goTo(zoom, tmp[0], tmp[1], smooth = true)
    }

    private fun fitContent(widthOnly: Boolean) {
        val c = e.page(0)?.content ?: return
        val b = PageRenderer.contentBoundsOf(c)?.outset(24f) ?: return
        val zw = (vp.viewWidth - 2f * margin()) / b.width
        val zh = (vp.viewHeight - 2f * margin()) / b.height
        val zoom = (if (widthOnly) zw else min(zw, zh)) / vp.baseScale
        centerScroll(b.centerX, b.centerY, zoom, tmp)
        goTo(zoom, tmp[0], tmp[1], smooth = true)
    }

    // ---------------------------------------------------------------- State

    fun currentPageIndex(): Int = if (e.layout.count == 0) 0 else vp.currentPage(e.layout)

    fun viewState(): CanvasViewState = CanvasViewState(currentPageIndex(), vp.zoom, vp.scrollX, vp.scrollY)

    fun restore(state: CanvasViewState, notifyNow: Boolean = true) {
        if (!vp.hasSize) { pendingInitial = state; needsInitial = true; return }
        stop()
        if (state == CanvasViewState()) {
            // A never-moved document: open at the default position (page margin / centred board).
            defaultPosition()
            autoCenter = true
            if (notifyNow) {
                e.render.updateLevel(force = true)
                changed(userMove = false)
            }
            return
        }
        val z = if (state.zoom.isFinite() && state.zoom > 0f) state.zoom else 1f
        vp.setZoomUnanchored(z)
        vp.scrollX = if (state.scrollX.isFinite()) state.scrollX else 0f
        vp.scrollY = if (state.scrollY.isFinite()) state.scrollY else 0f
        vp.clamp(e.layout)
        autoCenter = false
        if (e.layoutIndexValid(state.pageIndex) && currentPageIndex() != state.pageIndex) {
            vp.scrollForPage(e.layout, state.pageIndex, tmp)
            vp.scrollX = tmp[0]; vp.scrollY = tmp[1]
            vp.clamp(e.layout)
        }
        if (notifyNow) {
            e.render.updateLevel(force = true)
            changed(userMove = false)
        }
    }

    fun isPageVisible(index: Int): Boolean {
        val out = IntArray(64)
        val n = e.visiblePages(out)
        for (k in 0 until n) if (out[k] == index) return true
        return false
    }

    /** Visible region in page points of [index] (whiteboard minimap); null when off screen. */
    fun visibleRect(index: Int): RectF? {
        if (!vp.hasSize || !e.layoutIndexValid(index)) return null
        if (!e.layout.isInfinite && !isPageVisible(index)) return null
        val b = Bounds()
        val corners = floatArrayOf(0f, 0f, vp.viewWidth, 0f, 0f, vp.viewHeight, vp.viewWidth, vp.viewHeight)
        for (k in 0 until 4) { e.viewToPage(index, corners[k * 2], corners[k * 2 + 1], tmp); b.union(tmp[0], tmp[1]) }
        return RectF(b.left, b.top, b.right, b.bottom)
    }

    companion object {
        const val SETTLE_MS = 140L
        const val NOTIFY_MS = 100L
    }
}
