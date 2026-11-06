package com.inkwell.notes.whiteboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import com.inkwell.notes.R
import com.inkwell.notes.ai.ui.AiPanelContext
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PageSizePreset
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.LibraryFilter
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.ink.render.PageRenderer
import com.inkwell.notes.ink.render.RenderOrder
import com.inkwell.notes.whiteboard.kit.DocAssets
import com.inkwell.notes.whiteboard.kit.FileSharing
import com.inkwell.notes.whiteboard.kit.UiMessage
import com.inkwell.notes.whiteboard.logic.BoardAnchors
import com.inkwell.notes.whiteboard.logic.BoardGeometry
import com.inkwell.notes.whiteboard.logic.Box
import com.inkwell.notes.whiteboard.logic.CropMath
import com.inkwell.notes.whiteboard.logic.ExportMath
import com.inkwell.notes.whiteboard.logic.FragmentOps
import com.inkwell.notes.whiteboard.logic.PasteSource
import com.inkwell.notes.whiteboard.logic.RecordLogic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Content copied on a board, kept for pasting into any board of this process. */
object BoardClipboard {
    @Volatile var content: PageContent? = null; private set
    @Volatile var sourceDocId: String? = null; private set
    /** When [content] was copied (wall clock, the system clipboard's time base). */
    @Volatile var copiedAt: Long? = null; private set

    fun set(docId: String, fragment: PageContent?) {
        if (fragment == null || fragment.isEmpty) return
        content = fragment
        sourceDocId = docId
        copiedAt = System.currentTimeMillis()
    }
}

/**
 * Plain text on the system clipboard (e.g. an AI summary copied from the recordings panel).
 * Checks read only the clip description, so they never trigger the system "pasted" notice.
 */
object SystemClipText {
    private fun manager(context: Context): ClipboardManager? = context.getSystemService(ClipboardManager::class.java)

    fun available(context: Context): Boolean = runCatching {
        val d = manager(context)?.primaryClipDescription ?: return false
        d.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || d.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
    }.getOrDefault(false)

    /** When the clip was set (wall clock), or null when unknown. */
    fun timestamp(context: Context): Long? = runCatching { manager(context)?.primaryClipDescription?.timestamp }.getOrNull()?.takeIf { it > 0L }

    fun read(context: Context): String? = runCatching {
        val clip = manager(context)?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        clip.getItemAt(0).coerceToText(context)?.toString()
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Longer whiteboard operations: overview rendering, area export, PDF snippets, moving
 * content between the board and notebooks, and AI context. Heavy work runs off Main.
 */
class BoardOperations(private val c: WhiteboardController) {
    private val graph get() = c.graph
    private val docId get() = c.docId
    private val smallImages = Collections.synchronizedMap(HashMap<String, Bitmap?>())

    /** True while a long operation runs (buttons that start another one are disabled). */
    fun isBusy(): Boolean = c.busy != null

    // ------------------------------------------------------------------ Overview (minimap)

    /** Decodes small copies of the images the overview needs (disk work, call on IO). */
    fun preloadOverviewImages(content: PageContent) {
        for (im in content.images) {
            if (!smallImages.containsKey(im.assetId)) {
                smallImages[im.assetId] = DocAssets.decodeAsset(graph, docId, im.assetId, maxSide = 256)
            }
        }
    }

    /**
     * Renders all content into a bitmap at most [maxW] x [maxH] pixels. CPU only; call on Default.
     * Stops early (returning null) once [isActive] turns false, so a superseded render of a large
     * board does not keep running.
     */
    fun renderOverview(content: PageContent, maxW: Int, maxH: Int, isActive: () -> Boolean = { true }): MinimapImage? {
        val b = BoardGeometry.contentBounds(content) ?: return null
        val bw = max(b.width, 1f)
        val bh = max(b.height, 1f)
        val s = min(maxW / bw, maxH / bh)
        val w = ceil(bw * s).toInt().coerceIn(1, maxW.coerceAtLeast(1))
        val h = ceil(bh * s).toInt().coerceIn(1, maxH.coerceAtLeast(1))
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(s, s)
            canvas.translate(-b.left, -b.top)
            // PageRenderer.drawContent in batches, checking for cancellation between them.
            val ordered = RenderOrder.order(content)
            var i = 0
            while (i < ordered.size) {
                if (!isActive()) {
                    bmp.recycle()
                    return null
                }
                val end = min(i + OVERVIEW_BATCH, ordered.size)
                PageRenderer.drawItems(canvas, content, ordered.subList(i, end), { id -> smallImages[id] }, null, null)
                i = end
            }
            MinimapImage(bmp.asImageBitmap(), Box(b.left, b.top, b.left + bw, b.top + bh))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "Overview rendering failed: ${t.javaClass.simpleName}")
            null
        }
    }


    // ------------------------------------------------------------------ Export area

    /**
     * Renders the board region under [viewRect] (canvas-view pixels of a view sized
     * [viewW] x [viewH]) at twice the on-screen resolution into a PNG in the export cache.
     */
    suspend fun exportArea(viewRect: Box, viewW: Float, viewH: Float): File? {
        val canvas = c.canvas ?: return null
        val vis = canvas.visibleRect(0)?.toBox() ?: c.visible ?: return null
        val region = ExportMath.viewToBoard(viewRect, viewW, viewH, vis)
        if (region.width < 4f || region.height < 4f) {
            c.messages.tryEmit(UiMessage(R.string.wb_export_too_small))
            return null
        }
        runCatching { canvas.flush() }
        val content = canvas.pageContent(0) ?: c.latestContent
        val paper = c.page?.paper?.color ?: 0xFFFFFFFF.toInt()
        val scale = ExportMath.outputScale(region, pxPerPoint = viewW / max(vis.width, 1f))
        val title = c.doc?.title ?: "Whiteboard"
        c.busy = R.string.wb_exporting
        return try {
            val images = withContext(Dispatchers.IO) { decodeRegionImages(content, region, scale) }
            val bmp = withContext(Dispatchers.Default) { renderRegion(content, region, scale, paper, images) }
            withContext(Dispatchers.IO) {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(graph.files.exportsDir, "${FileSharing.safeName(title, "Whiteboard")}_area_$stamp.png")
                try {
                    file.outputStream().use { out -> if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG encoding failed") }
                } finally {
                    bmp.recycle()
                }
                file
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "Area export failed: ${t.javaClass.simpleName}")
            c.messages.tryEmit(UiMessage(R.string.wb_export_failed))
            null
        } finally {
            c.busy = null
        }
    }

    /** Images inside [region], decoded at the export resolution. Disk work: call on IO. */
    private fun decodeRegionImages(content: PageContent, region: Box, scale: Float): Map<String, Bitmap?> {
        val images = HashMap<String, Bitmap?>()
        for (im in content.images) {
            if (im.assetId in images) continue
            // A rotated image reaches past its unrotated box.
            if (!BoardAnchors.rotated(Box(im.x, im.y, im.x + im.width, im.y + im.height), im.rotation).intersects(region)) continue
            val need = (max(im.width, im.height) * scale).roundToInt().coerceIn(64, 4096)
            images[im.assetId] = DocAssets.decodeAsset(graph, docId, im.assetId, maxSide = need)
        }
        return images
    }

    private fun renderRegion(content: PageContent, region: Box, scale: Float, paper: Int, images: Map<String, Bitmap?>): Bitmap {
        val w = (region.width * scale).roundToInt().coerceIn(1, 8192)
        val h = (region.height * scale).roundToInt().coerceIn(1, 8192)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(paper)
        canvas.scale(scale, scale)
        canvas.translate(-region.left, -region.top)
        PageRenderer.drawContent(canvas, content, { images[it] })
        images.values.forEach { it?.recycle() }
        return bmp
    }

    // ------------------------------------------------------------------ PDF snippet

    /** Renders [pdfPageIndex] of [pdfDocId], crops it to [crop] (0..1) and places it on the board. */
    suspend fun insertPdfSnippet(pdfDocId: String, pdfPageIndex: Int, pageSizePt: Pair<Float, Float>, crop: Box): Boolean {
        val canvas = c.canvas ?: return false
        c.busy = R.string.cd_working
        try {
            val (pw, ph) = pageSizePt
            val aspect = if (pw > 0f && ph > 0f) ph / pw else 1.414f
            // Enough pixels that the cropped part is sharp, within a memory budget.
            var renderW = (1400f / max(crop.width, 0.05f)).coerceIn(1600f, 3000f)
            if (renderW * renderW * aspect > MAX_RENDER_PIXELS) renderW = sqrt(MAX_RENDER_PIXELS / aspect)
            val rw = renderW.roundToInt()
            val rh = (renderW * aspect).roundToInt().coerceAtLeast(1)
            val page = graph.pdf.renderPage(pdfDocId, pdfPageIndex, rw, rh)
            if (page == null) {
                c.messages.tryEmit(UiMessage(R.string.wb_snippet_render_failed))
                return false
            }
            val stored = withContext(Dispatchers.Default) {
                val px = CropMath.toPixels(crop, page.width, page.height)
                val cropped = Bitmap.createBitmap(page, px.left, px.top, px.width, px.height)
                try {
                    DocAssets.saveBitmap(graph, docId, cropped, forcePng = true)
                } finally {
                    // The renderer may cache the full page; only recycle our own copy.
                    if (cropped !== page) cropped.recycle()
                }
            }
            canvas.insertImage(0, stored.assetId, stored.width, stored.height, null)
            c.messages.tryEmit(UiMessage(R.string.wb_snippet_inserted))
            return true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "PDF snippet failed: ${t.javaClass.simpleName}")
            c.messages.tryEmit(UiMessage(R.string.wb_snippet_failed))
            return false
        } finally {
            c.busy = null
        }
    }

    // ------------------------------------------------------------------ Images

    suspend fun insertPickedImage(context: android.content.Context, uri: android.net.Uri, at: PointF?) {
        val canvas = c.canvas ?: return
        c.busy = R.string.cd_working
        try {
            val stored = DocAssets.importImage(context, graph, docId, uri)
            canvas.insertImage(0, stored.assetId, stored.width, stored.height, at)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "Image import failed: ${t.javaClass.simpleName}")
            c.messages.tryEmit(UiMessage(R.string.cd_image_import_failed))
        } finally {
            c.busy = null
        }
    }

    // ------------------------------------------------------------------ Board <-> notebooks

    /**
     * Adds the current selection as a new page of [notebook] (a new notebook when null),
     * fitted inside the page margins. Returns the notebook and page, or null on failure.
     */
    suspend fun sendSelectionToNotebook(notebook: DocumentMeta?): Pair<DocumentMeta, PageMeta>? {
        val canvas = c.canvas ?: return null
        val selection = canvas.selection()
        if (selection == null || selection.content.isEmpty) {
            c.messages.tryEmit(UiMessage(R.string.wb_select_first))
            return null
        }
        c.busy = R.string.cd_working
        return try {
            // Not cancellable (e.g. by closing the dialog): once the notebook or page exists,
            // stopping before the save would leave it empty.
            withContext(NonCancellable) {
                val target = notebook ?: graph.library.createDocument(DocKind.NOTEBOOK)
                val page = if (notebook == null) {
                    graph.library.getPages(target.id).minByOrNull { it.index } ?: graph.library.addPage(target.id)
                } else {
                    graph.library.addPage(target.id)
                }
                val w = if (page.isInfinite) PageSizePreset.A4.widthPt else page.widthPt
                val h = if (page.isInfinite) PageSizePreset.A4.heightPt else page.heightPt
                val margin = min(48f, min(w, h) * 0.08f)
                val fitted = withContext(Dispatchers.Default) {
                    // The notebook has none of this board's recordings: anchors stay behind.
                    val portable = FragmentOps.withoutAudio(FragmentOps.withFreshIds(selection.content))
                    FragmentOps.fitInto(FragmentOps.compactZ(portable), Box(margin, margin, w - margin, h - margin))
                }
                DocAssets.copyAssets(graph, docId, target.id, FragmentOps.assetIds(fitted))
                graph.pageStore.save(target.id, page.id, fitted)
                runCatching { graph.library.pageContentChanged(target.id, page.id) }
                runCatching { graph.indexing.pageChanged(target.id, page.id) }
                runCatching { graph.sync.onDocumentChanged(target.id) }
                runCatching { graph.thumbnails.refresh(target.id) }
                val fresh = graph.library.getDocument(target.id) ?: target
                fresh to page
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "Send to notebook failed: ${t.javaClass.simpleName}")
            c.messages.tryEmit(UiMessage(R.string.wb_send_failed))
            null
        } finally {
            c.busy = null
        }
    }

    /**
     * Places a notebook or PDF page on the board as editable objects: its ink, shapes, text
     * and images, on a paper-coloured page frame (with the PDF page as an image beneath).
     */
    suspend fun addNotebookPage(source: DocumentMeta, page: PageMeta): Boolean {
        val canvas = c.canvas ?: return false
        c.busy = R.string.cd_working
        try {
            val content = graph.pageStore.load(source.id, page.id)
            DocAssets.copyAssets(graph, source.id, docId, FragmentOps.assetIds(content))
            // Anchors of the notebook's recordings mean nothing on the board.
            var fragment = FragmentOps.compactZ(FragmentOps.withFreshIds(FragmentOps.forDocument(content, source.id, docId)), base = 2)
            if (!page.isInfinite) {
                val w = page.widthPt
                val h = page.heightPt
                val frame = ShapeItem(
                    id = UUID.randomUUID().toString(), kind = ShapeKind.RECTANGLE, points = floatArrayOf(0f, 0f, w, h),
                    strokeColor = 0xFFDDD9CF.toInt(), fillColor = page.paper.color, strokeWidth = 1f, z = 0,
                    createdAt = System.currentTimeMillis(),
                )
                var background: ImageItem? = null
                val pdfIndex = page.pdfPageIndex
                if (pdfIndex != null) {
                    val pxW = (w * 2f).roundToInt().coerceIn(600, 2400)
                    val pxH = (pxW * h / w).roundToInt().coerceAtLeast(1)
                    val bmp = graph.pdf.renderPage(source.id, pdfIndex, pxW, pxH)
                    if (bmp != null) {
                        val stored = DocAssets.saveBitmap(graph, docId, bmp)
                        background = ImageItem(UUID.randomUUID().toString(), stored.assetId, 0f, 0f, w, h, z = 1, createdAt = System.currentTimeMillis())
                    }
                }
                fragment = fragment.copy(
                    shapes = listOf(frame) + fragment.shapes,
                    images = listOfNotNull(background) + fragment.images,
                )
            }
            if (fragment.isEmpty) {
                c.messages.tryEmit(UiMessage(R.string.wb_page_empty))
                return false
            }
            canvas.insertItems(0, fragment, null, select = true)
            c.messages.tryEmit(UiMessage(R.string.wb_page_added))
            return true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "Adding notebook page failed: ${t.javaClass.simpleName}")
            c.messages.tryEmit(UiMessage(R.string.wb_add_page_failed))
            return false
        } finally {
            c.busy = null
        }
    }

    /** Thumbnail of a notebook page: the thumbnail service, else a local render. */
    suspend fun pageThumbnail(doc: DocumentMeta, page: PageMeta, widthPx: Int): Bitmap? {
        runCatching { graph.thumbnails.pageThumbnail(doc.id, page.id, widthPx) }.getOrNull()?.let { return it }
        return try {
            val content = graph.pageStore.load(doc.id, page.id)
            val background = page.pdfPageIndex?.let { idx ->
                val h = if (page.widthPt > 0f) (widthPx * page.heightPt / page.widthPt).roundToInt() else widthPx
                runCatching { graph.pdf.renderPage(doc.id, idx, widthPx, h.coerceAtLeast(1)) }.getOrNull()
            }
            withContext(Dispatchers.Default) {
                val imgs = HashMap<String, Bitmap?>()
                fun img(id: String) = imgs.getOrPut(id) { DocAssets.decodeAsset(graph, doc.id, id, maxSide = 256) }
                PageRenderer.renderToBitmap(page, content, widthPx, background, ::img)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        }
    }

    // ------------------------------------------------------------------ Clipboard

    fun copySelection(cut: Boolean) {
        val canvas = c.canvas ?: return
        val fragment = if (cut) canvas.cutSelection() else canvas.copySelection()
        BoardClipboard.set(docId, fragment)
        if (fragment != null && !cut) c.messages.tryEmit(UiMessage(R.string.wb_copied))
    }

    /** True when there is something to paste: copied board items or text on the system clipboard. */
    fun canPaste(): Boolean = BoardClipboard.content != null || SystemClipText.available(graph.context)

    /**
     * Pastes the newer of the board clipboard and system-clipboard text at [at] (board points,
     * null = centre of the view). Text becomes a new text box; nothing existing is replaced.
     */
    suspend fun paste(at: PointF?) {
        val canvas = c.canvas ?: return
        val context = graph.context
        val hasText = SystemClipText.available(context)
        when (RecordLogic.pasteSource(BoardClipboard.copiedAt.takeIf { BoardClipboard.content != null }, hasText, if (hasText) SystemClipText.timestamp(context) else null)) {
            PasteSource.BOARD -> {
                val fragment = BoardClipboard.content ?: return
                val from = BoardClipboard.sourceDocId
                if (from != null && from != docId) {
                    runCatching { DocAssets.copyAssets(graph, from, docId, FragmentOps.assetIds(fragment)) }
                }
                // Anchors only mean something on the board they were written on.
                canvas.insertItems(0, FragmentOps.forDocument(fragment, from, docId), at, select = true)
            }
            PasteSource.TEXT -> {
                val text = SystemClipText.read(context)
                if (text == null) {
                    c.messages.tryEmit(UiMessage(R.string.wb_audio_nothing_to_paste))
                    return
                }
                canvas.insertText(0, text, at, c.tools.text.copy(fontSizePt = c.tools.text.fontSizePt.coerceIn(12f, 18f)))
            }
            PasteSource.NONE -> c.messages.tryEmit(UiMessage(R.string.wb_audio_nothing_to_paste))
        }
    }

    // ------------------------------------------------------------------ AI

    /** Context for the assistant: the selection (text, recognised handwriting, image) or the whole board's text. */
    suspend fun aiContext(): AiPanelContext {
        val canvas = c.canvas
        val pageId = c.page?.id
        val selection = canvas?.selection()
        if (selection != null && !selection.content.isEmpty) {
            val typed = FragmentOps.boardText(selection.content)
            val handwriting = if (selection.hasHandwriting) recognize(selection.content) else null
            val text = listOfNotNull(handwriting?.takeIf { it.isNotBlank() }, typed.takeIf { it.isNotBlank() }).joinToString("\n\n")
            val image = runCatching { canvas.renderSelectionBitmap(1024) }.getOrNull()
            return AiPanelContext(docId, selectionText = text.ifBlank { null }, selectionImage = image, pageId = pageId, pageIndex = 0)
        }
        val content = canvas?.pageContent(0) ?: c.latestContent
        val typed = FragmentOps.boardText(content)
        val indexed = runCatching {
            graph.search.documentText(docId)
                .filter { it.source == TextSource.HANDWRITING || it.source == TextSource.OCR }
                .joinToString("\n") { it.text }
        }.getOrDefault("")
        val handwriting = indexed.ifBlank { if (content.strokes.isNotEmpty() && content.strokes.size <= 4000) recognize(content).orEmpty() else "" }
        val text = listOf(typed, handwriting).filter { it.isNotBlank() }.joinToString("\n\n")
        return AiPanelContext(docId, selectionText = text.ifBlank { null }, pageId = pageId, pageIndex = 0)
    }

    private suspend fun recognize(content: PageContent): String? {
        val strokes = content.strokes.filter { it.pen != PenType.HIGHLIGHTER }
        if (strokes.isEmpty()) return null
        return withTimeoutOrNull(6_000) {
            runCatching { graph.recognition.recognizeStrokes(strokes) }.getOrNull()?.text
        }
    }

    /** Inserts assistant output as a text box in the middle of the view. */
    fun insertAiText(text: String) {
        val canvas = c.canvas ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        val ref = canvas.insertText(0, clean, null, c.tools.text.copy(fontSizePt = c.tools.text.fontSizePt.coerceIn(12f, 18f)), origin = "ai")
        // Null when the board page cannot be edited (it failed to load): nothing was added.
        c.messages.tryEmit(UiMessage(if (ref != null) R.string.wb_ai_inserted else R.string.ink_page_not_editable))
    }

    // ------------------------------------------------------------------ Paper

    fun setBackground(paper: PaperSpec) {
        val page = c.page ?: return
        c.updatePage(page.copy(paper = paper))
        graph.appScope.launch {
            runCatching { graph.library.setPaper(page.id, paper) }.onFailure { Log.w(TAG, "Saving the background failed", it) }
        }
    }

    /** Library documents of the given kinds, most recently edited first. */
    suspend fun documents(kinds: Set<DocKind>): List<DocumentMeta> =
        (withTimeoutOrNull(5_000) { graph.library.observeDocuments(LibraryFilter(inFolderOnly = false, kinds = kinds)).firstOrNull() } ?: emptyList())
            .filter { it.id != docId && it.trashedAt == null }
            .sortedByDescending { it.modifiedAt }

    companion object {
        private const val TAG = "BoardOperations"
        private const val MAX_RENDER_PIXELS = 12_000_000f
        /** Items drawn between cancellation checks of the overview render. */
        private const val OVERVIEW_BATCH = 500
    }
}
