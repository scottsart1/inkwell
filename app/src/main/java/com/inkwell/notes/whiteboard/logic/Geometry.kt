package com.inkwell.notes.whiteboard.logic

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.ShapeKind
import kotlin.math.max
import kotlin.math.min

/** An axis-aligned rectangle in plain Kotlin (android.graphics.RectF is not usable in unit tests). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun union(o: Box): Box = Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    fun inset(dx: Float, dy: Float): Box = Box(left + dx, top + dy, right - dx, bottom - dy)
    fun offset(dx: Float, dy: Float): Box = Box(left + dx, top + dy, right + dx, bottom + dy)
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
    fun intersects(o: Box): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    companion object {
        /** A box from two arbitrary corners. */
        fun of(x1: Float, y1: Float, x2: Float, y2: Float): Box = Box(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }
}

object BoardGeometry {
    /**
     * Bounds of every item as drawn (strokes include their width, rotated boxes their rotated
     * corners, unattached connector ends included).
     */
    fun contentBounds(content: PageContent): Box? {
        var out: Box? = null
        fun add(b: Box?) { if (b != null) out = out?.union(b) ?: b }
        for (s in content.strokes) add(pointsBounds(s.points, InkPoints.STRIDE)?.inset(-s.width, -s.width))
        for (s in content.shapes) {
            val raw = pointsBounds(s.points, 2) ?: continue
            // Only box shapes carry a rotation (around their centre); others store real vertices.
            val drawn = if (s.kind in ROTATED_SHAPES) BoardAnchors.rotated(raw, s.rotation) else raw
            add(drawn.inset(-s.strokeWidth / 2f, -s.strokeWidth / 2f))
        }
        for (t in content.texts) add(BoardAnchors.rotated(Box(t.x, t.y, t.x + t.width, t.y + t.height), t.rotation))
        for (i in content.images) add(BoardAnchors.rotated(Box(i.x, i.y, i.x + i.width, i.y + i.height), i.rotation))
        for (s in content.stickies) add(Box(s.x, s.y, s.x + s.width, s.y + s.height))
        for (c in content.connectors) {
            if (c.fromItemId == null) add(Box(c.fromX, c.fromY, c.fromX, c.fromY))
            if (c.toItemId == null) add(Box(c.toX, c.toY, c.toX, c.toY))
        }
        return out
    }

    private val ROTATED_SHAPES = setOf(ShapeKind.RECTANGLE, ShapeKind.ROUNDED_RECTANGLE, ShapeKind.ELLIPSE)

    fun pointsBounds(points: FloatArray, stride: Int): Box? {
        if (points.size < 2) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < points.size) {
            l = min(l, points[i]); r = max(r, points[i]); t = min(t, points[i + 1]); b = max(b, points[i + 1])
            i += stride
        }
        return Box(l, t, r, b)
    }
}
