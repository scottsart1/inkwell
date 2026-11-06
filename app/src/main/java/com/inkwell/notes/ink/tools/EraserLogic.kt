package com.inkwell.notes.ink.tools

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.EraserFilter
import com.inkwell.notes.core.model.EraserMode
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.Geo
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.geometry.SegmentEraser
import com.inkwell.notes.ink.geometry.StrokeSplitter
import com.inkwell.notes.ink.geometry.WordClusterer
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.model.ItemIndex
import com.inkwell.notes.ink.model.Items
import com.inkwell.notes.ink.select.SelectionClipboard
import java.util.UUID

/** Which items an eraser may touch. Text boxes, images and stickies are never erased. */
object EraserRules {
    fun allows(filter: EraserFilter, item: Any): Boolean = when (filter) {
        EraserFilter.ALL -> item is Stroke || item is ShapeItem || item is ConnectorItem
        EraserFilter.INK_ONLY -> item is Stroke && item.pen != PenType.HIGHLIGHTER
        EraserFilter.HIGHLIGHTER_ONLY -> item is Stroke && item.pen == PenType.HIGHLIGHTER
        EraserFilter.SHAPES_ONLY -> item is ShapeItem || item is ConnectorItem
    }
}

/**
 * Applies one eraser movement (segment A-B, reach [radius] in page points) to a page's content.
 * Pure: returns the new content (the same instance when nothing changed) and accumulates the
 * changed area in [changed]. [idFactory] creates ids for split pieces.
 */
class EraserEngine(private val idFactory: () -> String = { UUID.randomUUID().toString() }) {

    fun apply(
        content: PageContent,
        index: ItemIndex,
        mode: EraserMode,
        filter: EraserFilter,
        ax: Float, ay: Float, bx: Float, by: Float,
        radius: Float,
        changed: Bounds,
    ): PageContent {
        val hits = index.hitBySegment(ax, ay, bx, by, radius) { EraserRules.allows(filter, it) }
        if (hits.isEmpty()) return content
        val tmp = Bounds()
        val out = when (mode) {
            EraserMode.STROKE -> removeAll(content, hits, changed, tmp)
            EraserMode.PRECISION -> precision(content, hits, ax, ay, bx, by, radius, changed, tmp)
            EraserMode.SEGMENT -> segment(content, index, filter, hits, bx, by, changed, tmp)
            EraserMode.WORD -> word(content, index, filter, hits, changed, tmp)
        }
        return detachConnectors(content, out, changed, tmp)
    }

    /**
     * Connectors attached to items that are gone in [out] (erased, or split into new pieces) keep
     * a free end where the item was, as when deleting a selection. Their old and new areas both
     * join [changed], since the end still attached elsewhere can re-route.
     */
    private fun detachConnectors(before: PageContent, out: PageContent, changed: Bounds, tmp: Bounds): PageContent {
        if (out === before || out.connectors.isEmpty()) return out
        val keep = ContentOps.allItems(out).mapTo(HashSet<String>()) { Items.id(it) }
        val gone = HashSet<String>()
        for (it in ContentOps.allItems(before)) { val id = Items.id(it); if (id !in keep) gone += id }
        val detached = HashMap<String, ConnectorItem>()
        for (c in out.connectors) {
            if (c.fromItemId !in gone && c.toItemId !in gone) continue
            changed.union(ItemGeometry.bounds(c, tmp, before))
            detached[c.id] = SelectionClipboard.detachOutside(c, before, keep)
        }
        if (detached.isEmpty()) return out
        val result = ContentOps.replace(out, detached)
        for (c in detached.values) changed.union(ItemGeometry.bounds(c, tmp, result))
        return result
    }

    private fun removeAll(content: PageContent, items: List<Any>, changed: Bounds, tmp: Bounds): PageContent {
        val ids = HashSet<String>()
        for (it in items) { ids += com.inkwell.notes.ink.model.Items.id(it); changed.union(ItemGeometry.bounds(it, tmp, content)) }
        return ContentOps.remove(content, ids)
    }

    private fun precision(
        content: PageContent, hits: List<Any>, ax: Float, ay: Float, bx: Float, by: Float, radius: Float,
        changed: Bounds, tmp: Bounds,
    ): PageContent {
        val removeIds = HashSet<String>()
        val pieces = HashMap<String, List<Stroke>>()
        for (item in hits) {
            if (item !is Stroke) {
                // Shapes and connectors have no sub-parts to cut: remove them whole.
                removeIds += com.inkwell.notes.ink.model.Items.id(item)
                changed.union(ItemGeometry.bounds(item, tmp, content))
                continue
            }
            val reach = radius + item.width * 0.35f
            val rest = StrokeSplitter.erase(item.points, InkPoints.STRIDE, ax, ay, bx, by, reach, item.width * 0.3f) ?: continue
            changed.union(ItemGeometry.bounds(item, tmp, content))
            pieces[item.id] = rest.map { item.copy(id = idFactory(), points = it) }
        }
        return replaceStrokes(content, removeIds, pieces)
    }

    private fun segment(
        content: PageContent, index: ItemIndex, filter: EraserFilter, hits: List<Any>, x: Float, y: Float,
        changed: Bounds, tmp: Bounds,
    ): PageContent {
        val removeIds = HashSet<String>()
        val pieces = HashMap<String, List<Stroke>>()
        for (item in hits) {
            if (item !is Stroke) {
                removeIds += com.inkwell.notes.ink.model.Items.id(item)
                changed.union(ItemGeometry.bounds(item, tmp, content))
                continue
            }
            val b = ItemGeometry.strokeBounds(item, Bounds())
            val others = ArrayList<FloatArray>()
            index.query(b) { e ->
                val o = e.item
                if (o is Stroke && o.id != item.id) others += o.points
            }
            // Crossings and sharp corners both bound a segment. Using corners only as a fallback
            // broke closed shapes: a box whose last sample meets (or overshoots) its first has a
            // "crossing" at each end and would lose every side at once, and a box crossed by a
            // single line would lose everything from that crossing round to its end.
            val crossings = SegmentEraser.crossings(item.points, InkPoints.STRIDE, others, includeSelf = true)
            val corners = SegmentEraser.corners(item.points, InkPoints.STRIDE, minSegment = item.width * 1.5f + 1f)
            val cuts = SegmentEraser.merge(crossings, corners)
            val touch = ItemGeometry.nearestArcLength(item, x, y)
            val rest = SegmentEraser.removeSpan(item.points, InkPoints.STRIDE, touch, cuts, item.width * 0.5f)
            changed.union(ItemGeometry.bounds(item, tmp, content))
            pieces[item.id] = rest.map { item.copy(id = idFactory(), points = it) }
        }
        return replaceStrokes(content, removeIds, pieces)
    }

    private fun word(
        content: PageContent, index: ItemIndex, filter: EraserFilter, hits: List<Any>,
        changed: Bounds, tmp: Bounds,
    ): PageContent {
        val removeIds = HashSet<String>()
        for (item in hits) {
            val id = com.inkwell.notes.ink.model.Items.id(item)
            if (id in removeIds) continue
            if (item !is Stroke || item.pen == PenType.HIGHLIGHTER) {
                // Words are handwriting; other items are removed individually.
                removeIds += id
                changed.union(ItemGeometry.bounds(item, tmp, content))
                continue
            }
            val near = ItemGeometry.strokeBounds(item, Bounds()).outset(item.width * 40f + 60f)
            val boxes = ArrayList<WordClusterer.StrokeBox>()
            val byId = HashMap<String, Stroke>()
            index.query(near) { e ->
                val s = e.item
                if (s is Stroke && s.pen != PenType.HIGHLIGHTER && EraserRules.allows(filter, s)) {
                    boxes += boxOf(s)
                    byId[s.id] = s
                }
            }
            for (wid in WordClusterer.wordOf(item.id, boxes)) {
                if (removeIds.add(wid)) byId[wid]?.let { changed.union(ItemGeometry.bounds(it, tmp, content)) }
            }
        }
        return ContentOps.remove(content, removeIds)
    }

    /**
     * Removes [removeIds] and replaces each stroke in [pieces] by its remaining pieces, which
     * take the original stroke's list position (so paint order is unchanged).
     */
    private fun replaceStrokes(content: PageContent, removeIds: Set<String>, pieces: Map<String, List<Stroke>>): PageContent {
        if (removeIds.isEmpty() && pieces.isEmpty()) return content
        var out = content
        if (pieces.isNotEmpty()) {
            val strokes = ArrayList<Stroke>(content.strokes.size + pieces.size)
            for (s in content.strokes) {
                val rest = pieces[s.id]
                if (rest == null) strokes += s else strokes += rest
            }
            out = out.copy(strokes = strokes)
        }
        return ContentOps.remove(out, removeIds)
    }

    companion object {
        fun boxOf(s: Stroke): WordClusterer.StrokeBox {
            val b = Geo.polylineBounds(s.points, InkPoints.STRIDE, Bounds())
            val n = s.pointCount
            val dur = if (n > 0) s.points[(n - 1) * InkPoints.STRIDE + InkPoints.TIME].toLong() else 0L
            return WordClusterer.StrokeBox(s.id, b, s.createdAt, s.createdAt + dur)
        }
    }
}
