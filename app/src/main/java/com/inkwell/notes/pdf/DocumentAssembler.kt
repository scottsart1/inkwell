package com.inkwell.notes.pdf

import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.core.model.Orientation
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PageSizePreset
import com.inkwell.notes.core.model.PageTemplate
import com.inkwell.notes.core.storage.PageOrdering
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.abs

/**
 * Builds new documents out of pages of existing ones (extract, split, merge). The new
 * document's source PDF holds only the PDF pages it references, renumbered; page content
 * (ink, text, shapes), referenced images, paper, size, rotation, bookmarks and outline entries
 * are copied. The source documents and their PDFs are never modified here.
 */
internal class DocumentAssembler(
    private val graph: AppGraph,
    private val engine: PdfRenderEngine,
    private val pdfBox: PdfBoxOps,
    private val importer: PdfImporter,
) {
    private val context get() = graph.context
    private val library get() = graph.library
    private val files get() = graph.files

    /** One page to copy; pages with the same [group] came from the same document occurrence. */
    data class Source(val doc: DocumentMeta, val page: PageMeta, val group: Int = 0)

    suspend fun extract(docId: String, pageIndices: List<Int>, title: String?): DocumentMeta {
        val doc = requirePagedDocument(docId)
        val pages = library.getPages(docId)
        val selection = PdfPageMapping.selection(pageIndices, pages.size)
        if (selection.isEmpty()) throw PdfErrors.create(context, PdfError.NO_PAGES_SELECTED)
        val name = title?.let(PdfNames::clean) ?: PdfNames.extractTitle(doc.title, selection)
        return assemble(selection.map { Source(doc, pages[it]) }, name, doc.folderId)
    }

    suspend fun split(docId: String, atPageIndex: Int): DocumentMeta {
        val doc = requirePagedDocument(docId)
        val pages = library.getPages(docId)
        val range = try {
            PdfPageMapping.splitRange(pages.size, atPageIndex)
        } catch (e: IllegalArgumentException) {
            throw PdfErrors.create(context, PdfError.INVALID_SPLIT, e)
        }
        val moved = range.map { pages[it] }
        val created = assemble(moved.map { Source(doc, it) }, PdfNames.extractTitle(doc.title, range.toList()), doc.folderId)
        // Non-destructive: the original keeps its PDF; only its page entries go. Deleting
        // from the end means no remaining page has to be renumbered.
        val remaining = ArrayList<PageMeta>()
        withContext(NonCancellable) {
            for (page in moved.asReversed()) if (!tryDeletePage(page.id)) remaining += page
            if (remaining.size == moved.size) {
                // Nothing left the original: undo the copy rather than leave every page twice.
                discard(created.id)
                throw PdfErrors.create(context, PdfError.SAVE_FAILED)
            }
            val retry = remaining.toList()
            remaining.clear()
            for (page in retry) if (!tryDeletePage(page.id)) remaining += page
        }
        // Pages that still could not be removed exist in both documents; nothing is lost.
        if (remaining.isNotEmpty()) Log.w(TAG, "Split: ${remaining.size} page(s) stayed in the original")
        return created
    }

    private suspend fun tryDeletePage(pageId: String): Boolean = try {
        library.deletePage(pageId)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Removing a page failed (${e.javaClass.simpleName})")
        false
    }

    suspend fun merge(docIds: List<String>, title: String?): DocumentMeta {
        if (docIds.isEmpty()) throw PdfErrors.create(context, PdfError.NO_PAGES_SELECTED)
        val docs = docIds.map { requirePagedDocument(it) }
        val sources = docs.flatMapIndexed { group, d -> library.getPages(d.id).map { Source(d, it, group) } }
        if (sources.isEmpty()) throw PdfErrors.create(context, PdfError.NO_PAGES)
        val name = title?.let(PdfNames::clean) ?: PdfNames.mergedTitle(docs.map { it.title })
        return assemble(sources, name, docs.first().folderId)
    }

    private suspend fun requirePagedDocument(docId: String): DocumentMeta {
        val doc = library.getDocument(docId) ?: throw PdfErrors.create(context, PdfError.DOCUMENT_MISSING)
        if (doc.kind != DocKind.NOTEBOOK && doc.kind != DocKind.PDF) {
            throw PdfErrors.create(context, PdfError.UNSUPPORTED_DOCUMENT)
        }
        return doc
    }

    /** Creates a document from [sources], in order. On failure nothing is left behind. */
    suspend fun assemble(sources: List<Source>, title: String, folderId: String?): DocumentMeta {
        if (sources.isEmpty()) throw PdfErrors.create(context, PdfError.NO_PAGES_SELECTED)

        // Which source PDFs can actually be read (a page may point past a damaged file's end).
        val pdfCounts = HashMap<String, Int>()
        for (s in sources) {
            if (s.page.pdfPageIndex != null && s.doc.id !in pdfCounts) pdfCounts[s.doc.id] = engine.pageCount(s.doc.id)
        }
        val refs = sources.map { s ->
            val idx = s.page.pdfPageIndex
            if (idx != null && idx < (pdfCounts[s.doc.id] ?: 0)) PdfPageMapping.Ref(s.doc.id, idx) else null
        }
        val compaction = PdfPageMapping.compact(refs)
        val kind = if (compaction.keep.isNotEmpty()) DocKind.PDF else DocKind.NOTEBOOK

        // Build the new PDF first, so a failure here never creates a library entry.
        val pdf = if (compaction.keep.isEmpty()) null else buildSubset(compaction.keep)
        try {
            // Created without cancellation so the id always comes back and a cancel is discarded below.
            val created = try {
                // Not cancellable on its own: a document committed just as the operation is
                // cancelled would stay behind empty; populate() notices the cancellation and
                // the handler below removes it.
                withContext(NonCancellable) { library.createDocument(kind, folderId, originalName = title) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "Creating the document failed (${e.javaClass.simpleName})")
                throw PdfErrors.forWrite(context, e)
            }
            try {
                currentCoroutineContext().ensureActive()
                populate(created.id, sources, compaction.remapped, pdf)
            } catch (c: CancellationException) {
                withContext(NonCancellable) { discard(created.id) }
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "Building a document failed (${e.javaClass.simpleName})")
                withContext(NonCancellable) { discard(created.id) }
                throw PdfErrors.forWrite(context, e)
            }
            return library.getDocument(created.id) ?: created
        } finally {
            pdf?.delete()
        }
    }

    private suspend fun buildSubset(keep: List<PdfPageMapping.Ref<String>>): File {
        val out = importer.tempFile("subset-")
        try {
            withContext(Dispatchers.IO) {
                pdfBox.subset(keep.map { files.sourcePdf(it.source) to it.pdfIndex }, out)
            }
            val probe = engine.probe(out)
            if (probe !is PdfRenderEngine.Probe.Ok || probe.sizes.size != keep.size) {
                throw PdfErrors.create(context, PdfError.MERGE_FAILED)
            }
            return out
        } catch (c: CancellationException) {
            out.delete()
            throw c
        } catch (e: PdfException) {
            out.delete()
            throw e
        } catch (e: IOException) {
            out.delete()
            Log.w(TAG, "Copying PDF pages failed (${e.javaClass.simpleName})")
            throw if (PdfErrors.isOutOfSpace(e)) PdfErrors.create(context, PdfError.NO_SPACE, e)
            else PdfErrors.create(context, PdfError.MERGE_FAILED, e)
        } catch (e: RuntimeException) {
            out.delete()
            Log.w(TAG, "Copying PDF pages failed (${e.javaClass.simpleName})")
            throw PdfErrors.create(context, PdfError.MERGE_FAILED, e)
        } catch (oom: OutOfMemoryError) {
            out.delete()
            throw PdfErrors.create(context, PdfError.TOO_LARGE, oom)
        }
    }

    private suspend fun populate(docId: String, sources: List<Source>, remapped: List<Int?>, pdf: File?) {
        // A new NOTEBOOK starts with one template page; it is replaced by the copies.
        val placeholders = library.getPages(docId)
        if (pdf != null) engine.replaceSource(docId, pdf)

        val created = arrayOfNulls<PageMeta>(sources.size)
        for (segment in PdfPageMapping.segments(remapped)) {
            when (segment) {
                is PdfPageMapping.Segment.PdfRun -> {
                    val range = segment.start until segment.start + segment.count
                    val sizes = range.map { sizeOf(sources[it].page) }
                    val pages = library.addPdfPages(docId, sizes, firstPdfIndex = segment.firstPdfIndex, atIndex = null)
                    if (pages.size != sizes.size) throw IOException("Unexpected page count")
                    pages.forEachIndexed { i, p -> created[segment.start + i] = p }
                }
                is PdfPageMapping.Segment.Paper -> {
                    created[segment.start] = library.addPage(docId, atIndex = null, template = templateFor(sources[segment.start].page))
                }
            }
        }

        val withContent = ArrayList<String>()
        sources.forEachIndexed { i, source ->
            val target = created[i] ?: throw IOException("Page was not created")
            copyAttributes(source.page, target)
            if (copyContent(source.doc.id, source.page.id, docId, target.id)) withContent += target.id
        }
        for (p in placeholders) library.deletePage(p.id)
        copyOutline(docId, sources, created)
        notifyCreated(docId, hasPdf = pdf != null, pagesWithContent = withContent)
    }

    private suspend fun copyAttributes(src: PageMeta, dst: PageMeta) {
        val (w, h) = sizeOf(src)
        if (abs(dst.widthPt - w) > 0.5f || abs(dst.heightPt - h) > 0.5f) library.setPageSize(dst.id, w, h)
        if (dst.paper != src.paper) library.setPaper(dst.id, src.paper)
        val rotation = ((src.rotation % 360) + 360) % 360
        if (rotation != 0) library.rotatePage(dst.id, rotation)
        if (src.bookmarked) library.setBookmark(dst.id, true, src.bookmarkTitle)
    }

    /**
     * Copies ink and other items plus the image files they reference. Returns false for empty pages.
     * Audio links are dropped: the recordings belong to the source document.
     *
     * A page whose stored content cannot be read throws [com.inkwell.notes.core.storage.UnreadablePageException]
     * instead of being copied as blank, so the operation is abandoned and a split never deletes
     * the only (possibly recoverable) copy of that page.
     */
    private suspend fun copyContent(fromDoc: String, fromPage: String, toDoc: String, toPage: String): Boolean {
        val content = graph.pageStore.load(fromDoc, fromPage, strict = true)
        if (content.isEmpty) return false
        withContext(Dispatchers.IO) {
            for (assetId in content.images.map { it.assetId }.distinct()) {
                val from = files.assetFile(fromDoc, assetId)
                val to = files.assetFile(toDoc, assetId)
                if (from.isFile && !to.exists()) from.copyTo(to)
            }
        }
        graph.pageStore.save(toDoc, toPage, if (fromDoc == toDoc) content else PageOrdering.withoutAudio(content))
        return true
    }

    /** Outline entries that point at copied pages come along (per source document). */
    private suspend fun copyOutline(docId: String, sources: List<Source>, created: Array<PageMeta?>) {
        val groups = sources.indices.groupBy { sources[it].group }
        for ((group, positions) in groups) {
            val sourceDoc = sources[positions.first()].doc.id
            val entries = try {
                library.observeOutline(sourceDoc).first()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }
            if (entries.isEmpty()) continue
            val pageMap = HashMap<String, String>()
            for (pos in positions) {
                val target = created[pos] ?: continue
                pageMap.putIfAbsent(sources[pos].page.id, target.id)
            }
            val kept = entries.filter { it.pageId in pageMap }
            val ids = kept.associate { it.id to UUID.randomUUID().toString() }
            // The library keeps a parent reference only when the parent already exists.
            for (e in parentsFirst(kept)) {
                library.upsertOutlineEntry(
                    OutlineEntry(
                        id = ids.getValue(e.id),
                        documentId = docId,
                        title = e.title,
                        pageId = pageMap.getValue(e.pageId),
                        parentId = e.parentId?.let { ids[it] },
                        sortOrder = group * OUTLINE_GROUP_STRIDE + e.sortOrder,
                    ),
                )
            }
        }
    }

    private fun notifyCreated(docId: String, hasPdf: Boolean, pagesWithContent: List<String>) {
        runCatching {
            if (hasPdf) graph.indexing.documentImported(docId)
            pagesWithContent.forEach { graph.indexing.pageChanged(docId, it) }
        }.onFailure { Log.w(TAG, "Indexing could not be scheduled (${it.javaClass.simpleName})") }
        runCatching { graph.thumbnails.refresh(docId) }
    }

    private suspend fun discard(docId: String) {
        try {
            engine.close(docId)
            library.deletePermanently(docId)
        } catch (e: Exception) {
            Log.w(TAG, "Cleaning up a partial document failed (${e.javaClass.simpleName})")
        }
    }

    companion object {
        private const val TAG = "PdfAssemble"
        private const val OUTLINE_GROUP_STRIDE = 100_000

        /**
         * [entries] with every parent before its children (otherwise in the given order), so
         * nested outline entries keep their nesting when they are inserted one by one. Entries
         * whose parent is not among [entries], or that sit in a cycle, are treated as roots.
         */
        fun parentsFirst(entries: List<OutlineEntry>): List<OutlineEntry> {
            val byId = entries.associateBy { it.id }
            val out = ArrayList<OutlineEntry>(entries.size)
            val done = HashSet<String>()
            fun visit(e: OutlineEntry) {
                if (!done.add(e.id)) return
                e.parentId?.let { byId[it] }?.let { visit(it) }
                out += e
            }
            for (e in entries) visit(e)
            return out
        }

        fun sizeOf(page: PageMeta): Pair<Float, Float> =
            if (page.widthPt > 0f && page.heightPt > 0f) page.widthPt to page.heightPt
            else PageSizePreset.A4.widthPt to PageSizePreset.A4.heightPt

        /** A template reproducing [page]'s size and paper. */
        fun templateFor(page: PageMeta): PageTemplate {
            val (w, h) = sizeOf(page)
            return PageTemplate(
                size = PageSizePreset.CUSTOM,
                orientation = if (w > h) Orientation.LANDSCAPE else Orientation.PORTRAIT,
                customWidthPt = w,
                customHeightPt = h,
                paper = page.paper,
            )
        }
    }
}
