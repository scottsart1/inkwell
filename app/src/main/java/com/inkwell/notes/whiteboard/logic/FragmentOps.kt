package com.inkwell.notes.whiteboard.logic

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.TextItem
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Pure transforms of [PageContent] fragments (selections, pages moved between documents). */
object FragmentOps {

    fun translate(f: PageContent, dx: Float, dy: Float): PageContent = transform(f, 1f, 0f, 0f, dx, dy)

    /** Scales every coordinate and size by [s] around ([originX], [originY]). */
    fun scale(f: PageContent, s: Float, originX: Float = 0f, originY: Float = 0f): PageContent =
        transform(f, s, originX, originY, 0f, 0f)

    /**
     * Maps x -> originX + (x - originX) * s + dx (same for y). Widths, font sizes and corner
     * radii scale with [s] so the fragment looks identical, just bigger or smaller.
     */
    private fun transform(f: PageContent, s: Float, ox: Float, oy: Float, dx: Float, dy: Float): PageContent {
        fun x(v: Float) = ox + (v - ox) * s + dx
        fun y(v: Float) = oy + (v - oy) * s + dy
        return f.copy(
            strokes = f.strokes.map { st ->
                val p = st.points.copyOf()
                var i = 0
                while (i + InkPoints.STRIDE - 1 < p.size) {
                    p[i + InkPoints.X] = x(p[i + InkPoints.X]); p[i + InkPoints.Y] = y(p[i + InkPoints.Y])
                    i += InkPoints.STRIDE
                }
                st.copy(points = p, width = st.width * s)
            },
            shapes = f.shapes.map { sh ->
                val p = sh.points.copyOf()
                var i = 0
                while (i + 1 < p.size) { p[i] = x(p[i]); p[i + 1] = y(p[i + 1]); i += 2 }
                sh.copy(points = p, strokeWidth = sh.strokeWidth * s, cornerRadius = sh.cornerRadius * s)
            },
            texts = f.texts.map { t -> t.copy(x = x(t.x), y = y(t.y), width = t.width * s, height = t.height * s, fontSizePt = t.fontSizePt * s) },
            images = f.images.map { im -> im.copy(x = x(im.x), y = y(im.y), width = im.width * s, height = im.height * s) },
            stickies = f.stickies.map { st -> st.copy(x = x(st.x), y = y(st.y), width = st.width * s, height = st.height * s) },
            connectors = f.connectors.map { c -> c.copy(fromX = x(c.fromX), fromY = y(c.fromY), toX = x(c.toX), toY = y(c.toY), width = c.width * s) },
        )
    }

    /**
     * Places [f] inside [target]: scaled down to fit (never up unless [allowUpscale]),
     * horizontally centred and aligned to the top of the target. When the scale changes,
     * stickies become a filled box plus a text box ([stickiesAsShapes]): a sticky's text is
     * drawn at a fixed size and would not shrink with the note.
     */
    fun fitInto(f: PageContent, target: Box, allowUpscale: Boolean = false): PageContent {
        val b = BoardGeometry.contentBounds(f) ?: return f
        val bw = maxOf(b.width, 1e-3f)
        val bh = maxOf(b.height, 1e-3f)
        var s = min(target.width / bw, target.height / bh)
        if (!allowUpscale) s = min(s, 1f)
        val scaled = scale(if (s != 1f) stickiesAsShapes(f) else f, s, b.left, b.top)
        val w = bw * s
        val dx = target.left + (target.width - w) / 2f - b.left
        val dy = target.top - b.top
        return translate(scaled, dx, dy)
    }

    /**
     * Replaces every sticky by a filled rounded box (keeping the sticky's id, so attached
     * connectors still find it) and, when it has text, a text box laid out like the sticky's
     * (14pt, 10pt inset) in the same group. Unlike a sticky, both scale with [scale].
     */
    fun stickiesAsShapes(f: PageContent, newId: () -> String = { UUID.randomUUID().toString() }): PageContent {
        if (f.stickies.isEmpty()) return f
        val shapes = ArrayList(f.shapes)
        val texts = ArrayList(f.texts)
        for (st in f.stickies) {
            val group = st.groupId ?: if (st.text.isNotEmpty()) newId() else null
            shapes += ShapeItem(
                id = st.id, kind = ShapeKind.ROUNDED_RECTANGLE, points = floatArrayOf(st.x, st.y, st.x + st.width, st.y + st.height),
                strokeColor = 0, fillColor = st.color, strokeWidth = 0f, z = st.z, createdAt = st.createdAt,
                audio = st.audio, groupId = group, cornerRadius = STICKY_CORNER,
            )
            if (st.text.isNotEmpty()) {
                texts += TextItem(
                    id = newId(), x = st.x + STICKY_INSET, y = st.y + STICKY_INSET,
                    width = max(1f, st.width - 2 * STICKY_INSET), height = max(1f, st.height - 2 * STICKY_INSET),
                    text = st.text, fontSizePt = STICKY_FONT_PT, color = STICKY_TEXT_COLOR, z = st.z,
                    createdAt = st.createdAt, groupId = group,
                )
            }
        }
        return f.copy(shapes = shapes, texts = texts, stickies = emptyList())
    }

    fun assetIds(f: PageContent): Set<String> = f.images.mapTo(LinkedHashSet()) { it.assetId }

    /**
     * Drops every audio anchor. Anchors point at a recording of the document they were written
     * in, so content moving to another document must not keep them ("play from here" and
     * "show notes written here" would follow a recording that is not there).
     */
    fun withoutAudio(f: PageContent): PageContent {
        if (f.strokes.none { it.audio != null } && f.shapes.none { it.audio != null } &&
            f.texts.none { it.audio != null } && f.images.none { it.audio != null } && f.stickies.none { it.audio != null }
        ) return f
        return f.copy(
            strokes = f.strokes.map { if (it.audio == null) it else it.copy(audio = null) },
            shapes = f.shapes.map { if (it.audio == null) it else it.copy(audio = null) },
            texts = f.texts.map { if (it.audio == null) it else it.copy(audio = null) },
            images = f.images.map { if (it.audio == null) it else it.copy(audio = null) },
            stickies = f.stickies.map { if (it.audio == null) it else it.copy(audio = null) },
        )
    }

    /** [f] ready for [targetDocId] when it came from [sourceDocId]: anchors only survive within one document. */
    fun forDocument(f: PageContent, sourceDocId: String?, targetDocId: String): PageContent =
        if (sourceDocId == targetDocId) f else withoutAudio(f)

    /** Gives every item (and group) a new id, keeping connector attachments within the fragment. */
    fun withFreshIds(f: PageContent, newId: () -> String = { UUID.randomUUID().toString() }): PageContent {
        val ids = HashMap<String, String>()
        val groups = HashMap<String, String>()
        fun id(old: String) = ids.getOrPut(old) { newId() }
        fun group(old: String?) = old?.let { groups.getOrPut(it) { newId() } }
        val strokes = f.strokes.map { it.copy(id = id(it.id), groupId = group(it.groupId)) }
        val shapes = f.shapes.map { it.copy(id = id(it.id), groupId = group(it.groupId)) }
        val texts = f.texts.map { it.copy(id = id(it.id), groupId = group(it.groupId)) }
        val images = f.images.map { it.copy(id = id(it.id), groupId = group(it.groupId)) }
        val stickies = f.stickies.map { it.copy(id = id(it.id), groupId = group(it.groupId)) }
        val connectors = f.connectors.map { c ->
            c.copy(id = id(c.id), fromItemId = c.fromItemId?.let { ids[it] }, toItemId = c.toItemId?.let { ids[it] })
        }
        return PageContent(f.version, strokes, shapes, texts, images, stickies, connectors)
    }

    /** Re-stacks z values to 0..n-1 keeping relative order (fragment pasted onto an empty page). */
    fun compactZ(f: PageContent, base: Int = 0): PageContent {
        val zs = (f.strokes.map { it.z } + f.shapes.map { it.z } + f.texts.map { it.z } + f.images.map { it.z } +
            f.stickies.map { it.z } + f.connectors.map { it.z }).distinct().sorted()
        val map = zs.withIndex().associate { (i, z) -> z to base + i }
        return f.copy(
            strokes = f.strokes.map { it.copy(z = map.getValue(it.z)) },
            shapes = f.shapes.map { it.copy(z = map.getValue(it.z)) },
            texts = f.texts.map { it.copy(z = map.getValue(it.z)) },
            images = f.images.map { it.copy(z = map.getValue(it.z)) },
            stickies = f.stickies.map { it.copy(z = map.getValue(it.z)) },
            connectors = f.connectors.map { it.copy(z = map.getValue(it.z)) },
        )
    }

    /** Minimum z of any item (0 for an empty fragment). */
    fun minZ(f: PageContent): Int = listOfNotNull(
        f.strokes.minOfOrNull { it.z }, f.shapes.minOfOrNull { it.z }, f.texts.minOfOrNull { it.z },
        f.images.minOfOrNull { it.z }, f.stickies.minOfOrNull { it.z }, f.connectors.minOfOrNull { it.z },
    ).minOrNull() ?: 0

    /** Typed text on the board (text boxes, stickies, connector labels) in reading order. */
    fun boardText(f: PageContent): String {
        data class Piece(val x: Float, val y: Float, val text: String)
        val pieces = ArrayList<Piece>()
        f.texts.forEach { if (it.text.isNotBlank()) pieces += Piece(it.x, it.y, it.text.trim()) }
        f.stickies.forEach { if (it.text.isNotBlank()) pieces += Piece(it.x, it.y, it.text.trim()) }
        f.connectors.forEach { if (it.label.isNotBlank()) pieces += Piece(minOf(it.fromX, it.toX), minOf(it.fromY, it.toY), it.label.trim()) }
        // Rows of ~40pt, left to right within a row.
        return pieces.sortedWith(compareBy<Piece>({ floor(it.y / 40f).toInt() }, { it.x })).joinToString("\n\n") { it.text }
    }

    // The sticky look of PageRenderer.drawSticky.
    private const val STICKY_CORNER = 6f
    private const val STICKY_INSET = 10f
    private const val STICKY_FONT_PT = 14f
    private const val STICKY_TEXT_COLOR = 0xFF1A1916.toInt()
}
