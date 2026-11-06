package com.inkwell.notes.ink.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextConfig
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.InkSelection
import com.inkwell.notes.ink.ItemRef
import com.inkwell.notes.ink.ItemType
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.Geo
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.model.Items
import com.inkwell.notes.ink.render.PageRenderer
import com.inkwell.notes.ink.select.SelectionClipboard
import kotlin.math.max
import kotlin.math.min

/** Content insertion, selection commands and rendering helpers of the public canvas API. */
internal class CanvasEditor(private val e: InkEngine) {
    private val tmp = FloatArray(2)

    fun resolvePage(pageIndex: Int?): Int {
        if (pageIndex != null && e.layoutIndexValid(pageIndex)) return pageIndex
        return e.viewportCtl.currentPageIndex().coerceIn(0, max(0, e.layout.count - 1))
    }

    /** Viewport centre in page coordinates of [i] (clamped onto the page for paged layouts). */
    fun viewportCenter(i: Int): PointF {
        val vp = e.viewport
        if (!vp.hasSize || !e.layoutIndexValid(i)) return PointF(0f, 0f)
        e.viewToPage(i, vp.viewWidth / 2f, vp.viewHeight / 2f, tmp)
        if (!e.layout.isInfinite) {
            tmp[0] = tmp[0].coerceIn(0f, e.layout.pageWidth(i)); tmp[1] = tmp[1].coerceIn(0f, e.layout.pageHeight(i))
        }
        return PointF(tmp[0], tmp[1])
    }

    private fun pageWidthOr(i: Int, fallback: Float): Float = if (e.layout.isInfinite || !e.layoutIndexValid(i)) fallback else e.layout.pageWidth(i)
    private fun pageHeightOr(i: Int, fallback: Float): Float = if (e.layout.isInfinite || !e.layoutIndexValid(i)) fallback else e.layout.pageHeight(i)

    // ---------------------------------------------------------------- Insertion

    fun insertItems(pageIndex: Int?, fragment: PageContent, at: PointF?, select: Boolean) {
        e.flushTools()
        val i = resolvePage(pageIndex)
        if (!e.isEditable(i)) return
        val items = ContentOps.allItems(fragment)
        if (items.isEmpty()) return
        val bounds = PageRenderer.contentBoundsOf(fragment) ?: return
        val target = at ?: viewportCenter(i)
        val dx = target.x - bounds.centerX; val dy = target.y - bounds.centerY
        // Fresh ids (connectors follow), fresh groups, z stacked above the page keeping relative order.
        val idMap = HashMap<String, String>()
        for (it in items) idMap[Items.id(it)] = e.newId()
        val groupMap = HashMap<String, String>()
        val zs = items.map { Items.z(it) }.distinct().sorted()
        val base = e.nextZ(i)
        val zMap = zs.withIndex().associate { (k, z) -> z to base + k }
        // Inserted while recording: the copies belong to "now" on the recording's timeline.
        val anchor = e.audioAnchor()
        val out = items.map { item ->
            var x = Items.withId(item, idMap.getValue(Items.id(item)))
            if (anchor != null) x = Items.withAudio(x, anchor)
            x = Items.translate(x, dx, dy)
            x = Items.withZ(x, zMap.getValue(Items.z(item)))
            Items.groupId(item)?.let { g -> x = Items.withGroup(x, groupMap.getOrPut(g) { e.newId() }) }
            Items.remapConnector(x, idMap)
        }
        e.commitNewItems(i, out, "Insert", select)
    }

    fun insertImage(pageIndex: Int?, assetId: String, widthPx: Int, heightPx: Int, at: PointF?) {
        e.flushTools()
        val i = resolvePage(pageIndex)
        if (!e.isEditable(i)) return
        val wpx = widthPx.coerceAtLeast(1).toFloat(); val hpx = heightPx.coerceAtLeast(1).toFloat()
        // Natural size at ~160 dpi, fitted into 60% of the page.
        var w = wpx * 72f / 160f
        var h = hpx * 72f / 160f
        val maxW = pageWidthOr(i, 520f) * 0.6f
        val maxH = pageHeightOr(i, 520f) * 0.6f
        val fit = min(1f, min(maxW / w, maxH / h))
        w *= fit; h *= fit
        val c = at ?: viewportCenter(i)
        val item = ImageItem(
            id = e.newId(), assetId = assetId, x = c.x - w / 2f, y = c.y - h / 2f, width = w, height = h,
            z = e.nextZ(i), createdAt = System.currentTimeMillis(), audio = e.audioAnchor(),
        )
        e.commitNewItems(i, listOf(item), "Insert image", select = true)
    }

    private val textPaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    private fun measureText(text: String, cfg: TextConfig, maxWidth: Float, fixedWidth: Float? = null): Pair<Float, Float> {
        textPaint.textSize = cfg.fontSizePt.coerceIn(4f, 400f)
        textPaint.isFakeBoldText = cfg.bold
        textPaint.textSkewX = if (cfg.italic) -0.2f else 0f
        val desired = fixedWidth ?: (text.split('\n').maxOfOrNull { Layout.getDesiredWidth(it, textPaint) } ?: 0f).let { it + 4f }
        val width = desired.coerceIn(min(60f, maxWidth), maxWidth)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, max(1, width.toInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
        return width to max(layout.height.toFloat(), cfg.fontSizePt * 1.3f)
    }

    fun insertText(pageIndex: Int?, text: String, at: PointF?, config: TextConfig?, origin: String?): ItemRef? {
        e.flushTools()
        val i = resolvePage(pageIndex)
        if (!e.isEditable(i)) return null
        val h = e.page(i) ?: return null
        val cfg = config ?: e.toolState.text
        val pageW = pageWidthOr(i, 0f)
        val startX = at?.x
        val maxWidth = if (pageW > 0f) max(80f, pageW - (startX ?: 48f) - 36f) else 520f
        val (w, ht) = measureText(text, cfg, maxWidth)
        val pos = at ?: viewportCenter(i).let { PointF(it.x - w / 2f, it.y - ht / 2f) }
        val item = TextItem(
            id = e.newId(), x = pos.x, y = pos.y, width = w, height = ht, text = text,
            fontSizePt = cfg.fontSizePt, color = cfg.color, bold = cfg.bold, italic = cfg.italic,
            z = e.nextZ(i), createdAt = System.currentTimeMillis(), audio = e.audioAnchor(), origin = origin,
        )
        e.commitNewItems(i, listOf(item), "Add text")
        return ItemRef(h.id, item.id, ItemType.TEXT)
    }

    fun updateText(ref: ItemRef, text: String, config: TextConfig?) {
        e.flushTools()
        val i = e.indexOf(ref.pageId)
        if (i < 0) return
        e.applyEdit(i, "Edit text") { c ->
            val old = c.texts.firstOrNull { it.id == ref.itemId } ?: return@applyEdit c
            // Emptying a box removes it; arrows attached to it keep their end where it was.
            if (text.isBlank()) return@applyEdit SelectionClipboard.detachRemoved(c, ContentOps.remove(c, setOf(old.id)))
            val cfg = config ?: TextConfig(old.fontSizePt, old.color, old.bold, old.italic)
            val pageW = pageWidthOr(i, 0f)
            val maxWidth = if (pageW > 0f) max(old.width, pageW - old.x - 24f).coerceAtLeast(60f) else max(old.width, 520f)
            // A box the user narrowed (its text wrapped) keeps its width; others grow with the text.
            val oldCfg = TextConfig(old.fontSizePt, old.color, old.bold, old.italic)
            val wrapped = old.text.isNotEmpty() && measureText(old.text, oldCfg, maxWidth).first > old.width + 2f
            val width = if (wrapped) old.width else max(measureText(text, cfg, maxWidth).first, min(old.width, maxWidth))
            // The height always fits the text.
            val ht = measureText(text, cfg, width, fixedWidth = width).second
            val updated = old.copy(
                text = text, fontSizePt = cfg.fontSizePt, color = cfg.color, bold = cfg.bold, italic = cfg.italic,
                width = width, height = ht,
            )
            if (updated == old) c else ContentOps.replace(c, mapOf(old.id to updated))
        }
    }

    /**
     * [t] after a transform of [original]: when its width changed but its font did not (an edge
     * resize), the height follows the re-wrapped text; the box keeps its top edge.
     */
    fun refitText(t: TextItem, original: TextItem): TextItem {
        if (t.text.isEmpty()) return t
        val cfg = TextConfig(t.fontSizePt, t.color, t.bold, t.italic)
        val (_, h) = try { measureText(t.text, cfg, t.width, fixedWidth = t.width) } catch (x: Throwable) { return t }
        if (kotlin.math.abs(h - t.height) < 0.5f) return t
        // Grow or shrink downwards in the box's own (rotated) frame.
        val a = t.rotation * Math.PI.toFloat() / 180f
        val dh = (h - t.height) / 2f
        val cx = t.x + t.width / 2f - kotlin.math.sin(a) * dh
        val cy = t.y + t.height / 2f + kotlin.math.cos(a) * dh
        return t.copy(x = cx - t.width / 2f, y = cy - h / 2f, height = h)
    }

    fun addSticky(pageIndex: Int?, at: PointF?, color: Int?): ItemRef? {
        e.flushTools()
        val i = resolvePage(pageIndex)
        if (!e.isEditable(i)) return null
        val h = e.page(i) ?: return null
        val c = at ?: viewportCenter(i)
        val size = 180f
        val item = StickyItem(
            id = e.newId(), x = c.x - size / 2f, y = c.y - size / 2f, width = size, height = size,
            color = color ?: e.toolState.stickyColor, z = e.nextZ(i), createdAt = System.currentTimeMillis(),
            audio = e.audioAnchor(),
        )
        e.commitNewItems(i, listOf(item), "Add sticky")
        return ItemRef(h.id, item.id, ItemType.STICKY)
    }

    fun updateSticky(ref: ItemRef, text: String, color: Int?) {
        e.flushTools()
        val i = e.indexOf(ref.pageId)
        if (i < 0) return
        e.applyEdit(i, "Edit sticky") { c ->
            val old = c.stickies.firstOrNull { it.id == ref.itemId } ?: return@applyEdit c
            val updated = old.copy(text = text, color = color ?: old.color)
            if (updated == old) c else ContentOps.replace(c, mapOf(old.id to updated))
        }
    }

    // ---------------------------------------------------------------- Selection

    private fun selectionPage(): Int = e.selectionPageIndex

    fun buildSelection(): InkSelection? {
        val i = selectionPage()
        val h = e.page(i) ?: return null
        val ids = e.selectedIds
        if (ids.isEmpty() || !h.isLoaded) return null
        val fragment = ContentOps.fragment(h.content, ids)
        val items = ContentOps.allItems(fragment)
        if (items.isEmpty()) return null
        val b = selectionBounds(h.content, items)
        val view = RectF()
        e.pageBoundsToView(i, b, view)
        return InkSelection(
            pageId = h.id, pageIndex = i, items = items.map { Items.ref(h.id, it) }, content = fragment,
            boundsPage = RectF(b.left, b.top, b.right, b.bottom), boundsView = view,
        )
    }

    private fun selectionBounds(content: PageContent, items: List<Any>): Bounds {
        val b = Bounds()
        val t = Bounds()
        for (it in items) b.union(ItemGeometry.bounds(it, t, content))
        return b
    }

    fun selectAll(pageIndex: Int?) {
        e.flushTools()
        val i = resolvePage(pageIndex)
        val h = e.page(i) ?: return
        if (!h.isLoaded) return
        val ids = ContentOps.allItems(h.content).mapTo(LinkedHashSet()) { Items.id(it) }
        if (ids.isEmpty()) e.clearSelection() else e.setSelection(i, ids)
    }

    fun selectItems(pageId: String, itemIds: Collection<String>) {
        e.flushTools()
        val i = e.indexOf(pageId)
        val h = e.page(i) ?: return
        if (!h.isLoaded) return
        val ids = itemIds.filterTo(LinkedHashSet()) { ContentOps.find(h.content, it) != null }
        if (ids.isEmpty()) e.clearSelection() else e.setSelection(i, ids)
    }

    private inline fun editSelection(label: String, crossinline transform: (PageContent, Set<String>) -> PageContent): Boolean {
        e.flushTools()
        val i = selectionPage()
        if (i < 0) return false
        val ids = e.selectedIds
        if (ids.isEmpty()) return false
        val changed = e.applyEdit(i, label) { c -> transform(c, ids) }
        if (changed) e.listener?.onSelectionChanged(buildSelection())
        return changed
    }

    fun deleteSelection() {
        e.flushTools()
        val i = selectionPage()
        if (i < 0) return
        val ids = e.selectedIds
        // Connectors attached to deleted items keep a free end where the item was.
        e.applyEdit(i, "Delete") { c ->
            val keep = ContentOps.allItems(c).map { Items.id(it) }.filterTo(HashSet()) { it !in ids }
            val detached = c.connectors.filter { it.id !in ids && (it.fromItemId in ids || it.toItemId in ids) }
                .associate { it.id to (SelectionClipboard.detachOutside(it, c, keep) as Any) }
            ContentOps.replace(ContentOps.remove(c, ids), detached)
        }
        e.clearSelection()
    }

    /** The selected items as a fragment whose bounds start at (0, 0) (see [SelectionClipboard.copy]). */
    fun copySelection(): PageContent? {
        e.flushTools()
        val i = selectionPage()
        val h = e.page(i) ?: return null
        if (!h.isLoaded) return null
        return SelectionClipboard.copy(h.content, e.selectedIds)
    }

    fun cutSelection(): PageContent? {
        val c = copySelection() ?: return null
        deleteSelection()
        return c
    }

    /** Copies the selection 16pt down and to the right and selects the copy. */
    fun duplicateSelection() {
        e.flushTools()
        val i = selectionPage()
        val h = e.page(i) ?: return
        if (!h.isLoaded) return
        val ids = e.selectedIds
        val fragment = SelectionClipboard.copy(h.content, ids) ?: return
        val b = SelectionClipboard.boundsOf(ContentOps.fragment(h.content, ids), h.content) ?: return
        insertItems(i, fragment, PointF(b.centerX + DUPLICATE_OFFSET, b.centerY + DUPLICATE_OFFSET), select = true)
    }

    fun recolorSelection(color: Int) { editSelection("Recolor") { c, ids -> ContentOps.mapItems(c, ids) { Items.recolor(it, color) } } }

    fun setSelectionWidth(width: Float) { editSelection("Change width") { c, ids -> ContentOps.mapItems(c, ids) { Items.withWidth(it, width) } } }

    fun setSelectionOpacity(opacity: Float) { editSelection("Change opacity") { c, ids -> ContentOps.mapItems(c, ids) { Items.withOpacity(it, opacity) } } }

    fun groupSelection() {
        if (e.selectedIds.size < 2) return
        val gid = e.newId()
        editSelection("Group") { c, ids -> ContentOps.mapItems(c, ids) { Items.withGroup(it, gid) } }
    }

    fun ungroupSelection() { editSelection("Ungroup") { c, ids -> ContentOps.mapItems(c, ids) { Items.withGroup(it, null) } } }

    fun bringSelectionToFront() {
        editSelection("Bring to front") { c, ids ->
            val selected = ContentOps.allItems(c).filter { Items.id(it) in ids }.sortedBy { Items.z(it) }
            val othersMax = ContentOps.allItems(c).filter { Items.id(it) !in ids }.maxOfOrNull { Items.z(it) } ?: 0
            val map = HashMap<String, Any>()
            selected.forEachIndexed { k, it -> map[Items.id(it)] = Items.withZ(it, othersMax + 1 + k) }
            ContentOps.replace(c, map)
        }
    }

    fun sendSelectionToBack() {
        editSelection("Send to back") { c, ids ->
            val selected = ContentOps.allItems(c).filter { Items.id(it) in ids }.sortedBy { Items.z(it) }
            val othersMin = ContentOps.allItems(c).filter { Items.id(it) !in ids }.minOfOrNull { Items.z(it) } ?: 0
            val map = HashMap<String, Any>()
            val n = selected.size
            selected.forEachIndexed { k, it -> map[Items.id(it)] = Items.withZ(it, othersMin - n + k) }
            ContentOps.replace(c, map)
        }
    }

    /** Selected handwriting (no highlighter) on the selection page, after committing pending edits. */
    private fun selectedHandwriting(): Pair<Int, List<Stroke>>? {
        e.flushTools()
        val i = selectionPage()
        val h = e.page(i) ?: return null
        if (!h.isLoaded || !e.isEditable(i)) return null
        val ids = e.selectedIds
        val strokes = h.content.strokes.filter { it.id in ids && it.pen != PenType.HIGHLIGHTER }
        return if (strokes.isEmpty()) null else i to strokes
    }

    private fun inkBounds(strokes: List<Stroke>): Bounds {
        val b = Bounds()
        val t = Bounds()
        for (s in strokes) b.union(Geo.polylineBounds(s.points, InkPoints.STRIDE, t))
        return b
    }

    /**
     * Replaces the selected handwriting by a text box holding [text] (convert to text): the box
     * takes the handwriting's place and width, its font size follows the handwriting's line height
     * (shrinking a little when the text runs much longer than the writing), and its colour is the
     * handwriting's main colour. Marked with origin "handwriting".
     */
    fun replaceSelectionWithText(text: String) {
        if (text.isBlank()) return
        val (i, strokes) = selectedHandwriting() ?: return
        val b = inkBounds(strokes)
        if (b.isEmpty) return
        var size = HandwritingLayout.estimateFontSize(strokes)
        val color = strokes.groupingBy { it.color }.eachCount().maxByOrNull { it.value }!!.key or 0xFF000000.toInt()
        val width = max(b.width, 60f)
        var cfg = e.toolState.text.copy(fontSizePt = size, color = color)
        var measured = measureText(text, cfg, width, fixedWidth = width)
        var tries = 0
        while (measured.second > max(b.height * 1.3f, size * 1.4f) && size > MIN_CONVERTED_FONT && tries < 12) {
            size = max(MIN_CONVERTED_FONT, size * 0.9f)
            cfg = cfg.copy(fontSizePt = size)
            measured = measureText(text, cfg, width, fixedWidth = width)
            tries++
        }
        val item = TextItem(
            id = e.newId(), x = b.left, y = b.top, width = measured.first, height = measured.second, text = text,
            fontSizePt = cfg.fontSizePt, color = cfg.color, bold = cfg.bold, italic = cfg.italic,
            z = strokes.maxOf { it.z }, createdAt = System.currentTimeMillis(), origin = "handwriting",
            audio = strokes.firstNotNullOfOrNull { it.audio },
        )
        val removeIds = strokes.mapTo(HashSet()) { it.id }
        if (e.applyEdit(i, "Convert to text") { c -> ContentOps.add(ContentOps.remove(c, removeIds), listOf(item)) }) {
            e.setSelection(i, setOf(item.id))
        }
    }

    /** Rotates selected handwriting so its baseline (a regression through letter bottoms) is horizontal. */
    fun straightenSelection() {
        val (_, strokes) = selectedHandwriting() ?: return
        val angle = HandwritingLayout.baselineAngle(strokes)
        if (kotlin.math.abs(angle) < 0.004f) return
        val b = inkBounds(strokes)
        val strokeIds = strokes.mapTo(HashSet()) { it.id }
        editSelection("Straighten") { c, _ ->
            ContentOps.mapItems(c, strokeIds) { HandwritingLayout.rotate(it as Stroke, -angle, b.centerX, b.centerY) }
        }
    }

    /** Re-wraps selected handwritten words into lines of [widthPt] (null = page text width). */
    fun reflowSelection(widthPt: Float?) {
        val (i, strokes) = selectedHandwriting() ?: return
        val b = inkBounds(strokes)
        val pageW = pageWidthOr(i, 0f)
        val width = widthPt?.coerceAtLeast(40f) ?: if (pageW > 0f) max(80f, pageW - b.left - TEXT_MARGIN) else max(b.width, 400f)
        val moves = HandwritingLayout.reflow(strokes, b.left, b.top, width) ?: return
        editSelection("Reflow") { c, _ -> ContentOps.mapItems(c, moves.keys) { s -> moves.getValue((s as Stroke).id).let { (dx, dy) -> Items.translate(s, dx, dy) } } }
    }

    /** Scales the gaps between handwritten words of the selection by [factor]. */
    fun adjustSelectionWordSpacing(factor: Float) {
        if (factor <= 0f || !factor.isFinite()) return
        val (_, strokes) = selectedHandwriting() ?: return
        val moves = HandwritingLayout.respace(strokes, factor) ?: return
        editSelection("Word spacing") { c, _ -> ContentOps.mapItems(c, moves.keys) { s -> moves.getValue((s as Stroke).id).let { (dx, dy) -> Items.translate(s, dx, dy) } } }
    }

    /**
     * Crops the single selected image by insets (fractions of the source image, 0..1). The
     * visible part stays where it was on the page, also for rotated images.
     */
    fun cropSelectedImage(left: Float, top: Float, right: Float, bottom: Float) {
        e.flushTools()
        val i = selectionPage()
        val h = e.page(i) ?: return
        if (e.selectedIds.size != 1) return
        val img = h.content.images.singleOrNull { it.id in e.selectedIds } ?: return
        val l = left.coerceIn(0f, 0.95f); val r = right.coerceIn(0f, 0.95f - l)
        val tp = top.coerceIn(0f, 0.95f); val bt = bottom.coerceIn(0f, 0.95f - tp)
        val oldFx = (1f - img.cropLeft - img.cropRight).coerceAtLeast(0.05f)
        val oldFy = (1f - img.cropTop - img.cropBottom).coerceAtLeast(0.05f)
        val ptPerFx = img.width / oldFx; val ptPerFy = img.height / oldFy
        val nw = (1f - l - r) * ptPerFx; val nh = (1f - tp - bt) * ptPerFy
        // Centre shift in the image's own frame, then rotated onto the page.
        val dxl = (l - img.cropLeft) * ptPerFx + nw / 2f - img.width / 2f
        val dyl = (tp - img.cropTop) * ptPerFy + nh / 2f - img.height / 2f
        val a = img.rotation * Math.PI.toFloat() / 180f
        val cx = img.x + img.width / 2f + dxl * kotlin.math.cos(a) - dyl * kotlin.math.sin(a)
        val cy = img.y + img.height / 2f + dxl * kotlin.math.sin(a) + dyl * kotlin.math.cos(a)
        val cropped = img.copy(
            x = cx - nw / 2f, y = cy - nh / 2f, width = nw, height = nh,
            cropLeft = l, cropTop = tp, cropRight = r, cropBottom = bt,
        )
        editSelection("Crop") { c, _ -> ContentOps.replace(c, mapOf(img.id to cropped)) }
    }

    // ---------------------------------------------------------------- Rendering helpers

    fun pageContent(pageIndex: Int): PageContent? = e.page(pageIndex)?.takeIf { it.isLoaded }?.content

    fun renderPageBitmap(pageIndex: Int, widthPx: Int): Bitmap? {
        val h = e.page(pageIndex) ?: return null
        if (!h.isLoaded || widthPx <= 0) return null
        return try {
            val meta = if (e.layoutIndexValid(pageIndex)) e.renderMeta(pageIndex) else h.meta
            PageRenderer.renderToBitmap(meta, h.content, widthPx, e.render.backgrounds.bitmap(h.id), { e.render.images.get(it) })
        } catch (t: Throwable) {
            Log.e(TAG, "Page bitmap failed", t)
            null
        }
    }

    fun renderSelectionBitmap(maxPx: Int): Bitmap? {
        val sel = buildSelection() ?: return null
        val b = sel.boundsPage
        if (b.isEmpty || maxPx <= 0) return null
        return try {
            val pad = 4f
            val w = b.width() + pad * 2; val hgt = b.height() + pad * 2
            val s = min(maxPx / w, maxPx / hgt).coerceAtMost(8f)
            val bmp = Bitmap.createBitmap((w * s).toInt().coerceAtLeast(1), (hgt * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(s, s)
            c.translate(pad - b.left, pad - b.top)
            PageRenderer.drawContent(c, sel.content, { e.render.images.get(it) })
            bmp
        } catch (t: Throwable) {
            Log.e(TAG, "Selection bitmap failed", t)
            null
        }
    }

    companion object {
        private const val TAG = "InkEditor"
        private const val DUPLICATE_OFFSET = 16f
        private const val TEXT_MARGIN = 48f
        private const val MIN_CONVERTED_FONT = 8f
    }
}
