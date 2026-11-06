package com.inkwell.notes.export.format

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.core.content.res.ResourcesCompat
import com.inkwell.notes.R
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Paginates a typed document onto A4 pages (in points) with Android text layout, so the
 * same pages feed the PDF export (android.graphics.pdf.PdfDocument keeps real, selectable
 * text), PNG/JPEG export, thumbnails and AI snapshots. Long paragraphs and code split
 * between lines; table rows and images move to the next page whole (a row taller than a
 * page is split between its lines).
 */
internal class TypedLayout(
    context: Context,
    private val title: String,
    blocks: List<ExportBlock>,
    private val assetFile: (String) -> File,
    val pageWidth: Float = A4_W,
    val pageHeight: Float = A4_H,
) {
    data class HeadingMark(val level: Int, val text: String, val pageIndex: Int, val top: Float)

    private class Placed(val page: Int, val top: Float, val draw: (Canvas, Float) -> Unit)

    private val regular: Typeface = font(context, R.font.figtree_regular, Typeface.DEFAULT)
    private val semibold: Typeface = font(context, R.font.figtree_semibold, Typeface.DEFAULT_BOLD)
    private val bold: Typeface = font(context, R.font.figtree_bold, Typeface.DEFAULT_BOLD)

    private val margin = 56f
    private val contentW = pageWidth - 2 * margin
    private val bottomLimit = pageHeight - margin
    private val placed = ArrayList<Placed>()
    private val marks = ArrayList<HeadingMark>()
    private var page = 0
    private var y = margin

    val headings: List<HeadingMark> get() = marks
    var pageCount: Int = 1
        private set

    init {
        layoutTitle()
        for (b in blocks) layoutBlock(b)
        pageCount = page + 1
    }

    /** Pixels per point of the current draw, for decoding images at a matching resolution. */
    private var drawScale = 1f

    /**
     * Draws page [index] onto [canvas], whose units are points (scale it for pixels).
     * [pixelsPerPoint] is the output resolution images are decoded for.
     */
    fun draw(canvas: Canvas, index: Int, pixelsPerPoint: Float, paper: Int = PAPER) {
        drawScale = pixelsPerPoint.coerceIn(0.5f, 6f)
        canvas.drawColor(paper)
        for (p in placed) if (p.page == index) p.draw(canvas, p.top)
    }

    /** Renders page [index] [widthPx] wide. */
    fun renderBitmap(index: Int, widthPx: Int): Bitmap {
        val size = RasterSizing.fit(pageWidth, pageHeight, widthPx)
        val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val scale = size.width / pageWidth
        c.scale(scale, scale)
        draw(c, index.coerceIn(0, pageCount - 1), scale)
        return bmp
    }

    // ------------------------------------------------------------------ Layout

    private fun ensure(height: Float) {
        if (y + height > bottomLimit && y > margin + 0.5f) newPage()
    }

    private fun newPage() {
        page++
        y = margin
    }

    private fun layoutTitle() {
        val layout = textLayout(runsOf(title.ifBlank { "Untitled" }), contentW, paint(22f, semibold, INK))
        placeText(layout, margin, 0f)
        y += 14f
        val ruleY = y
        val p = page
        placed += Placed(p, ruleY) { c, top -> c.drawLine(margin, top, margin + contentW, top, linePaint(RULE, 0.75f)) }
        y += 18f
    }

    private fun layoutBlock(b: ExportBlock) {
        when (b) {
            is ExportBlock.Heading -> {
                val size = when (b.level) { 1 -> 17f; 2 -> 14.5f; else -> 12.5f }
                val color = if (b.level >= 3) MUTED else INK
                val layout = textLayout(b.runs, contentW, paint(size, bold, color))
                y += if (b.level == 1) 10f else 6f
                // Keep a heading with the first lines that follow it.
                ensure(layout.height + 40f)
                marks += HeadingMark(b.level, b.runs.plainText(), page, y)
                placeText(layout, margin, 5f)
            }
            is ExportBlock.Paragraph -> placeText(textLayout(b.runs, contentW, bodyPaint()), margin, 7f)
            is ExportBlock.ListItem -> listItem(b)
            is ExportBlock.Quote -> {
                val indent = 14f
                val layout = textLayout(b.runs, contentW - indent, bodyPaint(MUTED), italic = true)
                placeText(layout, margin + indent, 8f, bar = true)
            }
            is ExportBlock.Code -> code(b)
            is ExportBlock.Table -> table(b.rows)
            is ExportBlock.Image -> image(b)
            ExportBlock.Divider -> {
                ensure(14f)
                val top = y + 6f
                placed += Placed(page, top) { c, t -> c.drawLine(margin, t, margin + contentW, t, linePaint(RULE, 0.75f)) }
                y += 14f
            }
            is ExportBlock.PageImage -> Unit
            is ExportBlock.Timestamped -> {
                val runs = listOf(InlineRun("[${formatTimestamp(b.startMs)}] ", bold = true)) + runsOf(b.text)
                placeText(textLayout(runs, contentW, bodyPaint()), margin, 3f)
            }
        }
    }

    private fun listItem(item: ExportBlock.ListItem) {
        val indent = 16f * item.depth + 18f
        val x = margin + indent
        val runs = if (item.kind == ListKind.CHECKLIST && item.checked) item.runs.map { it.copy(strike = true) } else item.runs
        val p = if (item.kind == ListKind.CHECKLIST && item.checked) bodyPaint(MUTED) else bodyPaint()
        val layout = textLayout(runs, contentW - indent, p)
        val firstBaseline = if (layout.lineCount > 0) layout.getLineBaseline(0).toFloat() else 11f
        ensure(min(layout.height.toFloat(), firstBaseline + 6f))
        val markerTop = y
        val markerPage = page
        val markerX = x - 14f
        when (item.kind) {
            ListKind.BULLET -> {
                val glyph = BULLETS[item.depth % BULLETS.size]
                placed += Placed(markerPage, markerTop) { c, t -> c.drawText(glyph, markerX, t + firstBaseline, bodyPaint()) }
            }
            ListKind.NUMBERED -> {
                val label = "${item.number}."
                val mp = bodyPaint().apply { textAlign = Paint.Align.RIGHT }
                placed += Placed(markerPage, markerTop) { c, t -> c.drawText(label, x - 4f, t + firstBaseline, mp) }
            }
            ListKind.CHECKLIST -> {
                val checked = item.checked
                placed += Placed(markerPage, markerTop) { c, t ->
                    val box = RectF(markerX, t + firstBaseline - 8.5f, markerX + 9f, t + firstBaseline + 0.5f)
                    c.drawRoundRect(box, 2f, 2f, linePaint(if (checked) ACCENT else MUTED, 0.9f))
                    if (checked) {
                        val pp = linePaint(ACCENT, 1.2f).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
                        val path = android.graphics.Path().apply {
                            moveTo(box.left + 2f, box.centerY())
                            lineTo(box.left + 3.8f, box.bottom - 2.2f)
                            lineTo(box.right - 1.8f, box.top + 2.2f)
                        }
                        c.drawPath(path, pp)
                    }
                }
            }
        }
        placeText(layout, x, 3f)
    }

    private fun code(b: ExportBlock.Code) {
        val pad = 8f
        val p = paint(9.5f, Typeface.MONOSPACE, INK)
        val text = b.text.trimEnd('\n').ifEmpty { " " }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, p, max(1, (contentW - 2 * pad).toInt()))
            .setIncludePad(false).setLineSpacing(0f, 1.15f).build()
        placeLines(layout, margin + pad, pad, after = 9f, background = CODE_BG)
    }

    private fun table(rows: List<List<String>>) {
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        val colW = contentW / cols
        val pad = 5f
        rows.forEachIndexed { r, row ->
            val header = r == 0
            val layouts = (0 until cols).map { c ->
                val p = paint(9.5f, if (header) bold else regular, INK)
                val txt = row.getOrNull(c).orEmpty().ifEmpty { " " }
                StaticLayout.Builder.obtain(txt, 0, txt.length, p, max(1, (colW - 2 * pad).toInt())).setIncludePad(false).build()
            }
            val contentH = layouts.maxOf { it.height }.toFloat()
            if (contentH + 2 * pad <= bottomLimit - margin) {
                ensure(contentH + 2 * pad)
                placeRow(layouts, header, cols, colW, pad, 0f, contentH)
            } else {
                // A row taller than a page continues on the next pages, cut between lines of its tallest cell.
                if (y > margin + 0.5f) newPage()
                val tallest = layouts.maxBy { it.height }
                val bottoms = (0 until tallest.lineCount).map { tallest.getLineBottom(it).toFloat() }
                RowSlices.of(contentH, bottoms, bottomLimit - margin - 2 * pad).forEachIndexed { i, (from, end) ->
                    if (i > 0) newPage()
                    placeRow(layouts, header, cols, colW, pad, from, end - from)
                }
            }
        }
        y += 9f
    }

    /** Places the cells' text from [from] to [from] + [h] as one bordered table row. */
    private fun placeRow(layouts: List<StaticLayout>, header: Boolean, cols: Int, colW: Float, pad: Float, from: Float, h: Float) {
        val rowH = h + 2 * pad
        placed += Placed(page, y) { c, t ->
            if (header) c.drawRect(margin, t, margin + contentW, t + rowH, fillPaint(CODE_BG))
            layouts.forEachIndexed { i, l ->
                c.save()
                c.clipRect(margin + i * colW, t + pad, margin + (i + 1) * colW, t + pad + h)
                c.translate(margin + i * colW + pad, t + pad - from)
                l.draw(c)
                c.restore()
            }
            val lp = linePaint(RULE, 0.5f)
            c.drawRect(margin, t, margin + contentW, t + rowH, lp)
            for (i in 1 until cols) c.drawLine(margin + i * colW, t, margin + i * colW, t + rowH, lp)
        }
        y += rowH
    }

    private fun image(b: ExportBlock.Image) {
        val file = assetFile(b.assetId)
        val size = ImageFiles.size(file)
        if (size == null) {
            if (b.caption.isNotBlank()) placeText(textLayout(runsOf(b.caption), contentW, paint(9f, regular, MUTED), italic = true), margin, 8f)
            return
        }
        val maxH = (bottomLimit - margin) * 0.85f
        var w = min(contentW, size.first * 72f / 150f)
        var h = w * size.second / size.first
        if (h > maxH) { w *= maxH / h; h = maxH }
        ensure(h + 4f)
        val left = margin + (contentW - w) / 2f
        val top = y
        placed += Placed(page, top) { c, t ->
            val scale = drawScale
            val bmp = ImageFiles.decode(file, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            if (bmp != null) {
                c.drawBitmap(bmp, null, RectF(left, t, left + w, t + h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                bmp.recycle()
            } else {
                c.drawRect(left, t, left + w, t + h, fillPaint(CODE_BG))
            }
        }
        y += h + 4f
        if (b.caption.isNotBlank()) {
            val cp = paint(9f, regular, MUTED)
            val layout = StaticLayout.Builder.obtain(b.caption, 0, b.caption.length, cp, max(1, contentW.toInt()))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
            placeLines(layout, margin, 0f, after = 8f)
        } else {
            y += 8f
        }
    }

    // ------------------------------------------------------------------ Text placement

    private fun placeText(layout: StaticLayout, x: Float, after: Float, bar: Boolean = false) =
        placeLines(layout, x, 0f, after, background = null, bar = bar)

    /** Places [layout] line by line, continuing on the next page when it does not fit. */
    private fun placeLines(layout: StaticLayout, x: Float, pad: Float, after: Float, background: Int? = null, bar: Boolean = false) {
        var line = 0
        y += pad
        while (line < layout.lineCount) {
            val startTop = layout.getLineTop(line).toFloat()
            var end = line
            while (end < layout.lineCount && y + (layout.getLineBottom(end) - startTop) + pad <= bottomLimit) end++
            if (end == line) {
                if (y > margin + pad + 0.5f) { newPage(); y += pad; continue }
                end = line + 1 // a single line taller than a page: draw it clipped
            }
            val bottom = layout.getLineBottom(end - 1).toFloat()
            val h = bottom - startTop
            val top = y
            val from = startTop
            placed += Placed(page, top) { c, t ->
                if (background != null) c.drawRoundRect(RectF(margin, t - pad, margin + contentW, t + h + pad), 6f, 6f, fillPaint(background))
                if (bar) c.drawRect(margin, t, margin + 2.5f, t + h, fillPaint(RULE))
                c.save()
                c.clipRect(x - 2f, t, x + layout.width + 2f, t + h)
                c.translate(x, t - from)
                layout.draw(c)
                c.restore()
            }
            y += h
            line = end
            if (line < layout.lineCount) { newPage(); y += pad }
        }
        y += pad + after
    }

    private fun textLayout(runs: List<InlineRun>, width: Float, p: TextPaint, italic: Boolean = false): StaticLayout {
        val text = spanned(runs.ifEmpty { listOf(InlineRun(" ")) }, italic)
        return StaticLayout.Builder.obtain(text, 0, text.length, p, max(1, width.toInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.25f)
            .setIncludePad(false)
            .build()
    }

    private fun spanned(runs: List<InlineRun>, italicAll: Boolean): CharSequence {
        val sb = SpannableStringBuilder()
        for (r in runs) {
            val start = sb.length
            sb.append(r.text)
            val end = sb.length
            if (end <= start) continue
            val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            if (r.bold) sb.setSpan(TypefaceSpan(bold), start, end, flags)
            if (r.italic) sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, flags)
            if (r.underline || r.link != null) sb.setSpan(UnderlineSpan(), start, end, flags)
            if (r.strike) sb.setSpan(StrikethroughSpan(), start, end, flags)
            if (r.link != null) sb.setSpan(ForegroundColorSpan(ACCENT), start, end, flags)
            if (r.code) {
                sb.setSpan(TypefaceSpan(Typeface.MONOSPACE), start, end, flags)
                sb.setSpan(BackgroundColorSpan(CODE_BG), start, end, flags)
            }
        }
        if (italicAll && sb.isNotEmpty()) sb.setSpan(StyleSpan(Typeface.ITALIC), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return sb
    }

    private fun bodyPaint(color: Int = INK) = paint(11f, regular, color)

    private fun paint(size: Float, face: Typeface, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = face
        this.color = color
    }

    private fun linePaint(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        this.color = color
    }

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    companion object {
        const val A4_W = 595f
        const val A4_H = 842f
        const val INK = 0xFF1A1916.toInt()
        const val MUTED = 0xFF57544C.toInt()
        const val ACCENT = 0xFF2952CC.toInt()
        const val RULE = 0xFFDDD9CF.toInt()
        const val CODE_BG = 0xFFF3F2EE.toInt()
        const val PAPER = 0xFFFFFFFF.toInt()
        private val BULLETS = listOf("•", "◦", "▪")

        private fun font(context: Context, res: Int, fallback: Typeface): Typeface =
            runCatching { ResourcesCompat.getFont(context, res) }.getOrNull() ?: fallback
    }
}

/** Splits a table row taller than a page into page-sized parts. */
internal object RowSlices {
    /**
     * Ranges (from, to) of a row's [contentH] of text, each at most [avail] tall and ending at
     * one of [lineBottoms] (ascending) when a line ends inside it, so lines are not cut in half.
     */
    fun of(contentH: Float, lineBottoms: List<Float>, avail: Float): List<Pair<Float, Float>> {
        if (avail <= 0f) return listOf(0f to contentH)
        val out = ArrayList<Pair<Float, Float>>()
        var from = 0f
        while (from < contentH) {
            val limit = from + avail
            var end = contentH
            if (limit < contentH) {
                end = from
                for (b in lineBottoms) {
                    if (b > limit) break
                    if (b > from) end = b
                }
                if (end <= from) end = limit // a single line taller than a page: cut it
            }
            out += from to end
            from = end
        }
        return out
    }
}
