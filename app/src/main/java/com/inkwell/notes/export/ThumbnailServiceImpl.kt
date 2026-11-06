package com.inkwell.notes.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PageTemplate
import com.inkwell.notes.export.format.DocSource
import com.inkwell.notes.export.format.PageRaster
import com.inkwell.notes.export.format.RasterSizing
import com.inkwell.notes.export.format.TypedBlocks
import com.inkwell.notes.export.format.TypedLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Library covers and page thumbnails. Covers are re-rendered 1.5 s after the last edit of a
 * document into a new file (thumb_<time>.webp) so image caches never show a stale cover;
 * the previous file is removed once the library points at the new one.
 */
class ThumbnailServiceImpl(private val graph: AppGraph) : ThumbnailService {
    private val source by lazy { DocSource(graph) }
    private val raster by lazy { PageRaster(source) }
    private val pending = ConcurrentHashMap<String, Job>()
    private val pageLocks = ConcurrentHashMap<String, Mutex>()
    private val coverLocks = ConcurrentHashMap<String, Mutex>()

    override fun refresh(docId: String) {
        val job = graph.appScope.launch(start = CoroutineStart.LAZY) {
            delay(DEBOUNCE_MS)
            try {
                coverLocks.getOrPut(docId) { Mutex() }.withLock { renderCover(docId) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Cover not rendered (${t.javaClass.simpleName})")
            }
        }
        pending.put(docId, job)?.cancel()
        job.invokeOnCompletion { pending.remove(docId, job) }
        job.start()
    }

    private suspend fun renderCover(docId: String) {
        val doc = graph.library.getDocument(docId) ?: graph.db.documents().get(docId)?.toModel() ?: return
        val bitmap = coverBitmap(doc) ?: return
        val dir = graph.files.docDir(docId)
        val stamp = System.currentTimeMillis()
        val target = File(dir, "$PREFIX$stamp.webp")
        try {
            withContext(Dispatchers.IO) { writeWebp(bitmap, target) }
        } finally {
            bitmap.recycle()
        }
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                graph.library.setThumbnail(docId, target.absolutePath)
            } catch (t: Throwable) {
                target.delete()
                throw t
            }
            // Only now that the library shows the new cover are older ones removed.
            dir.listFiles()?.forEach { f ->
                val old = (f.name.startsWith(PREFIX) && f.name.endsWith(".webp") && f.name != target.name) || f.name == LEGACY
                if (old && f.isFile) f.delete()
            }
        }
    }

    private suspend fun coverBitmap(doc: DocumentMeta): Bitmap? {
        if (doc.kind == DocKind.TYPED) {
            val typed = source.typed(doc.id)
            return withContext(Dispatchers.Default) {
                TypedLayout(graph.context, doc.title, TypedBlocks.convert(typed), { source.assetFile(doc.id, it) }).renderBitmap(0, COVER_WIDTH)
            }
        }
        val pages = source.pages(doc.id)
        val first = pages.firstOrNull() ?: return blankPaper(doc)
        return raster.render(doc.id, first, source.content(doc.id, first), COVER_WIDTH)
    }

    /** A blank sheet of the default template for documents without pages yet. */
    private suspend fun blankPaper(doc: DocumentMeta): Bitmap {
        val t: PageTemplate = graph.settings.current.notebookDefaults.template
        val page = PageMeta("blank", doc.id, 0, t.widthPt(), t.heightPt(), t.paper)
        return raster.render(doc.id, page, com.inkwell.notes.core.model.PageContent.EMPTY, COVER_WIDTH)
    }

    override suspend fun pageThumbnail(docId: String, pageId: String, widthPx: Int): Bitmap? {
        val width = widthPx.coerceIn(16, 2048)
        return try {
            pageLocks.getOrPut(pageId) { Mutex() }.withLock {
                val page = withContext(Dispatchers.IO) { graph.library.getPage(pageId) ?: graph.db.pages().get(pageId)?.toModel() }
                    ?: return@withLock null
                val file = graph.files.pageThumbnailFile(docId, pageId)
                withContext(Dispatchers.IO) { cached(file, page, width) }?.let { return@withLock it }
                val bmp = raster.render(docId, page, source.content(docId, page), width)
                withContext(Dispatchers.IO) {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    try {
                        writeWebp(bmp, tmp)
                        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
                        // Stamp the cache with the page version read before its content: an edit
                        // saved while this rendered (newer modifiedAt) must still make it stale.
                        if (page.modifiedAt in 1..System.currentTimeMillis()) file.setLastModified(page.modifiedAt)
                    } catch (e: Exception) {
                        tmp.delete()
                        Log.w(TAG, "Page thumbnail not cached (${e.javaClass.simpleName})")
                    }
                }
                bmp
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Page thumbnail failed (${t.javaClass.simpleName})")
            null
        }
    }

    /** The cached thumbnail when it is newer than the page and wide enough. */
    private fun cached(file: File, page: PageMeta, width: Int): Bitmap? {
        if (!file.isFile || file.lastModified() < page.modifiedAt) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0 || o.outWidth < width * 0.9f) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = RasterSizing.sampleSize(o.outWidth, o.outHeight, width, 1) }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    private fun writeWebp(bitmap: Bitmap, target: File) {
        target.parentFile?.mkdirs()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        target.outputStream().use { out ->
            if (!bitmap.compress(format, QUALITY, out)) throw java.io.IOException("WebP encoding failed")
        }
    }

    companion object {
        private const val TAG = "Thumbnails"
        private const val DEBOUNCE_MS = 1500L
        private const val COVER_WIDTH = 480
        private const val QUALITY = 82
        private const val PREFIX = "thumb_"
        private const val LEGACY = "thumb.webp"
    }
}
