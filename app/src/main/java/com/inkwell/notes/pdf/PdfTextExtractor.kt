package com.inkwell.notes.pdf

import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded text of source-PDF pages (pdfbox [PDFTextStripper], reading order by position).
 * Results are cached per page. The parsed document stays open between calls, because the
 * indexer asks for every page in turn, and closes after a short idle period.
 */
internal class PdfTextExtractor(
    private val pdfBox: PdfBoxOps,
    private val sourceFile: (docId: String) -> File,
    private val scope: CoroutineScope,
) {
    private val cache = SizedLruCache<String, String>(MAX_CACHED_CHARS, MAX_CACHED_CHARS / 4) { it.length.toLong() + 16 }
    private val mutex = Mutex()

    private class OpenText(val docId: String, val file: File, val length: Long, val modified: Long, val doc: PDDocument)

    // Guarded by [mutex].
    private var current: OpenText? = null
    private var idleClose: Job? = null

    /** Bumped by [invalidate]; an extraction that started before keeps its result out of the cache. */
    private val generations = ConcurrentHashMap<String, Int>()

    /** Source files that ran out of memory here (keyed by their version, so a replaced file is tried again). */
    private val outOfMemoryFailures = PdfTextOutOfMemory()

    suspend fun pageText(docId: String, pdfPageIndex: Int): String = pageTextOrNull(docId, pdfPageIndex).orEmpty()

    /**
     * Like [pageText], but null when the text could not be read for now (out of memory), so the
     * indexer can try again later instead of taking the page for one without a text layer. Once a
     * file has run out of memory [PdfTextOutOfMemory.GIVE_UP_AFTER] times while loading it is not
     * loaded again, and a page that ran out of memory that often is not read again (null right
     * away, see [givenUp]); the file's other pages are still read.
     */
    suspend fun pageTextOrNull(docId: String, pdfPageIndex: Int): String? {
        if (pdfPageIndex < 0) return ""
        val key = PdfText.cacheKey(docId, pdfPageIndex)
        cache.get(key)?.let { return it }
        val generation = generations[docId] ?: 0
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                cache.get(key)?.let { return@withLock it }
                val file = sourceFile(docId)
                val length = file.length()
                val modified = file.lastModified()
                // Loading it (or reading this page) again would only run out of memory again.
                if (outOfMemoryFailures.givenUp(docId, length, modified, pdfPageIndex)) return@withLock null
                val opened = try {
                    openLocked(docId)
                } catch (oom: OutOfMemoryError) {
                    Log.w(TAG, "Opening a PDF for text ran out of memory")
                    outOfMemoryFailures.record(docId, length, modified)
                    return@withLock null
                }
                val doc = opened ?: return@withLock ""
                if (pdfPageIndex >= doc.numberOfPages) return@withLock ""
                var outOfMemory = false
                val text = try {
                    val stripper = PDFTextStripper().apply {
                        sortByPosition = true
                        startPage = pdfPageIndex + 1
                        endPage = pdfPageIndex + 1
                        lineSeparator = "\n"
                    }
                    PdfText.normalize(stripper.getText(doc)).also { outOfMemoryFailures.recordSuccess(docId) }
                } catch (e: Exception) {
                    // Broken content streams or fonts: OCR covers the page instead.
                    Log.w(TAG, "Text extraction failed for a page (${e.javaClass.simpleName})")
                    null
                } catch (oom: OutOfMemoryError) {
                    Log.w(TAG, "Text extraction ran out of memory")
                    closeLocked()
                    // This page's content is too heavy; the file's other pages may read fine.
                    outOfMemoryFailures.record(docId, length, modified, pdfPageIndex)
                    outOfMemory = true
                    null
                }
                scheduleIdleClose()
                if (text != null && (generations[docId] ?: 0) == generation) cache.put(key, text)
                if (outOfMemory) null else text.orEmpty()
            }
        }
    }

    /**
     * True when the text layer of page [pdfPageIndex] of [docId]'s current source file (or the
     * file as a whole) keeps running out of memory and is no longer read in this process: the
     * page is best OCRed instead.
     */
    suspend fun givenUp(docId: String, pdfPageIndex: Int): Boolean = withContext(Dispatchers.IO) {
        val file = sourceFile(docId)
        outOfMemoryFailures.givenUp(docId, file.length(), file.lastModified(), pdfPageIndex)
    }

    /** Forgets cached text and closes the parsed document (source replaced or document closed). */
    fun invalidate(docId: String) {
        generations.merge(docId, 1, Int::plus)
        cache.removeIf { PdfText.isKeyOf(it, docId) }
        scope.launch(Dispatchers.IO) {
            mutex.withLock { if (current?.docId == docId) closeLocked() }
        }
    }

    /** Null when the source is missing or unreadable; throws [OutOfMemoryError] (worth retrying later). */
    private fun openLocked(docId: String): PDDocument? {
        val file = sourceFile(docId)
        if (!file.isFile) {
            closeLocked()
            return null
        }
        current?.let { c ->
            if (c.docId == docId && c.length == file.length() && c.modified == file.lastModified()) return c.doc
            closeLocked()
        }
        return try {
            val doc = pdfBox.open(file)
            current = OpenText(docId, file, file.length(), file.lastModified(), doc)
            doc
        } catch (e: Exception) {
            Log.w(TAG, "Opening a PDF for text failed (${e.javaClass.simpleName})")
            null
        }
    }

    private fun closeLocked() {
        val c = current ?: return
        current = null
        runCatching { c.doc.close() }
    }

    private fun scheduleIdleClose() {
        idleClose?.cancel()
        idleClose = scope.launch(Dispatchers.IO) {
            delay(IDLE_CLOSE_MS)
            mutex.withLock { closeLocked() }
        }
    }

    companion object {
        private const val TAG = "PdfText"
        private const val IDLE_CLOSE_MS = 20_000L
        /** About 8 MB of UTF-16 text. */
        private const val MAX_CACHED_CHARS = 4L * 1024 * 1024
    }
}
