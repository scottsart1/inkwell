package com.inkwell.notes.ocr.work

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.inkwell.notes.core.storage.FileStore
import com.inkwell.notes.ocr.text.OcrScaling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** OCR of one image or scanned page, boxes normalised to 0..1 of the image. */
@Serializable
internal data class CachedImageOcr(
    val engine: String,
    val blocks: List<CachedBlock>,
    /** Mean confidence of the local OCR; null when unknown (or for AI results). */
    val confidence: Float? = null,
    /** AI assistance was asked and found no text: these are the local results, not worth paying for again. */
    val aiTried: Boolean = false,
) {
    @Serializable
    data class CachedBlock(val text: String, val box: List<Float>, val confidence: Float? = null)

    val isAi: Boolean get() = engine.startsWith("ai:")
}

/**
 * Recognised text of embedded images and scanned PDF pages, keyed by document, item and file
 * size, so an image placed on many pages, or a scanned page re-indexed after an annotation, is
 * read only once (and an AI transcription is never paid for twice). Kept in no-backup storage
 * and pruned when documents disappear. Every instance shares one lock, so indexing workers running
 * at the same time never write the same file at once.
 */
internal class ImageOcrCache(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(docId: String, key: String, size: Long) =
        File(dir, "${FileStore.safe(docId)}$SEP${FileStore.safe(key)}$SEP$size.json")

    suspend fun get(docId: String, key: String, size: Long): CachedImageOcr? = withContext(Dispatchers.IO) {
        lock.withLock { read(file(docId, key, size)) }
    }

    private fun read(f: File): CachedImageOcr? {
        if (!f.exists()) return null
        return try {
            json.decodeFromString(CachedImageOcr.serializer(), f.readText())
        } catch (e: Exception) {
            f.delete()
            null
        }
    }

    /**
     * Stores [value] for [size]. [stillCurrent] is checked under the cache lock, right before the
     * write: a result for a file that was replaced meanwhile is dropped instead of deleting what
     * [copySize] carried over to the new file.
     */
    suspend fun put(docId: String, key: String, size: Long, value: CachedImageOcr, stillCurrent: () -> Boolean = { true }) =
        withContext(Dispatchers.IO) {
            lock.withLock { if (stillCurrent()) write(docId, key, size, value) }
        }

    private fun write(docId: String, key: String, size: Long, value: CachedImageOcr) {
        try {
            dir.mkdirs()
            // Older results for the same item (previous file sizes) are obsolete.
            val prefix = "${FileStore.safe(docId)}$SEP${FileStore.safe(key)}$SEP"
            dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
            val bytes = json.encodeToString(CachedImageOcr.serializer(), value).encodeToByteArray()
            FileStore.writeAtomically(file(docId, key, size), keepBackup = false) { it.write(bytes) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not cache OCR: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Copies the results of [docId] whose key starts with [keyPrefix] and that were stored for
     * [fromSize] so they are found under [toSize] as well. Used when a file is replaced by a
     * larger one that keeps the earlier items unchanged (pages appended to a source PDF); the
     * old entries stay valid until the replacement lands and are dropped by the next [put].
     */
    suspend fun copySize(docId: String, keyPrefix: String, fromSize: Long, toSize: Long) = withContext(Dispatchers.IO) {
        if (fromSize <= 0 || toSize <= 0 || fromSize == toSize) return@withContext
        val prefix = "${FileStore.safe(docId)}$SEP${FileStore.safe(keyPrefix)}"
        val suffix = "$SEP$fromSize.json"
        lock.withLock {
            try {
                dir.listFiles()?.filter { it.name.startsWith(prefix) && it.name.endsWith(suffix) }?.forEach { f ->
                    val target = File(dir, f.name.removeSuffix(suffix) + "$SEP$toSize.json")
                    val bytes = f.readBytes()
                    FileStore.writeAtomically(target, keepBackup = false) { it.write(bytes) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not carry OCR results over: ${e.javaClass.simpleName}")
            }
        }
        Unit
    }

    /** Forgets a document's cached results (when it is deleted). */
    suspend fun removeDocument(docId: String) = withContext(Dispatchers.IO) {
        val prefix = "${FileStore.safe(docId)}$SEP"
        lock.withLock { dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() } }
        Unit
    }

    /** Deletes results of documents not in [liveDocIds]. */
    suspend fun prune(liveDocIds: Set<String>) = withContext(Dispatchers.IO) {
        val live = liveDocIds.mapTo(HashSet()) { FileStore.safe(it) }
        lock.withLock {
            dir.listFiles()?.forEach { f ->
                val doc = f.name.substringBefore(SEP, missingDelimiterValue = "")
                if (doc.isEmpty() || doc !in live) f.delete()
            }
        }
        Unit
    }

    companion object {
        private const val TAG = "OcrCache"
        private const val SEP = "__"
        /** Folder under noBackupFilesDir. */
        const val DIR = "ocr-cache"
        /** Key prefix of scanned source-PDF pages ("pdfpage-<pdf page index>"). */
        const val PDF_PAGE_KEY = "pdfpage-"
        /** Cache I/O is tiny; one lock for the process keeps workers from interleaving writes. */
        private val lock = Mutex()
        /** Images smaller than this (px, longest edge) carry no readable text. */
        const val MIN_EDGE = 48

        /** Decodes [file] subsampled for OCR; null when unreadable, tiny or out of memory. */
        fun decodeForOcr(file: File): Bitmap? = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || maxOf(bounds.outWidth, bounds.outHeight) < MIN_EDGE) {
                null
            } else {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = OcrScaling.sampleSize(bounds.outWidth, bounds.outHeight)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeFile(file.path, opts)
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Image too large to read")
            null
        } catch (e: Exception) {
            null
        }
    }
}
