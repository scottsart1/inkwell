package com.inkwell.notes.ink.model

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.ItemType
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.ItemGeometry

/** An item together with its index in its type list (for exact undo restoration). */
class IndexedItem(val index: Int, val item: Any)

/**
 * The difference between two versions of one page's content. Applying it forward turns
 * "before" into "after"; backward restores "before" exactly (list positions included).
 */
class PageDiff(val pageId: String, val removed: List<IndexedItem>, val added: List<IndexedItem>) {
    val isEmpty: Boolean get() = removed.isEmpty() && added.isEmpty()

    /** Union of the bounds of everything that changed (for partial re-rendering). */
    fun changedBounds(contextBefore: PageContent?, contextAfter: PageContent?): Bounds {
        val out = Bounds()
        val tmp = Bounds()
        for (r in removed) out.union(ItemGeometry.bounds(r.item, tmp, contextBefore))
        for (a in added) out.union(ItemGeometry.bounds(a.item, tmp, contextAfter))
        return out
    }
}

/** Immutable edits of [PageContent]. Pure Kotlin, unit tested. */
object ContentOps {

    fun listOf(content: PageContent, type: ItemType): List<Any> = when (type) {
        ItemType.STROKE -> content.strokes
        ItemType.SHAPE -> content.shapes
        ItemType.TEXT -> content.texts
        ItemType.IMAGE -> content.images
        ItemType.STICKY -> content.stickies
        ItemType.CONNECTOR -> content.connectors
    }

    @Suppress("UNCHECKED_CAST")
    fun withList(content: PageContent, type: ItemType, list: List<Any>): PageContent = when (type) {
        ItemType.STROKE -> content.copy(strokes = list as List<Stroke>)
        ItemType.SHAPE -> content.copy(shapes = list as List<ShapeItem>)
        ItemType.TEXT -> content.copy(texts = list as List<TextItem>)
        ItemType.IMAGE -> content.copy(images = list as List<ImageItem>)
        ItemType.STICKY -> content.copy(stickies = list as List<StickyItem>)
        ItemType.CONNECTOR -> content.copy(connectors = list as List<ConnectorItem>)
    }

    /** Every item of the page in type order (strokes, shapes, texts, images, stickies, connectors). */
    fun allItems(content: PageContent): List<Any> {
        val out = ArrayList<Any>(itemCount(content))
        out.addAll(content.strokes); out.addAll(content.shapes); out.addAll(content.texts)
        out.addAll(content.images); out.addAll(content.stickies); out.addAll(content.connectors)
        return out
    }

    fun itemCount(content: PageContent): Int = content.strokes.size + content.shapes.size + content.texts.size +
        content.images.size + content.stickies.size + content.connectors.size

    fun find(content: PageContent, id: String): Any? {
        content.strokes.firstOrNull { it.id == id }?.let { return it }
        content.shapes.firstOrNull { it.id == id }?.let { return it }
        content.texts.firstOrNull { it.id == id }?.let { return it }
        content.images.firstOrNull { it.id == id }?.let { return it }
        content.stickies.firstOrNull { it.id == id }?.let { return it }
        content.connectors.firstOrNull { it.id == id }?.let { return it }
        return null
    }

    fun minZ(content: PageContent): Int {
        var m = Int.MAX_VALUE
        for (i in allItems(content)) m = minOf(m, Items.z(i))
        return if (m == Int.MAX_VALUE) 0 else m
    }

    /**
     * Renumbers [items]' z values to [base], [base] + 1, ... by distinct z, keeping their relative
     * stacking (and equal-z ties, such as an image under the strokes of its own z).
     */
    fun stackFrom(items: List<Any>, base: Int): List<Any> {
        val zMap = items.map { Items.z(it) }.distinct().sorted().withIndex().associate { (k, z) -> z to base + k }
        return items.map { Items.withZ(it, zMap.getValue(Items.z(it))) }
    }

    /** Removes items by id. Returns the same instance when nothing matched. */
    fun remove(content: PageContent, ids: Set<String>): PageContent {
        if (ids.isEmpty()) return content
        var out = content
        for (type in ItemType.entries) {
            val list = listOf(out, type)
            if (list.none { Items.id(it) in ids }) continue
            out = withList(out, type, list.filter { Items.id(it) !in ids })
        }
        return out
    }

    /** Appends items (an item whose id already exists replaces it in place). */
    fun add(content: PageContent, items: List<Any>): PageContent {
        if (items.isEmpty()) return content
        var out = content
        for (type in ItemType.entries) {
            val ofType = items.filter { Items.type(it) == type }
            if (ofType.isEmpty()) continue
            val list = ArrayList(listOf(out, type))
            val pos = HashMap<String, Int>(list.size * 2)
            list.forEachIndexed { i, it -> pos[Items.id(it)] = i }
            for (item in ofType) {
                val at = pos[Items.id(item)]
                if (at != null) list[at] = item else { pos[Items.id(item)] = list.size; list += item }
            }
            out = withList(out, type, list)
        }
        return out
    }

    /** Replaces items in place by id; ids not present are ignored. */
    fun replace(content: PageContent, replacements: Map<String, Any>): PageContent {
        if (replacements.isEmpty()) return content
        var out = content
        for (type in ItemType.entries) {
            val list = listOf(out, type)
            if (list.none { Items.id(it) in replacements }) continue
            out = withList(out, type, list.map { replacements[Items.id(it)] ?: it })
        }
        return out
    }

    /** Maps the items with the given ids through [transform] in place. */
    fun mapItems(content: PageContent, ids: Set<String>, transform: (Any) -> Any): PageContent {
        val map = HashMap<String, Any>()
        for (item in allItems(content)) { val id = Items.id(item); if (id in ids) map[id] = transform(item) }
        return replace(content, map)
    }

    /** A content fragment holding only the given items (in their page order). */
    fun fragment(content: PageContent, ids: Set<String>): PageContent = PageContent(
        strokes = content.strokes.filter { it.id in ids },
        shapes = content.shapes.filter { it.id in ids },
        texts = content.texts.filter { it.id in ids },
        images = content.images.filter { it.id in ids },
        stickies = content.stickies.filter { it.id in ids },
        connectors = content.connectors.filter { it.id in ids },
    )

    /** Computes the diff turning [before] into [after]. Items compare by identity, then id. */
    fun diff(pageId: String, before: PageContent, after: PageContent): PageDiff {
        if (before === after) return PageDiff(pageId, emptyList(), emptyList())
        val removed = ArrayList<IndexedItem>()
        val added = ArrayList<IndexedItem>()
        for (type in ItemType.entries) {
            val b = listOf(before, type)
            val a = listOf(after, type)
            if (b === a) continue
            val aById = HashMap<String, Any>(a.size * 2)
            for (item in a) aById[Items.id(item)] = item
            val bById = HashMap<String, Any>(b.size * 2)
            for (item in b) bById[Items.id(item)] = item
            b.forEachIndexed { i, item -> if (aById[Items.id(item)] !== item) removed += IndexedItem(i, item) }
            a.forEachIndexed { i, item -> if (bById[Items.id(item)] !== item) added += IndexedItem(i, item) }
        }
        return PageDiff(pageId, removed, added)
    }

    fun applyForward(content: PageContent, diff: PageDiff): PageContent = apply(content, drop = diff.removed, insert = diff.added, alsoDrop = diff.added)

    fun applyBackward(content: PageContent, diff: PageDiff): PageContent = apply(content, drop = diff.added, insert = diff.removed, alsoDrop = diff.removed)

    private fun apply(content: PageContent, drop: List<IndexedItem>, insert: List<IndexedItem>, alsoDrop: List<IndexedItem>): PageContent {
        if (drop.isEmpty() && insert.isEmpty()) return content
        var out = content
        for (type in ItemType.entries) {
            val dropIds = HashSet<String>()
            for (d in drop) if (Items.type(d.item) == type) dropIds += Items.id(d.item)
            for (d in alsoDrop) if (Items.type(d.item) == type) dropIds += Items.id(d.item)
            val ins = insert.filter { Items.type(it.item) == type }.sortedBy { it.index }
            if (dropIds.isEmpty() && ins.isEmpty()) continue
            val list = ArrayList<Any>(listOf(out, type).filter { Items.id(it) !in dropIds })
            for (e in ins) list.add(e.index.coerceIn(0, list.size), e.item)
            out = withList(out, type, list)
        }
        return out
    }
}
