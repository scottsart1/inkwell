package com.inkwell.notes.ink.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.Log
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.model.Items
import com.inkwell.notes.ink.model.LoadState
import com.inkwell.notes.ink.render.PageRenderer
import com.inkwell.notes.ink.tools.EraserTool
import com.inkwell.notes.ui.theme.DarkInkwell
import com.inkwell.notes.ui.theme.LightInkwell
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min

/** Draws the page layer (desk, paper, cached content) and the overlay (live feedback). */
internal class EnginePainter(private val e: InkEngine) {
    private val visible = IntArray(64)
    private val rect = RectF()
    private val clip = RectF()
    private val matrix = Matrix()
    private val region = Bounds()
    private val tmp = FloatArray(2)
    private val tmpBounds = Bounds()

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val messagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val emphasisCache = HashMap<String, Pair<Long, List<Any>>>()

    // ---------------------------------------------------------------- Page layer

    fun drawPages(canvas: Canvas) {
        try {
            drawPagesUnsafe(canvas)
        } catch (t: Throwable) {
            // Never crash the UI from drawing; the next frame retries.
            Log.e(TAG, "Page drawing failed", t)
        }
        e.render.schedule()
    }

    private fun drawPagesUnsafe(canvas: Canvas) {
        e.render.beginFrame()
        val vp = e.viewport
        val layout = e.layout
        val theme = e.theme
        if (!vp.hasSize || layout.count == 0) { canvas.drawColor(theme.desk); return }
        val s = vp.scale
        if (layout.isInfinite) {
            val h = e.page(0) ?: return
            val paper = h.meta.paper
            canvas.drawColor(paper.color)
            region.set(vp.viewToDocX(0f), vp.viewToDocY(0f), vp.viewToDocX(vp.viewWidth), vp.viewToDocY(vp.viewHeight))
            canvas.save()
            e.pageMatrix(0, matrix)
            canvas.concat(matrix)
            PageRenderer.drawPaperRegion(canvas, 0f, 0f, paper, null, region, s, infinite = true)
            canvas.restore()
            e.render.drawPage(canvas, 0, h)
            if (h.state == LoadState.FAILED) drawFailed(canvas, 0f, 0f, vp.viewWidth, vp.viewHeight, paper.color)
            return
        }
        canvas.drawColor(theme.desk)
        val n = e.visiblePages(visible)
        val current = vp.currentPage(layout)
        val shadowR = 6f * e.density
        // Settings > General > "Page edges".
        val edges = runCatching { AppGraph.get(e.context).settings.current.view.showPageShadows }.getOrDefault(true)
        for (k in 0 until n) {
            val i = visible[k]
            val h = e.page(i) ?: continue
            e.pageClip(i, rect)
            val meta = e.renderMeta(i)
            shadowPaint.color = meta.paper.color
            if (edges) shadowPaint.setShadowLayer(shadowR, 0f, 1.5f * e.density, theme.pageShadow) else shadowPaint.clearShadowLayer()
            canvas.drawRect(rect, shadowPaint)
            canvas.save()
            canvas.clipRect(rect)
            // Visible part of the page in page points, for the paper placeholder.
            clip.set(max(rect.left, 0f), max(rect.top, 0f), min(rect.right, vp.viewWidth), min(rect.bottom, vp.viewHeight))
            viewRectToPage(i, clip, region)
            e.render.backgrounds.ensure(meta, s, primary = i == current, viewportMoving = e.isViewportMoving)
            canvas.save()
            e.pageMatrix(i, matrix)
            canvas.concat(matrix)
            PageRenderer.drawPaperRegion(canvas, meta.widthPt, meta.heightPt, meta.paper, e.render.backgrounds.bitmap(h.id), region, s, infinite = false)
            canvas.restore()
            e.render.drawPage(canvas, i, h)
            if (h.state == LoadState.FAILED) drawFailed(canvas, clip.left, clip.top, clip.right, clip.bottom, meta.paper.color)
            canvas.restore()
        }
    }

    private fun viewRectToPage(i: Int, r: RectF, out: Bounds) {
        out.setEmpty()
        e.viewToPage(i, r.left, r.top, tmp); out.union(tmp[0], tmp[1])
        e.viewToPage(i, r.right, r.bottom, tmp); out.union(tmp[0], tmp[1])
        e.viewToPage(i, r.left, r.bottom, tmp); out.union(tmp[0], tmp[1])
        e.viewToPage(i, r.right, r.top, tmp); out.union(tmp[0], tmp[1])
    }

    private fun drawFailed(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, paperColor: Int) {
        if (r <= l || b <= t) return
        val light = luminance(paperColor) > 0.5f
        messagePaint.color = if (light) LightInkwell.inkMuted.toArgb() else DarkInkwell.inkMuted.toArgb()
        messagePaint.textSize = 15f * e.density
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        canvas.drawText(e.context.getString(R.string.ink_page_load_failed), cx, cy, messagePaint)
        messagePaint.textSize = 13f * e.density
        canvas.drawText(e.context.getString(R.string.ink_page_load_retry), cx, cy + 22f * e.density, messagePaint)
    }

    private fun luminance(c: Int): Float = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    // ---------------------------------------------------------------- Overlay

    fun drawOverlay(canvas: Canvas) {
        try {
            drawPending(canvas)
            drawPlayback(canvas)
            e.wetInk.drawInOverlay(canvas)
            drawSelection(canvas)
            drawFlash(canvas)
            for (tool in e.router.activeTools()) tool.drawOverlay(e, canvas)
            for (o in e.overlays) o.draw(e, canvas)
            drawHoverCursor(canvas)
        } catch (t: Throwable) {
            Log.e(TAG, "Overlay drawing failed", t)
        }
        e.wetInk.onOverlayDrawn()
    }

    private fun drawPending(canvas: Canvas) {
        val pending = e.render.pending
        if (pending.isEmpty()) return
        for (p in pending) {
            val i = e.indexOf(p.pageId)
            if (i < 0 || !e.layoutIndexValid(i)) continue
            val content = e.page(i)?.content ?: PageContent.EMPTY
            canvas.save()
            if (e.pageClip(i, clip)) canvas.clipRect(clip)
            e.pageMatrix(i, matrix)
            canvas.concat(matrix)
            for (item in p.items) drawItem(canvas, item, content)
            canvas.restore()
        }
    }

    private fun drawItem(canvas: Canvas, item: Any, content: PageContent) {
        when (item) {
            is Stroke -> PageRenderer.drawStroke(canvas, item)
            is ShapeItem -> PageRenderer.drawShape(canvas, item)
            is TextItem -> PageRenderer.drawText(canvas, item)
            is ImageItem -> PageRenderer.drawImage(canvas, item) { e.render.images.get(it) }
            is StickyItem -> PageRenderer.drawSticky(canvas, item)
            is ConnectorItem -> PageRenderer.drawConnector(canvas, item, content)
        }
    }

    private fun drawPlayback(canvas: Canvas) {
        val pb = e.playback ?: return
        val n = e.visiblePages(visible)
        val accent = e.theme.accent
        for (k in 0 until n) {
            val i = visible[k]
            val h = e.page(i) ?: continue
            if (!h.isLoaded) continue
            val anchored = anchoredItems(h.id, h.version, h.content)
            if (anchored.isEmpty()) continue
            var saved = false
            for (item in anchored) {
                if (!pb.matches(Items.audio(item))) continue
                if (!saved) {
                    canvas.save()
                    if (e.pageClip(i, clip)) canvas.clipRect(clip)
                    e.pageMatrix(i, matrix)
                    canvas.concat(matrix)
                    saved = true
                }
                if (item is Stroke) {
                    PageRenderer.drawStroke(canvas, item, emphasized = true)
                } else {
                    val b = ItemGeometry.bounds(item, tmpBounds, h.content)
                    fillPaint.color = e.theme.withAlpha(accent, 0x22)
                    canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, 4f, 4f, fillPaint)
                    ringPaint.color = e.theme.withAlpha(accent, 0x99)
                    ringPaint.strokeWidth = 1.5f / e.scale * e.density
                    canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, 4f, 4f, ringPaint)
                }
            }
            if (saved) canvas.restore()
        }
    }

    private fun anchoredItems(pageId: String, version: Long, content: PageContent): List<Any> {
        val cached = emphasisCache[pageId]
        if (cached != null && cached.first == version) return cached.second
        val list = ContentOps.allItems(content).filter { Items.audio(it) != null }
        if (emphasisCache.size > 64) emphasisCache.clear()
        emphasisCache[pageId] = version to list
        return list
    }

    private fun drawSelection(canvas: Canvas) {
        e.selection.draw(canvas)
    }

    private fun drawFlash(canvas: Canvas) {
        val vc = e.viewportCtl
        if (vc.flashPage < 0 || vc.flashAlpha <= 0f || !e.layoutIndexValid(vc.flashPage)) return
        e.pageBoundsToView(vc.flashPage, vc.flashRect, rect)
        rect.inset(-3f * e.density, -3f * e.density)
        val t = e.theme
        fillPaint.color = t.withAlpha(t.accent, (vc.flashAlpha * 80).toInt())
        canvas.drawRoundRect(rect, 4f * e.density, 4f * e.density, fillPaint)
        ringPaint.color = t.withAlpha(t.accent, (vc.flashAlpha * 200).toInt())
        ringPaint.strokeWidth = 2f * e.density
        canvas.drawRoundRect(rect, 4f * e.density, 4f * e.density, ringPaint)
    }

    private fun drawHoverCursor(canvas: Canvas) {
        val r = e.router
        if (!e.inputPrefs.showHoverCursor || !r.hoverActive || r.stylusInContact) return
        val x = r.hoverX; val y = r.hoverY
        when (val tool = r.effectiveTool) {
            ToolType.ERASER -> EraserTool.drawCursor(e, canvas, x, y, e.toolState.eraser.sizeDp)
            ToolType.PEN, ToolType.PENCIL, ToolType.HIGHLIGHTER -> {
                val cfg = when (tool) { ToolType.PENCIL -> e.toolState.pencil; ToolType.HIGHLIGHTER -> e.toolState.highlighter; else -> e.toolState.pen }
                val radius = max(cfg.width * e.scale / 2f, 2f * e.density)
                val alpha = ((Color.alpha(cfg.color) * cfg.opacity).toInt()).coerceIn(60, 255)
                fillPaint.color = (alpha shl 24) or (cfg.color and 0x00FFFFFF)
                canvas.drawCircle(x, y, radius, fillPaint)
                ringPaint.color = e.theme.withAlpha(e.theme.surface, 0xCC)
                ringPaint.strokeWidth = e.density
                canvas.drawCircle(x, y, radius + e.density * 0.5f, ringPaint)
            }
            else -> Unit
        }
    }

    companion object { private const val TAG = "InkPainter" }
}
