package com.inkwell.notes.ink.select

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.model.Items

/** Copy/paste fragments. Pure Kotlin. */
object SelectionClipboard {

    /** Union bounds of every item of [content] (page points), or null when empty. */
    fun boundsOf(content: PageContent, context: PageContent? = content): Bounds? {
        val out = Bounds()
        val tmp = Bounds()
        for (item in ContentOps.allItems(content)) out.union(ItemGeometry.bounds(item, tmp, context))
        return if (out.isEmpty) null else out
    }

    /**
     * The items [ids] of [page] as a self-contained fragment whose bounds start at (0, 0).
     * Connector ends attached to items outside the fragment become free ends at their current
     * position, so a pasted connector looks the way it was copied.
     */
    fun copy(page: PageContent, ids: Set<String>): PageContent? {
        if (ids.isEmpty()) return null
        val raw = ContentOps.fragment(page, ids)
        if (ContentOps.itemCount(raw) == 0) return null
        val connectors = raw.connectors.map { c -> detachOutside(c, page, ids) }
        val detached = raw.copy(connectors = connectors)
        val b = boundsOf(detached) ?: return null
        return translate(detached, -b.left, -b.top)
    }

    /** Moves every item of [content] by (dx, dy). */
    fun translate(content: PageContent, dx: Float, dy: Float): PageContent {
        if (dx == 0f && dy == 0f) return content
        @Suppress("UNCHECKED_CAST")
        return PageContent(
            version = content.version,
            strokes = content.strokes.map { Items.translate(it, dx, dy) as com.inkwell.notes.core.model.Stroke },
            shapes = content.shapes.map { Items.translate(it, dx, dy) as com.inkwell.notes.core.model.ShapeItem },
            texts = content.texts.map { Items.translate(it, dx, dy) as com.inkwell.notes.core.model.TextItem },
            images = content.images.map { Items.translate(it, dx, dy) as com.inkwell.notes.core.model.ImageItem },
            stickies = content.stickies.map { Items.translate(it, dx, dy) as com.inkwell.notes.core.model.StickyItem },
            connectors = content.connectors.map { Items.translate(it, dx, dy) as ConnectorItem },
        )
    }

    /**
     * [after] (an edit of [before] that removed items) with every connector end whose item the
     * edit removed frozen where that item's edge was in [before], as deleting a selection does.
     * Otherwise an arrow whose box was erased or emptied jumps to its stale fallback point.
     * Returns [after] itself when no connector lost an item.
     */
    fun detachRemoved(before: PageContent, after: PageContent): PageContent {
        if (after === before || after.connectors.none { it.fromItemId != null || it.toItemId != null }) return after
        val present = HashSet<String>()
        for (item in ContentOps.allItems(after)) present += Items.id(item)
        var existed: HashSet<String>? = null
        fun removed(id: String?): Boolean {
            if (id == null || id in present) return false
            val ids = existed ?: ContentOps.allItems(before).mapTo(HashSet()) { Items.id(it) }.also { existed = it }
            return id in ids
        }
        var detached: HashMap<String, Any>? = null
        for (c in after.connectors) {
            if (!removed(c.fromItemId) && !removed(c.toItemId)) continue
            val map = detached ?: HashMap<String, Any>().also { detached = it }
            map[c.id] = detachOutside(c, before, present)
        }
        return detached?.let { ContentOps.replace(after, it) } ?: after
    }

    /** Freezes the ends of [c] attached to items not in [keep] at their live positions in [page]. */
    fun detachOutside(c: ConnectorItem, page: PageContent, keep: Set<String>): ConnectorItem {
        val fromOut = c.fromItemId != null && c.fromItemId !in keep
        val toOut = c.toItemId != null && c.toItemId !in keep
        if (!fromOut && !toOut) return c
        val e = ItemGeometry.connectorEndpoints(c, page, FloatArray(4))
        return c.copy(
            fromItemId = if (fromOut) null else c.fromItemId,
            toItemId = if (toOut) null else c.toItemId,
            fromX = if (fromOut) e[0] else c.fromX, fromY = if (fromOut) e[1] else c.fromY,
            toX = if (toOut) e[2] else c.toX, toY = if (toOut) e[3] else c.toY,
        )
    }
}
