package com.inkwell.notes.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Renders source-PDF pages with the platform [PdfRenderer]. One renderer per open document,
 * all access serialised on a dedicated thread (a renderer allows a single open page at a time,
 * and pdfium is not re-entrant). Renders land in an LRU bitmap cache keyed by
 * (document, page, width bucket).
 *
 * Bitmaps returned from [render] are shared with the cache: draw them, never recycle them.
 */
internal class PdfRenderEngine(
    private val sourceFile: (docId: String) -> File,
    private val scope: CoroutineScope,
    maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
) {
    /** The thread every [PdfRenderer] call runs on. */
    val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "inkwell-pdf-render").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val cache = SizedLruCache<RenderKey, Bitmap>(RenderSizing.cacheBudgetBytes(maxHeapBytes)) {
        it.allocationByteCount.toLong()
    }

    /** Open renderers, least recently used first. Only touched on [dispatcher]. */
    private val open = LinkedHashMap<String, OpenPdf>(8, 0.75f, true)

    private class OpenPdf(
        val file: File,
        val stamp: FileStamp,
        val descriptor: ParcelFileDescriptor,
        val renderer: PdfRenderer,
    ) {
        val pageCount: Int = renderer.pageCount
        var sizes: List<Pair<Float, Float>>? = null

        fun close() {
            try {
                renderer.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Closing renderer failed (${t.javaClass.simpleName})")
            }
            runCatching { descriptor.close() }
        }
    }

    /** Detects a source file that was replaced behind an open renderer. */
    private data class FileStamp(val length: Long, val modified: Long) {
        companion object {
            fun of(file: File) = FileStamp(file.length(), file.lastModified())
        }
    }

    /** Outcome of opening a file that is not (yet) a document's source. */
    sealed interface Probe {
        data class Ok(val sizes: List<Pair<Float, Float>>) : Probe
        /** Encrypted: PdfRenderer refuses it without a password. */
        data object Locked : Probe
        data object Damaged : Probe
    }

    suspend fun render(docId: String, pdfPageIndex: Int, widthPx: Int, heightPx: Int): Bitmap? {
        if (pdfPageIndex < 0) return null
        val target = RenderSizing.target(widthPx, heightPx) ?: return null
        val key = RenderKey(docId, pdfPageIndex, target.width)
        cached(key, target)?.let { return it }
        return withContext(dispatcher) {
            // Another request may have rendered it while this one waited for the thread.
            cached(key, target)?.let { return@withContext it }
            val pdf = openPdf(docId) ?: return@withContext null
            if (pdfPageIndex >= pdf.pageCount) return@withContext null
            val bitmap = draw(docId, pdf, pdfPageIndex, target) ?: return@withContext null
            // A valid entry of another shape (a caller asking for a different aspect ratio) is
            // kept, so two such callers never evict each other in a loop.
            val existing = cache.get(key)
            if (existing == null || existing.isRecycled) cache.put(key, bitmap)
            bitmap
        }
    }

    private fun cached(key: RenderKey, target: PixelSize): Bitmap? {
        val hit = cache.get(key) ?: return null
        if (hit.isRecycled) {
            cache.remove(key)
            return null
        }
        return hit.takeIf { RenderSizing.matches(it.width, it.height, target) }
    }

    /**
     * Page sizes in points, read once per open document. They are read through a separate
     * batched [probe] so a long document never stalls on-screen renders.
     */
    suspend fun pageSizes(docId: String): List<Pair<Float, Float>> {
        val (known, stamp) = withContext(dispatcher) {
            val pdf = openPdf(docId)
            pdf?.sizes to pdf?.stamp
        }
        if (known != null) return known
        if (stamp == null) return emptyList()
        val probed = probe(sourceFile(docId)) as? Probe.Ok ?: return emptyList()
        withContext(dispatcher) {
            // Remember them unless the file was replaced meanwhile.
            open[docId]?.takeIf { it.stamp == stamp }?.sizes = probed.sizes
        }
        return probed.sizes
    }

    suspend fun pageCount(docId: String): Int = withContext(dispatcher) { openPdf(docId)?.pageCount ?: 0 }

    private class ProbeHandle(val descriptor: ParcelFileDescriptor, val renderer: PdfRenderer) {
        val pageCount: Int = renderer.pageCount
    }

    /**
     * Opens an arbitrary PDF file (an import in progress) to validate it and read its page
     * sizes. Pages are read in small batches, each a separate task on the render thread, so
     * on-screen renders queued meanwhile are not held up by a long document.
     */
    suspend fun probe(file: File): Probe {
        // A caller cancelled before this point never opens anything.
        currentCoroutineContext().ensureActive()
        var failure: Probe = Probe.Damaged
        // Set on the render thread as soon as the renderer exists. withContext drops its result
        // when the caller is cancelled meanwhile, so the finally below closes it from here.
        var opened: ProbeHandle? = null
        try {
            withContext<Unit>(dispatcher) {
                var descriptor: ParcelFileDescriptor? = null
                try {
                    descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    opened = ProbeHandle(descriptor, PdfRenderer(descriptor))
                } catch (e: SecurityException) {
                    failure = Probe.Locked
                    runCatching { descriptor?.close() }
                } catch (e: Exception) {
                    Log.w(TAG, "PDF probe failed (${e.javaClass.simpleName})")
                    runCatching { descriptor?.close() }
                }
            }
            val handle = opened ?: return failure
            val count = handle.pageCount
            val sizes = ArrayList<Pair<Float, Float>>(count)
            while (sizes.size < count) {
                withContext(dispatcher) {
                    val end = minOf(count, sizes.size + PROBE_BATCH)
                    for (i in sizes.size until end) {
                        sizes += handle.renderer.openPage(i).use { page -> page.width.toFloat() to page.height.toFloat() }
                    }
                }
            }
            return Probe.Ok(sizes)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "Reading pages failed (${e.javaClass.simpleName})")
            return Probe.Damaged
        } finally {
            opened?.let { handle ->
                withContext(NonCancellable + dispatcher) {
                    runCatching { handle.renderer.close() }
                    runCatching { handle.descriptor.close() }
                }
            }
        }
    }

    /**
     * Moves [replacement] over the document's source file on the render thread, after closing
     * its renderer and dropping its cached pages, so no render ever sees a half-replaced file.
     */
    suspend fun replaceSource(docId: String, replacement: File) = withContext(dispatcher) {
        closeDoc(docId)
        val target = sourceFile(docId)
        target.parentFile?.mkdirs()
        if (!replacement.renameTo(target)) {
            // Different file systems: copy next to the target, then rename atomically.
            val tmp = File(target.parentFile, target.name + ".incoming")
            try {
                replacement.copyTo(tmp, overwrite = true)
                if (!tmp.renameTo(target)) throw IOException("Could not replace the source PDF")
            } finally {
                tmp.delete()
            }
            replacement.delete()
        }
        cache.removeIf { it.docId == docId }
    }

    /** Releases the document's renderer and cached pages. Safe from any thread. */
    fun close(docId: String) {
        cache.removeIf { it.docId == docId }
        scope.launch(dispatcher) { closeDoc(docId) }
    }

    /** Drops cached bitmaps under memory pressure; [fraction] of the budget is kept. */
    fun trimCache(fraction: Float) {
        if (fraction <= 0f) cache.clear() else cache.trimToFraction(fraction)
    }

    private fun closeDoc(docId: String) {
        open.remove(docId)?.close()
        cache.removeIf { it.docId == docId }
    }

    /** Must run on [dispatcher]. */
    private fun openPdf(docId: String): OpenPdf? {
        val file = sourceFile(docId)
        val existing = open[docId]
        if (!file.isFile) {
            if (existing != null) closeDoc(docId)
            return null
        }
        val stamp = FileStamp.of(file)
        if (existing != null) {
            if (existing.stamp == stamp) return existing
            // The file changed (merge, restore): reopen and forget stale renders.
            closeDoc(docId)
        }
        var descriptor: ParcelFileDescriptor? = null
        return try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val pdf = OpenPdf(file, stamp, descriptor, PdfRenderer(descriptor))
            open[docId] = pdf
            while (open.size > MAX_OPEN) {
                val eldest = open.entries.iterator().next()
                open.remove(eldest.key)
                eldest.value.close()
            }
            pdf
        } catch (e: Exception) {
            Log.w(TAG, "Opening a source PDF failed (${e.javaClass.simpleName})")
            runCatching { descriptor?.close() }
            null
        }
    }

    /** Must run on [dispatcher]. */
    private fun draw(docId: String, pdf: OpenPdf, index: Int, target: PixelSize): Bitmap? {
        val bitmap = allocate(target) ?: return null
        return try {
            bitmap.eraseColor(Color.WHITE)
            pdf.renderer.openPage(index).use { page ->
                val pw = page.width.coerceAtLeast(1).toFloat()
                val ph = page.height.coerceAtLeast(1).toFloat()
                val matrix = Matrix().apply { setScale(bitmap.width / pw, bitmap.height / ph) }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Rendering page $index failed (${e.javaClass.simpleName})")
            // A renderer in a bad state is reopened on the next request.
            if (e is IllegalStateException) closeDoc(docId)
            null
        }
    }

    private fun allocate(size: PixelSize): Bitmap? {
        try {
            return Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "Out of memory for a ${size.width}x${size.height} render; clearing the cache")
        } catch (e: IllegalArgumentException) {
            return null
        }
        cache.clear()
        return try {
            Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            null
        }
    }

    companion object {
        private const val TAG = "PdfRender"
        /** Open renderers kept at once (each holds a file descriptor and native memory). */
        const val MAX_OPEN = 4

        /** Pages whose size is read per render-thread task while probing a new file. */
        const val PROBE_BATCH = 24
    }
}
